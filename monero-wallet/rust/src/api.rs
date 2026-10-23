//! The wallet session: one unlocked wallet at a time, wiped from memory on lock.

use std::{
  path::PathBuf,
  sync::{Mutex, MutexGuard, OnceLock},
};

use serde_json::json;
use zeroize::Zeroizing;

use crate::{
  keys::{self, Keys, Net, SeedKind},
  net::{self, Daemon, NodeConfig},
  vault::{self, KdfParams, Unlocked, Vault},
  wallet::{self, Prepared, State},
  Error,
};

const MIN_PIN: usize = 6;
const MAX_PIN: usize = 64;
const MAX_SUBADDRESSES: u32 = 10_000;

struct Session {
  vault: Vault,
  state: State,
  keys: Keys,
  daemon: Option<Daemon>,
  prepared: Option<Prepared>,
  rt: tokio::runtime::Runtime,
}

static DATA_DIR: OnceLock<PathBuf> = OnceLock::new();
static SESSION: Mutex<Option<Session>> = Mutex::new(None);
static KDF: Mutex<KdfParams> = Mutex::new(KdfParams::DEFAULT);

fn session() -> MutexGuard<'static, Option<Session>> {
  SESSION.lock().unwrap_or_else(|p| p.into_inner())
}

fn vault_path() -> Result<PathBuf, Error> {
  DATA_DIR.get().map(|d| d.join("wallet.vault")).ok_or(Error::Internal("not initialized"))
}

fn check_new_pin(pin: &[u8]) -> Result<(), Error> {
  if pin.len() < MIN_PIN || pin.len() > MAX_PIN {
    return Err(Error::Input("the PIN must be 6 to 64 digits"));
  }
  if !pin.iter().all(u8::is_ascii_digit) {
    return Err(Error::Input("the PIN must be digits"));
  }
  // Refuse the obvious: all one digit, or a straight run.
  let same = pin.iter().all(|d| *d == pin[0]);
  let up = pin.windows(2).all(|w| w[1] == w[0] + 1);
  let down = pin.windows(2).all(|w| w[0] == w[1] + 1);
  if same || up || down {
    return Err(Error::Input("that PIN is too easy to guess"));
  }
  Ok(())
}

fn runtime() -> Result<tokio::runtime::Runtime, Error> {
  tokio::runtime::Builder::new_current_thread()
    .enable_all()
    .build()
    .map_err(|_| Error::Internal("runtime"))
}

/// Where the wallet lives (the app's no-backup directory).
pub fn init(dir: PathBuf) -> Result<(), Error> {
  if let Some(existing) = DATA_DIR.get() {
    return if *existing == dir { Ok(()) } else { Err(Error::Internal("already initialized")) };
  }
  let _ = DATA_DIR.set(dir);
  Ok(())
}

#[doc(hidden)]
pub fn set_kdf_for_tests(params: KdfParams) {
  *KDF.lock().unwrap_or_else(|p| p.into_inner()) = params;
}

pub fn exists() -> bool {
  vault_path().map(|p| vault::exists(&p)).unwrap_or(false)
}

fn start(vault: Vault, state: State) -> Result<(), Error> {
  let keys = keys::keys(&state.mnemonic(), state.net)?;
  *session() = Some(Session { vault, state, keys, daemon: None, prepared: None, rt: runtime()? });
  Ok(())
}

fn new_wallet(
  pin: &[u8],
  pepper: &[u8],
  net: Net,
  mnemonic: &keys::Mnemonic,
  restore_height: Option<u64>,
  fresh: bool,
) -> Result<(), Error> {
  check_new_pin(pin)?;
  let path = vault_path()?;
  if vault::exists(&path) {
    return Err(Error::Input("a wallet already exists; wipe it first"));
  }
  let kdf = *KDF.lock().unwrap_or_else(|p| p.into_inner());
  let vault = Vault::create(path, pin, pepper, kdf)?;
  let state = State::new(net, mnemonic, restore_height, fresh);
  vault.save(&state.to_bytes()?)?;
  start(vault, state)
}

/// Make a new wallet. Returns the mnemonic to show the user once.
pub fn create(pin: &[u8], pepper: &[u8], net: Net, kind: SeedKind) -> Result<Zeroizing<String>, Error> {
  let mnemonic = keys::generate(kind);
  new_wallet(pin, pepper, net, &mnemonic, None, true)?;
  Ok(mnemonic.phrase.clone())
}

pub fn restore(pin: &[u8], pepper: &[u8], net: Net, phrase: &str, restore_height: Option<u64>) -> Result<(), Error> {
  let mnemonic = keys::parse(phrase)?;
  // A legacy seed carries no date: without a height the whole chain has to be scanned.
  new_wallet(pin, pepper, net, &mnemonic, restore_height, false)
}

pub enum UnlockResult {
  Unlocked,
  WrongPin,
  /// The duress PIN was entered; the wallet file has been destroyed.
  Duress,
}

pub fn unlock(pin: &[u8], pepper: &[u8]) -> Result<UnlockResult, Error> {
  let path = vault_path()?;
  match Vault::open(path.clone(), pin, pepper)? {
    Unlocked::Opened(vault, plain) => {
      let state = State::from_bytes(&plain)?;
      drop(plain);
      start(vault, state)?;
      Ok(UnlockResult::Unlocked)
    }
    Unlocked::WrongPin => Ok(UnlockResult::WrongPin),
    Unlocked::Duress => {
      lock();
      vault::destroy(&path);
      Ok(UnlockResult::Duress)
    }
  }
}

/// Drop the session: keys, seed and state are zeroized.
pub fn lock() {
  let s = session().take();
  drop(s);
}

pub fn is_unlocked() -> bool {
  session().is_some()
}

fn with<T>(f: impl FnOnce(&mut Session) -> Result<T, Error>) -> Result<T, Error> {
  let mut guard = session();
  let s = guard.as_mut().ok_or(Error::Locked)?;
  f(s)
}

fn save(s: &Session) -> Result<(), Error> {
  s.vault.save(&s.state.to_bytes()?)
}

fn require_pin(s: &Session, pin: &[u8], pepper: &[u8]) -> Result<(), Error> {
  if s.vault.verify_pin(pin, pepper)? {
    Ok(())
  } else {
    Err(Error::WrongPin)
  }
}

pub fn status() -> Result<String, Error> {
  with(|s| {
    let (total, unlocked) = s.state.balance();
    Ok(
      json!({
        "net": format!("{:?}", s.state.net),
        "seed_kind": format!("{:?}", s.state.seed_kind),
        "address": s.keys.primary_address().to_string(),
        "balance": total.to_string(),
        "unlocked_balance": unlocked.to_string(),
        "height": s.state.next_height,
        "tip": s.state.chain_tip,
        "restore_height": s.state.restore_height,
        "node_set": s.state.node.is_some(),
        "duress_set": s.vault.has_duress_pin(),
        "subaddresses": s.state.subaddresses,
      })
      .to_string(),
    )
  })
}

pub fn set_node(cfg_json: &str) -> Result<(), Error> {
  let cfg: NodeConfig = serde_json::from_str(cfg_json).map_err(|_| Error::Input("bad node settings"))?;
  cfg.endpoint()?;
  with(|s| {
    s.state.node = Some(cfg);
    s.daemon = None;
    s.prepared = None;
    save(s)
  })
}

pub fn node() -> Result<String, Error> {
  with(|s| Ok(serde_json::to_string(&s.state.node.clone().unwrap_or_default()).unwrap_or_default()))
}

fn daemon(s: &mut Session) -> Result<Daemon, Error> {
  if let Some(d) = &s.daemon {
    return Ok(d.clone());
  }
  let cfg = s.state.node.clone().ok_or(Error::Input("set a node in Settings first"))?;
  let d = s.rt.block_on(net::connect(&cfg))?;
  s.daemon = Some(d.clone());
  Ok(d)
}

/// Check the node answers; returns its height.
pub fn test_node() -> Result<u64, Error> {
  use monero_wallet::interface::ProvidesBlockchainMeta;
  with(|s| {
    s.daemon = None;
    let d = daemon(s)?;
    Ok(s.rt.block_on(d.latest_block_number())? as u64)
  })
}

pub fn sync_step() -> Result<String, Error> {
  with(|s| {
    let d = daemon(s)?;
    let progress = s.rt.block_on(wallet::sync_step(&mut s.state, &s.keys, &d));
    // Save whatever was scanned, even if the step then failed.
    save(s)?;
    let p = progress.inspect_err(|_| s.daemon = None)?;
    Ok(serde_json::to_string(&p).unwrap_or_default())
  })
}

pub fn address(index: u32) -> Result<String, Error> {
  with(|s| Ok(s.keys.subaddress(index).to_string()))
}

/// Hand out the next unused subaddress.
pub fn new_subaddress() -> Result<String, Error> {
  with(|s| {
    if s.state.subaddresses >= MAX_SUBADDRESSES {
      return Err(Error::Input("too many subaddresses"));
    }
    s.state.subaddresses += 1;
    save(s)?;
    Ok(json!({"index": s.state.subaddresses, "address": s.keys.subaddress(s.state.subaddresses).to_string()}).to_string())
  })
}

pub fn validate_address(addr: &str) -> Result<bool, Error> {
  with(|s| Ok(wallet::parse_address(s.state.net, addr).is_ok()))
}

pub fn prepare_send(destination: &str, amount: u64, priority: i32) -> Result<String, Error> {
  with(|s| {
    s.prepared = None;
    let d = daemon(s)?;
    let p = s.rt.block_on(wallet::prepare_send(&s.state, &s.keys, &d, destination, amount, wallet::priority(priority)))?;
    let summary = serde_json::to_string(&wallet::summary(&p)).unwrap_or_default();
    s.prepared = Some(p);
    Ok(summary)
  })
}

/// Broadcast the prepared transaction. Asks for the PIN again so an unlocked phone in someone
/// else's hands can't send.
pub fn confirm_send(pin: &[u8], pepper: &[u8]) -> Result<String, Error> {
  with(|s| {
    require_pin(s, pin, pepper)?;
    let p = s.prepared.take().ok_or(Error::Input("nothing to send"))?;
    let d = daemon(s)?;
    let txid = s.rt.block_on(wallet::publish(&mut s.state, &d, p))?;
    save(s)?;
    Ok(txid)
  })
}

pub fn cancel_send() {
  let _ = with(|s| {
    s.prepared = None;
    Ok(())
  });
}

pub fn history() -> Result<String, Error> {
  with(|s| {
    let items: Vec<serde_json::Value> = wallet::history(&s.state)
      .into_iter()
      .map(|h| {
        json!({
          "txid": h.txid, "height": h.height, "confirmations": h.confirmations,
          "timestamp": h.timestamp, "net": h.net.to_string(), "fee": h.fee.map(|f| f.to_string()),
          "destination": h.destination,
        })
      })
      .collect();
    Ok(serde_json::Value::Array(items).to_string())
  })
}

pub fn reveal_seed(pin: &[u8], pepper: &[u8]) -> Result<Zeroizing<String>, Error> {
  with(|s| {
    require_pin(s, pin, pepper)?;
    Ok(s.state.mnemonic.clone())
  })
}

pub fn verify_pin(pin: &[u8], pepper: &[u8]) -> Result<bool, Error> {
  with(|s| s.vault.verify_pin(pin, pepper))
}

pub fn change_pin(old: &[u8], new: &[u8], pepper: &[u8]) -> Result<(), Error> {
  check_new_pin(new)?;
  with(|s| {
    require_pin(s, old, pepper)?;
    s.vault.change_pin(new, pepper)?;
    // The duress PIN must not end up equal to the new PIN; simplest is to clear it.
    s.vault.set_duress_pin(None, pepper)?;
    save(s)
  })
}

pub fn set_duress_pin(pin: &[u8], duress: Option<&[u8]>, pepper: &[u8]) -> Result<(), Error> {
  if let Some(d) = duress {
    check_new_pin(d)?;
  }
  with(|s| {
    require_pin(s, pin, pepper)?;
    s.vault.set_duress_pin(duress, pepper)?;
    save(s)
  })
}

/// Rescan from the restore height (or a new one).
pub fn rescan(restore_height: Option<u64>) -> Result<(), Error> {
  with(|s| {
    if let Some(h) = restore_height {
      s.state.restore_height = h;
    }
    s.state.outputs.clear();
    s.state.txs.clear();
    s.state.recent_hashes.clear();
    s.state.next_height = s.state.restore_height;
    save(s)
  })
}

/// Destroy the wallet file. The caller also deletes the Keystore key.
pub fn wipe() -> Result<(), Error> {
  lock();
  vault::destroy(&vault_path()?);
  Ok(())
}

/// QR code modules for `text` (row-major), and the side length.
pub fn qr(text: &str) -> Result<(usize, Vec<bool>), Error> {
  let code = qrcode::QrCode::with_error_correction_level(text.as_bytes(), qrcode::EcLevel::M)
    .map_err(|_| Error::Input("text too long for a QR code"))?;
  let width = code.width();
  let modules = code.to_colors().into_iter().map(|c| c == qrcode::Color::Dark).collect();
  Ok((width, modules))
}

#[cfg(test)]
mod tests {
  use super::*;

  // One test, because the session is process-global.
  #[test]
  fn lifecycle() {
    let dir = tempfile::tempdir().unwrap();
    init(dir.path().to_path_buf()).unwrap();
    set_kdf_for_tests(KdfParams::TEST);
    let pepper = [3u8; 32];

    assert!(create(b"123456", &pepper, Net::Stagenet, SeedKind::Polyseed).is_err(), "weak PIN");
    let seed = create(b"271828", &pepper, Net::Stagenet, SeedKind::Polyseed).unwrap();
    assert_eq!(seed.split(' ').count(), 16);
    assert!(exists());
    let first: serde_json::Value = serde_json::from_str(&status().unwrap()).unwrap();
    let address = first["address"].as_str().unwrap().to_owned();
    assert!(address.starts_with('5'), "stagenet address");
    assert!(validate_address(&address).unwrap());
    assert!(!validate_address("44AFFq5kSiGBoZ4NMDwYtN18obc8AemS33DBLWs3H7otXft3XjrpDtQGv7SqSsaBYBb98uNbr2VBBEt7f2wfn3RVGQBEP3A").unwrap(), "mainnet address on stagenet");
    let sub: serde_json::Value = serde_json::from_str(&new_subaddress().unwrap()).unwrap();
    assert_eq!(sub["index"], 1);

    assert!(matches!(reveal_seed(b"000000", &pepper), Err(Error::WrongPin)));
    assert_eq!(*reveal_seed(b"271828", &pepper).unwrap(), *seed);

    assert!(set_node(r#"{"url":"http://node.example.com:18081"}"#).is_err());
    set_node(r#"{"url":"https://node.example.com:18089","tor_proxy":"127.0.0.1:9050"}"#).unwrap();

    set_duress_pin(b"271828", Some(b"909192"), &pepper).unwrap();
    lock();
    assert!(!is_unlocked());
    assert!(status().is_err());

    assert!(matches!(unlock(b"314159", &pepper).unwrap(), UnlockResult::WrongPin));
    assert!(matches!(unlock(b"271828", &pepper).unwrap(), UnlockResult::Unlocked));
    let again: serde_json::Value = serde_json::from_str(&status().unwrap()).unwrap();
    assert_eq!(again["address"].as_str().unwrap(), address);
    assert_eq!(again["subaddresses"], 1);
    assert!(node().unwrap().contains("node.example.com"));

    change_pin(b"271828", b"14142135", &pepper).unwrap();
    lock();
    assert!(matches!(unlock(b"271828", &pepper).unwrap(), UnlockResult::WrongPin));
    assert!(matches!(unlock(b"14142135", &pepper).unwrap(), UnlockResult::Unlocked));
    set_duress_pin(b"14142135", Some(b"909192"), &pepper).unwrap();
    lock();
    assert!(matches!(unlock(b"909192", &pepper).unwrap(), UnlockResult::Duress));
    assert!(!exists(), "duress PIN destroys the wallet");

    restore(
      b"27182818",
      &pepper,
      Net::Mainnet,
      "washing thirsty occur lectures tuesday fainted toxic adapt abnormal memoir nylon mostly \
       building shrugged online ember northern ruby woes dauntless boil family illness inroads northern",
      Some(3_000_000),
    )
    .unwrap();
    let st: serde_json::Value = serde_json::from_str(&status().unwrap()).unwrap();
    assert_eq!(st["restore_height"], 3_000_000);
    wipe().unwrap();
    assert!(!exists());
    assert!(!is_unlocked());

    let (w, m) = qr("monero:4abc").unwrap();
    assert_eq!(m.len(), w * w);
  }
}
