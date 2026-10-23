//! Rust core of XMR Vault, a hardened Monero wallet for Android.
//!
//! Everything secret lives here: seeds and keys, the encrypted vault, block scanning, signing and
//! the node connection. The Android side only draws the UI and holds the hardware-backed key that
//! unwraps the vault's pepper.

#![deny(unsafe_code)]

pub mod api;
pub mod keys;
pub mod net;
pub mod vault;
pub mod wallet;

#[allow(unsafe_code)]
mod hardening;
#[allow(unsafe_code)]
mod jni;

use monero_daemon_rpc::prelude::InterfaceError;

#[derive(Debug, thiserror::Error)]
pub enum Error {
  #[error("{0}")]
  Input(&'static str),
  #[error("no wallet")]
  NoWallet,
  #[error("wallet is locked")]
  Locked,
  #[error("wrong PIN")]
  WrongPin,
  #[error("wallet file damaged: {0}")]
  Corrupt(&'static str),
  #[error("storage: {0}")]
  Io(&'static str),
  #[error("node: {0}")]
  Node(String),
  #[error("internal error: {0}")]
  Internal(&'static str),
}

impl From<InterfaceError> for Error {
  fn from(e: InterfaceError) -> Self {
    Error::Node(e.to_string())
  }
}
