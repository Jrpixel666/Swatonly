//! The encrypted wallet file.
//!
//! Layout (little-endian):
//!
//! ```text
//! magic        8   "XMRVAULT"
//! version      2   1
//! m_cost_kib   4   Argon2id memory
//! t_cost       4   Argon2id passes
//! p_cost       4   Argon2id lanes
//! salt        32   random, per PIN
//! flags        1   bit 0: a duress PIN is set
//! duress_salt 32
//! duress_tag  32   SHA-256(domain || Argon2id(duress PIN))
//! nonce       24   random, per write
//! ct_len       4
//! ciphertext  ct_len   XChaCha20-Poly1305(state), AAD = every byte above
//! ```
//!
//! The key is `Argon2id(password = PIN, salt, secret = device pepper)`. The pepper is a random
//! 32-byte value that only exists wrapped by a non-exportable Android Keystore key (StrongBox when
//! the device has one), so a copied vault file can't be brute forced off the device, and deleting
//! the Keystore key destroys the wallet even if flash storage keeps old blocks around.

use std::{
  fs::{self, File, OpenOptions},
  io::Write as _,
  path::{Path, PathBuf},
};

use argon2::{Algorithm, Argon2, Params, Version};
use chacha20poly1305::{
  aead::{Aead, KeyInit, Payload},
  XChaCha20Poly1305, XNonce,
};
use rand_core::{OsRng, RngCore};
use sha2::{Digest, Sha256};
use subtle::ConstantTimeEq;
use zeroize::Zeroizing;

use crate::Error;

const MAGIC: &[u8; 8] = b"XMRVAULT";
const VERSION: u16 = 1;
const HEADER_LEN: usize = 8 + 2 + 12 + 32 + 1 + 32 + 32 + 24 + 4;
const MAX_CIPHERTEXT: usize = 256 * 1024 * 1024;
const DURESS_DOMAIN: &[u8] = b"xmrvault duress v1";

/// Argon2id cost. 128 MiB and 3 passes takes about a second on a current phone.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct KdfParams {
  pub m_cost_kib: u32,
  pub t_cost: u32,
  pub p_cost: u32,
}

impl KdfParams {
  pub const DEFAULT: KdfParams = KdfParams { m_cost_kib: 128 * 1024, t_cost: 3, p_cost: 1 };
  /// Only for tests: hashing at full cost makes them crawl.
  pub const TEST: KdfParams = KdfParams { m_cost_kib: 64, t_cost: 1, p_cost: 1 };

  fn check(&self) -> Result<(), Error> {
    // Refuse headers that were tampered with to make brute forcing cheap or to exhaust memory.
    let ok = (KdfParams::TEST.m_cost_kib ..= 1024 * 1024).contains(&self.m_cost_kib) &&
      (1 ..= 64).contains(&self.t_cost) &&
      (1 ..= 8).contains(&self.p_cost);
    if ok {
      Ok(())
    } else {
      Err(Error::Corrupt("unsupported key derivation parameters"))
    }
  }
}

pub fn derive_key(
  pin: &[u8],
  pepper: &[u8],
  salt: &[u8; 32],
  params: KdfParams,
) -> Result<Zeroizing<[u8; 32]>, Error> {
  params.check()?;
  if pin.is_empty() {
    return Err(Error::Input("empty PIN"));
  }
  if pepper.len() != 32 {
    return Err(Error::Input("device key must be 32 bytes"));
  }
  let argon_params = Params::new(params.m_cost_kib, params.t_cost, params.p_cost, Some(32))
    .map_err(|_| Error::Corrupt("bad key derivation parameters"))?;
  let argon = Argon2::new_with_secret(pepper, Algorithm::Argon2id, Version::V0x13, argon_params)
    .map_err(|_| Error::Internal("argon2 init"))?;
  let mut key = Zeroizing::new([0u8; 32]);
  argon.hash_password_into(pin, salt, key.as_mut()).map_err(|_| Error::Internal("argon2"))?;
  Ok(key)
}

fn duress_tag(key: &[u8; 32]) -> [u8; 32] {
  let mut h = Sha256::new();
  h.update(DURESS_DOMAIN);
  h.update(key);
  h.finalize().into()
}

/// A duress PIN verifier: unlocking with that PIN destroys the wallet instead.
#[derive(Clone)]
pub struct Duress {
  salt: [u8; 32],
  tag: [u8; 32],
}

/// An open vault: where it lives and the key that seals it.
pub struct Vault {
  path: PathBuf,
  params: KdfParams,
  salt: [u8; 32],
  key: Zeroizing<[u8; 32]>,
  duress: Option<Duress>,
}

/// What an unlock attempt found.
pub enum Unlocked {
  Opened(Vault, Zeroizing<Vec<u8>>),
  WrongPin,
  Duress,
}

struct Header {
  params: KdfParams,
  salt: [u8; 32],
  duress: Option<Duress>,
  nonce: [u8; 24],
  ct_len: usize,
}

fn take<const N: usize>(bytes: &[u8], at: &mut usize) -> [u8; N] {
  let mut out = [0u8; N];
  out.copy_from_slice(&bytes[*at .. *at + N]);
  *at += N;
  out
}

fn parse_header(bytes: &[u8]) -> Result<Header, Error> {
  if bytes.len() < HEADER_LEN || &bytes[.. 8] != MAGIC {
    return Err(Error::Corrupt("not a wallet file"));
  }
  let mut at = 8;
  if u16::from_le_bytes(take(bytes, &mut at)) != VERSION {
    return Err(Error::Corrupt("unsupported wallet file version"));
  }
  let params = KdfParams {
    m_cost_kib: u32::from_le_bytes(take(bytes, &mut at)),
    t_cost: u32::from_le_bytes(take(bytes, &mut at)),
    p_cost: u32::from_le_bytes(take(bytes, &mut at)),
  };
  params.check()?;
  let salt = take(bytes, &mut at);
  let flags = take::<1>(bytes, &mut at)[0];
  let duress_salt = take(bytes, &mut at);
  let duress_tag = take(bytes, &mut at);
  let nonce = take(bytes, &mut at);
  let ct_len = u32::from_le_bytes(take(bytes, &mut at)) as usize;
  if flags & !1 != 0 {
    return Err(Error::Corrupt("unknown wallet file flags"));
  }
  if ct_len > MAX_CIPHERTEXT || bytes.len() != HEADER_LEN + ct_len {
    return Err(Error::Corrupt("truncated wallet file"));
  }
  let duress = (flags & 1 == 1).then_some(Duress { salt: duress_salt, tag: duress_tag });
  Ok(Header { params, salt, duress, nonce, ct_len })
}

pub fn exists(path: &Path) -> bool {
  path.is_file()
}

impl Vault {
  /// Create a vault sealed with `pin`. Nothing is written until [`Vault::save`].
  pub fn create(
    path: PathBuf,
    pin: &[u8],
    pepper: &[u8],
    params: KdfParams,
  ) -> Result<Vault, Error> {
    let mut salt = [0u8; 32];
    OsRng.fill_bytes(&mut salt);
    let key = derive_key(pin, pepper, &salt, params)?;
    Ok(Vault { path, params, salt, key, duress: None })
  }

  /// Try to open the vault at `path`.
  pub fn open(path: PathBuf, pin: &[u8], pepper: &[u8]) -> Result<Unlocked, Error> {
    let bytes = Zeroizing::new(fs::read(&path).map_err(|_| Error::NoWallet)?);
    let header = parse_header(&bytes)?;
    let key = derive_key(pin, pepper, &header.salt, header.params)?;
    let aad = &bytes[.. HEADER_LEN];
    let ct = &bytes[HEADER_LEN .. HEADER_LEN + header.ct_len];
    let cipher = XChaCha20Poly1305::new(key.as_ref().into());
    match cipher.decrypt(XNonce::from_slice(&header.nonce), Payload { msg: ct, aad }) {
      Ok(plain) => Ok(Unlocked::Opened(
        Vault { path, params: header.params, salt: header.salt, key, duress: header.duress },
        Zeroizing::new(plain),
      )),
      Err(_) => {
        // Only on failure, so a normal unlock costs one derivation.
        if let Some(duress) = &header.duress {
          let dkey = derive_key(pin, pepper, &duress.salt, header.params)?;
          if bool::from(duress_tag(&dkey).ct_eq(&duress.tag)) {
            return Ok(Unlocked::Duress);
          }
        }
        Ok(Unlocked::WrongPin)
      }
    }
  }

  /// Whether `pin` is the PIN this vault is sealed with. Costs one key derivation.
  pub fn verify_pin(&self, pin: &[u8], pepper: &[u8]) -> Result<bool, Error> {
    let key = derive_key(pin, pepper, &self.salt, self.params)?;
    Ok(bool::from(key.as_ref().ct_eq(self.key.as_ref())))
  }

  /// Re-seal with a new PIN (fresh salt). Takes effect at the next [`Vault::save`].
  pub fn change_pin(&mut self, new_pin: &[u8], pepper: &[u8]) -> Result<(), Error> {
    let mut salt = [0u8; 32];
    OsRng.fill_bytes(&mut salt);
    self.key = derive_key(new_pin, pepper, &salt, self.params)?;
    self.salt = salt;
    Ok(())
  }

  pub fn set_duress_pin(&mut self, duress_pin: Option<&[u8]>, pepper: &[u8]) -> Result<(), Error> {
    self.duress = match duress_pin {
      None => None,
      Some(pin) => {
        if self.verify_pin(pin, pepper)? {
          return Err(Error::Input("the duress PIN must differ from the PIN"));
        }
        let mut salt = [0u8; 32];
        OsRng.fill_bytes(&mut salt);
        let dkey = derive_key(pin, pepper, &salt, self.params)?;
        Some(Duress { salt, tag: duress_tag(&dkey) })
      }
    };
    Ok(())
  }

  pub fn has_duress_pin(&self) -> bool {
    self.duress.is_some()
  }

  fn seal(&self, plain: &[u8]) -> Result<Zeroizing<Vec<u8>>, Error> {
    if plain.len() + 16 > MAX_CIPHERTEXT {
      return Err(Error::Internal("wallet too large"));
    }
    let mut nonce = [0u8; 24];
    OsRng.fill_bytes(&mut nonce);
    let mut out = Zeroizing::new(Vec::with_capacity(HEADER_LEN + plain.len() + 16));
    out.extend_from_slice(MAGIC);
    out.extend_from_slice(&VERSION.to_le_bytes());
    out.extend_from_slice(&self.params.m_cost_kib.to_le_bytes());
    out.extend_from_slice(&self.params.t_cost.to_le_bytes());
    out.extend_from_slice(&self.params.p_cost.to_le_bytes());
    out.extend_from_slice(&self.salt);
    let (flags, dsalt, dtag) = match &self.duress {
      Some(d) => (1u8, d.salt, d.tag),
      None => (0u8, [0; 32], [0; 32]),
    };
    out.push(flags);
    out.extend_from_slice(&dsalt);
    out.extend_from_slice(&dtag);
    out.extend_from_slice(&nonce);
    out.extend_from_slice(&u32::try_from(plain.len() + 16).unwrap_or(u32::MAX).to_le_bytes());
    debug_assert_eq!(out.len(), HEADER_LEN);
    let cipher = XChaCha20Poly1305::new(self.key.as_ref().into());
    let ct = cipher
      .encrypt(XNonce::from_slice(&nonce), Payload { msg: plain, aad: &out[.. HEADER_LEN] })
      .map_err(|_| Error::Internal("encrypt"))?;
    out.extend_from_slice(&ct);
    Ok(out)
  }

  /// Encrypt `plain` and atomically replace the wallet file with it.
  pub fn save(&self, plain: &[u8]) -> Result<(), Error> {
    let sealed = self.seal(plain)?;
    let tmp = self.path.with_extension("tmp");
    {
      let mut f = OpenOptions::new()
        .write(true)
        .create(true)
        .truncate(true)
        .open(&tmp)
        .map_err(|_| Error::Io("can't write wallet file"))?;
      f.write_all(&sealed).map_err(|_| Error::Io("can't write wallet file"))?;
      f.sync_all().map_err(|_| Error::Io("can't sync wallet file"))?;
    }
    fs::rename(&tmp, &self.path).map_err(|_| Error::Io("can't replace wallet file"))?;
    if let Some(dir) = self.path.parent() {
      if let Ok(d) = File::open(dir) {
        let _ = d.sync_all();
      }
    }
    Ok(())
  }

  pub fn path(&self) -> &Path {
    &self.path
  }
}

/// Overwrite the wallet file with random bytes, then delete it.
///
/// Flash storage may keep old copies, which is why the real erasure is deleting the Keystore key
/// that wraps the pepper (done on the Android side).
pub fn destroy(path: &Path) {
  for p in [path.to_path_buf(), path.with_extension("tmp")] {
    if let Ok(meta) = fs::metadata(&p) {
      if let Ok(mut f) = OpenOptions::new().write(true).open(&p) {
        let mut buf = vec![0u8; 64 * 1024];
        let mut left = meta.len();
        while left > 0 {
          OsRng.fill_bytes(&mut buf);
          let n = left.min(buf.len() as u64) as usize;
          if f.write_all(&buf[.. n]).is_err() {
            break;
          }
          left -= n as u64;
        }
        let _ = f.sync_all();
      }
      let _ = fs::remove_file(&p);
    }
  }
}

#[cfg(test)]
mod tests {
  use super::*;

  const PEPPER: [u8; 32] = [7; 32];

  fn vault(dir: &tempfile::TempDir) -> Vault {
    Vault::create(dir.path().join("w.vault"), b"123456", &PEPPER, KdfParams::TEST).unwrap()
  }

  #[test]
  fn roundtrip_and_wrong_pin() {
    let dir = tempfile::tempdir().unwrap();
    let v = vault(&dir);
    v.save(b"secret state").unwrap();
    let path = v.path().to_path_buf();
    match Vault::open(path.clone(), b"123456", &PEPPER).unwrap() {
      Unlocked::Opened(_, plain) => assert_eq!(&plain[..], b"secret state"),
      _ => panic!("should open"),
    }
    assert!(matches!(Vault::open(path.clone(), b"654321", &PEPPER).unwrap(), Unlocked::WrongPin));
    // Right PIN, wrong device key.
    assert!(matches!(Vault::open(path, b"123456", &[8; 32]).unwrap(), Unlocked::WrongPin));
  }

  #[test]
  fn tamper_is_detected() {
    let dir = tempfile::tempdir().unwrap();
    let v = vault(&dir);
    v.save(b"secret state").unwrap();
    let mut bytes = fs::read(v.path()).unwrap();
    // Flip a ciphertext bit, then a header bit (the duress flag).
    for at in [bytes.len() - 1, 8 + 2 + 12 + 32] {
      let orig = bytes[at];
      bytes[at] ^= 1;
      fs::write(v.path(), &bytes).unwrap();
      assert!(!matches!(
        Vault::open(v.path().to_path_buf(), b"123456", &PEPPER),
        Ok(Unlocked::Opened(..))
      ));
      bytes[at] = orig;
    }
  }

  #[test]
  fn weakened_kdf_is_refused() {
    let dir = tempfile::tempdir().unwrap();
    let v = vault(&dir);
    v.save(b"x").unwrap();
    let mut bytes = fs::read(v.path()).unwrap();
    bytes[10 .. 14].copy_from_slice(&8u32.to_le_bytes());
    fs::write(v.path(), &bytes).unwrap();
    assert!(Vault::open(v.path().to_path_buf(), b"123456", &PEPPER).is_err());
  }

  #[test]
  fn duress_and_pin_change() {
    let dir = tempfile::tempdir().unwrap();
    let mut v = vault(&dir);
    assert!(v.set_duress_pin(Some(b"123456"), &PEPPER).is_err());
    v.set_duress_pin(Some(b"999999"), &PEPPER).unwrap();
    v.change_pin(b"24680135", &PEPPER).unwrap();
    v.save(b"s").unwrap();
    let path = v.path().to_path_buf();
    assert!(v.verify_pin(b"24680135", &PEPPER).unwrap());
    assert!(!v.verify_pin(b"123456", &PEPPER).unwrap());
    assert!(matches!(Vault::open(path.clone(), b"999999", &PEPPER).unwrap(), Unlocked::Duress));
    assert!(matches!(Vault::open(path.clone(), b"123456", &PEPPER).unwrap(), Unlocked::WrongPin));
    assert!(matches!(Vault::open(path.clone(), b"24680135", &PEPPER).unwrap(), Unlocked::Opened(..)));
    destroy(&path);
    assert!(!exists(&path));
  }
}
