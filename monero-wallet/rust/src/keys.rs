//! Seeds and the keys derived from them.

use rand_core::OsRng;
use serde::{Deserialize, Serialize};
use zeroize::Zeroizing;

use monero_wallet::{
  address::{MoneroAddress, Network, SubaddressIndex},
  ed25519::{CompressedPoint, Point, Scalar},
  ViewPair,
};

use crate::Error;

#[derive(Clone, Copy, PartialEq, Eq, Debug, Serialize, Deserialize)]
pub enum SeedKind {
  /// 16 words, carries the wallet's birthday (restore height).
  Polyseed,
  /// The classic 25-word Monero seed.
  Legacy,
}

#[derive(Clone, Copy, PartialEq, Eq, Debug, Serialize, Deserialize)]
pub enum Net {
  Mainnet,
  Stagenet,
  Testnet,
}

impl Net {
  pub fn from_code(code: i32) -> Result<Net, Error> {
    match code {
      0 => Ok(Net::Mainnet),
      1 => Ok(Net::Stagenet),
      2 => Ok(Net::Testnet),
      _ => Err(Error::Input("unknown network")),
    }
  }

  pub fn network(self) -> Network {
    match self {
      Net::Mainnet => Network::Mainnet,
      Net::Stagenet => Network::Stagenet,
      Net::Testnet => Network::Testnet,
    }
  }

  /// Rough block height at a unix time, from a hard fork anchor and 2-minute blocks.
  pub fn height_at(self, unix: u64) -> u64 {
    let (anchor_height, anchor_time) = match self {
      // v16 (2022-08-13)
      Net::Mainnet => (2_688_888u64, 1_660_398_000u64),
      Net::Stagenet => (1_151_000, 1_657_000_000),
      Net::Testnet => (1_983_520, 1_657_000_000),
    };
    if unix >= anchor_time {
      anchor_height + (unix - anchor_time) / 120
    } else {
      anchor_height.saturating_sub((anchor_time - unix) / 120)
    }
  }
}

/// A wallet's secret keys. Wiped from memory when dropped.
pub struct Keys {
  pub spend: Zeroizing<Scalar>,
  pub view: Zeroizing<Scalar>,
  pub pair: ViewPair,
  pub net: Net,
}

fn scalar_reduce32(bytes: &[u8; 32]) -> Zeroizing<Scalar> {
  Zeroizing::new(Scalar::from(curve25519_dalek_scalar(bytes)))
}

fn curve25519_dalek_scalar(bytes: &[u8; 32]) -> curve25519_dalek::Scalar {
  curve25519_dalek::Scalar::from_bytes_mod_order(*bytes)
}

impl Keys {
  fn from_spend(spend: Zeroizing<Scalar>, net: Net) -> Result<Keys, Error> {
    // Monero's view key is Hs(spend key).
    let spend_bytes = Zeroizing::new(<[u8; 32]>::from(*spend));
    let view = Zeroizing::new(Scalar::hash(spend_bytes.as_ref()));
    let spend_point = Point::from(
      curve25519_dalek::constants::ED25519_BASEPOINT_TABLE *
        &(*spend).into(),
    );
    let pair =
      ViewPair::new(spend_point, view.clone()).map_err(|_| Error::Input("invalid keys"))?;
    Ok(Keys { spend, view, pair, net })
  }

  pub fn primary_address(&self) -> MoneroAddress {
    self.pair.legacy_address(self.net.network())
  }

  pub fn subaddress(&self, index: u32) -> MoneroAddress {
    match SubaddressIndex::new(0, index) {
      Some(i) if index != 0 => self.pair.subaddress(self.net.network(), i),
      _ => self.primary_address(),
    }
  }

  /// The key image of an output with this one-time key offset and key.
  pub fn key_image(&self, key_offset: Scalar, output_key: Point) -> CompressedPoint {
    let x = Zeroizing::new((*self.spend).into() + key_offset.into());
    let hp = Point::biased_hash(output_key.compress().to_bytes()).into();
    Point::from(*x * hp).compress()
  }
}

/// A freshly generated or parsed seed.
pub struct Mnemonic {
  pub kind: SeedKind,
  pub phrase: Zeroizing<String>,
  /// Unix time the seed was made, when it records one (Polyseed).
  pub birthday: Option<u64>,
}

/// Normalize user-typed words: lowercase, single spaces.
fn normalize(phrase: &str) -> Zeroizing<String> {
  let mut out = Zeroizing::new(String::with_capacity(phrase.len()));
  for word in phrase.split_whitespace() {
    if !out.is_empty() {
      out.push(' ');
    }
    for c in word.chars() {
      out.extend(c.to_lowercase());
    }
  }
  out
}

pub fn generate(kind: SeedKind) -> Mnemonic {
  match kind {
    SeedKind::Polyseed => {
      let seed = polyseed::Polyseed::new(&mut OsRng, polyseed::Language::English);
      Mnemonic { kind, phrase: seed.to_string(), birthday: Some(seed.birthday()) }
    }
    SeedKind::Legacy => {
      let seed = monero_seed::Seed::new(&mut OsRng, monero_seed::Language::English);
      Mnemonic { kind, phrase: seed.to_string(), birthday: None }
    }
  }
}

/// Parse a mnemonic (16-word Polyseed or 25-word Monero seed, English).
pub fn parse(phrase: &str) -> Result<Mnemonic, Error> {
  let phrase = normalize(phrase);
  let words = phrase.split(' ').count();
  match words {
    16 => {
      let seed = polyseed::Polyseed::from_string(polyseed::Language::English, phrase.clone())
        .map_err(|_| Error::Input("invalid Polyseed (check the words)"))?;
      Ok(Mnemonic { kind: SeedKind::Polyseed, phrase: seed.to_string(), birthday: Some(seed.birthday()) })
    }
    25 => {
      let seed = monero_seed::Seed::from_string(monero_seed::Language::English, phrase.clone())
        .map_err(|_| Error::Input("invalid seed (check the words and checksum)"))?;
      Ok(Mnemonic { kind: SeedKind::Legacy, phrase: seed.to_string(), birthday: None })
    }
    _ => Err(Error::Input("a seed has 16 (Polyseed) or 25 words")),
  }
}

pub fn keys(mnemonic: &Mnemonic, net: Net) -> Result<Keys, Error> {
  let spend = match mnemonic.kind {
    SeedKind::Polyseed => {
      let seed =
        polyseed::Polyseed::from_string(polyseed::Language::English, mnemonic.phrase.clone())
          .map_err(|_| Error::Corrupt("stored seed is invalid"))?;
      // Polyseed's key, reduced mod l (sc_reduce32), is the spend key.
      scalar_reduce32(&seed.key())
    }
    SeedKind::Legacy => {
      let seed =
        monero_seed::Seed::from_string(monero_seed::Language::English, mnemonic.phrase.clone())
          .map_err(|_| Error::Corrupt("stored seed is invalid"))?;
      scalar_reduce32(&seed.entropy())
    }
  };
  Keys::from_spend(spend, net)
}

#[cfg(test)]
mod tests {
  use super::*;

  // Test vector from monero-seed's own tests (English).
  const LEGACY: &str = "washing thirsty occur lectures tuesday fainted toxic adapt \
    abnormal memoir nylon mostly building shrugged online ember northern ruby woes \
    dauntless boil family illness inroads northern";
  const LEGACY_SPEND: &str = "c0af65c0dd837e666b9d0dfed62745f4df35aed7ea619b2798a709f0fe545403";
  const LEGACY_VIEW: &str = "513ba91c538a5a9069e0094de90e927c0cd147fa10428ce3ac1afd49f63e3b01";

  #[test]
  fn legacy_seed_derives_known_keys() {
    let m = parse(LEGACY).unwrap();
    assert_eq!(m.kind, SeedKind::Legacy);
    let k = keys(&m, Net::Mainnet).unwrap();
    assert_eq!(hex::encode(<[u8; 32]>::from(*k.spend)), LEGACY_SPEND);
    assert_eq!(hex::encode(<[u8; 32]>::from(*k.view)), LEGACY_VIEW);
  }

  #[test]
  fn generated_seeds_roundtrip() {
    for kind in [SeedKind::Polyseed, SeedKind::Legacy] {
      let m = generate(kind);
      let k1 = keys(&m, Net::Mainnet).unwrap();
      let upper = m.phrase.to_uppercase().replace(' ', "   ");
      let m2 = parse(&upper).unwrap();
      assert_eq!(m2.kind, kind);
      let k2 = keys(&m2, Net::Mainnet).unwrap();
      assert_eq!(k1.primary_address().to_string(), k2.primary_address().to_string());
      assert!(k1.primary_address().to_string().starts_with('4'));
      assert!(k1.subaddress(1).to_string().starts_with('8'));
    }
  }

  #[test]
  fn bad_checksum_rejected() {
    let mut words: Vec<&str> = LEGACY.split_whitespace().collect();
    words.swap(0, 1);
    assert!(parse(&words.join(" ")).is_err());
    assert!(parse("abandon abandon").is_err());
  }
}

#[cfg(test)]
mod polyseed_compat {
  use super::*;

  /// Cross-checked against the reference C library (tevador/polyseed `polyseed_keygen`, coin 0).
  #[test]
  fn polyseed_key_matches_reference() {
    let phrase = "raven tail swear infant grief assist regular lamp duck valid someone little \
                  harsh puppy airport language";
    let seed = polyseed::Polyseed::from_string(polyseed::Language::English, Zeroizing::new(phrase.into())).unwrap();
    assert_eq!(
      hex::encode(seed.key().as_ref()),
      "21268a76048a3b25a4a9ac179d86b12fab5800b8d858da9facf4b0a778dc2840"
    );
    assert_eq!(parse(phrase).unwrap().kind, SeedKind::Polyseed);
  }
}
