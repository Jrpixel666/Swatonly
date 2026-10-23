//! End-to-end test against a local regtest `monerod`:
//!
//! ```sh
//! monerod --regtest --offline --fixed-difficulty 1 --rpc-bind-port 18081 --non-interactive &
//! MONEROD_URL=http://127.0.0.1:18081 cargo test --test regtest -- --nocapture
//! ```
//!
//! Skipped when `MONEROD_URL` is not set.

use serde_json::Value;
use xmrvault::{
  api,
  keys::{self, Net, SeedKind},
  net::{self, NodeConfig},
  vault::KdfParams,
};

const XMR: u64 = 1_000_000_000_000;
const PEPPER: [u8; 32] = [9; 32];

fn status() -> Value {
  serde_json::from_str(&api::status().unwrap()).unwrap()
}

fn amount(v: &Value, key: &str) -> u64 {
  v[key].as_str().unwrap().parse().unwrap()
}

fn sync() {
  loop {
    let p: Value = serde_json::from_str(&api::sync_step().unwrap()).unwrap();
    if p["done"].as_bool().unwrap() {
      return;
    }
  }
}

fn mine(url: &str, address: &str, blocks: usize) {
  let rt = tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap();
  rt.block_on(async {
    let daemon = net::connect(&NodeConfig { url: url.into(), ..Default::default() }).await.unwrap();
    let address = monero_wallet::address::MoneroAddress::from_str(
      monero_wallet::address::Network::Mainnet,
      address,
    )
    .unwrap();
    daemon.generate_blocks(&address, blocks).await.unwrap();
  });
}

#[test]
fn mine_sync_send_receive() {
  let Ok(url) = std::env::var("MONEROD_URL") else {
    eprintln!("MONEROD_URL not set; skipping");
    return;
  };
  let dir = tempfile::tempdir().unwrap();
  api::init(dir.path().to_path_buf()).unwrap();
  api::set_kdf_for_tests(KdfParams::TEST);
  let node = serde_json::json!({ "url": url }).to_string();

  // Wallet B only needs an address for now.
  let b_seed = keys::generate(SeedKind::Polyseed);
  let b_keys = keys::keys(&b_seed, Net::Mainnet).unwrap();
  let b_address = b_keys.primary_address().to_string();
  // B never hands this out from its own session: found through the subaddress lookahead.
  let b_sub = b_keys.subaddress(3).to_string();

  // Wallet A: restore, mine coinbase to it (enough blocks for decoys and the coinbase lock).
  let a_seed = keys::generate(SeedKind::Legacy);
  api::restore(b"27182818", &PEPPER, Net::Mainnet, &a_seed.phrase, Some(0)).unwrap();
  api::set_node(&node).unwrap();
  let height = api::test_node().unwrap();
  let a_address = status()["address"].as_str().unwrap().to_owned();
  mine(&url, &a_address, 120);
  // Bury A's coinbase under its 60-block lock.
  mine(&url, &b_address, 70);
  // Start A's scan where we began mining: fast, and exercises the restore height.
  api::rescan(Some(height)).unwrap();
  sync();
  let s = status();
  let before = amount(&s, "balance");
  eprintln!("A after mining: {s}");
  assert!(before > 0, "coinbase found");
  assert!(amount(&s, "unlocked_balance") > 0, "coinbase unlocked");

  // Send 1.5 XMR to B's subaddress. The PIN is required again to broadcast.
  let summary: Value =
    serde_json::from_str(&api::prepare_send(&b_sub, 3 * XMR / 2, 1).unwrap()).unwrap();
  eprintln!("prepared: {summary}");
  let fee: u64 = summary["fee"].as_u64().unwrap();
  assert!(fee > 0 && fee < XMR / 100);
  assert!(api::confirm_send(b"00000000", &PEPPER).is_err(), "wrong PIN can't send");
  let summary: Value =
    serde_json::from_str(&api::prepare_send(&b_sub, 3 * XMR / 2, 1).unwrap()).unwrap();
  let fee: u64 = summary["fee"].as_u64().unwrap();
  let txid = api::confirm_send(b"27182818", &PEPPER).unwrap();
  eprintln!("sent {txid}");

  mine(&url, &b_address, 12);
  sync();
  let s = status();
  eprintln!("A after send: {s}");
  // Coinbase from blocks mined before the send keeps unlocking, but A got no new coinbase:
  // the total must have dropped by exactly amount + fee.
  assert_eq!(amount(&s, "balance"), before - 3 * XMR / 2 - fee);
  let history: Value = serde_json::from_str(&api::history().unwrap()).unwrap();
  eprintln!("history: {history}");
  let sent = history.as_array().unwrap().iter().find(|t| t["txid"] == txid.as_str()).unwrap();
  assert!(sent["height"].as_u64().is_some(), "send confirmed");
  assert_eq!(sent["net"].as_str().unwrap(), format!("-{}", 3 * XMR / 2 + fee));

  // Reorg: drop the last 13 blocks (the send's block included) and build a longer chain.
  let popped: Value = reqwest_post(&url, "pop_blocks", r#"{"nblocks":13}"#);
  assert_eq!(popped["status"], "OK");
  let flushed = reqwest_post(&url, "json_rpc", r#"{"jsonrpc":"2.0","id":0,"method":"flush_txpool"}"#);
  assert_eq!(flushed["result"]["status"], "OK");
  mine(&url, &b_address, 15);
  sync();
  let s = status();
  eprintln!("A after reorg: {s}");
  // The send was flushed: its inputs stay reserved as pending (not double-spendable) and its
  // change output is gone with the orphaned block.
  assert!(amount(&s, "balance") < before - 3 * XMR / 2 - fee);
  let history: Value = serde_json::from_str(&api::history().unwrap()).unwrap();
  let sent = history.as_array().unwrap().iter().find(|t| t["txid"] == txid.as_str()).unwrap();
  assert!(sent["height"].is_null(), "send is unconfirmed after the reorg");

  // Send again: the reserved inputs of the flushed send must not be picked.
  api::prepare_send(&b_sub, 3 * XMR / 2, 1).unwrap();
  let txid = api::confirm_send(b"27182818", &PEPPER).unwrap();
  mine(&url, &a_address, 2);

  // Wallet B sees the payment.
  api::wipe().unwrap();
  api::restore(b"31415926", &PEPPER, Net::Mainnet, &b_seed.phrase, Some(height)).unwrap();
  api::set_node(&node).unwrap();
  sync();
  let history: Value = serde_json::from_str(&api::history().unwrap()).unwrap();
  let got = history.as_array().unwrap().iter().find(|t| t["txid"] == txid.as_str()).unwrap();
  assert_eq!(got["net"].as_str().unwrap(), (3 * XMR / 2).to_string());
  api::wipe().unwrap();
}

fn reqwest_post(url: &str, route: &str, body: &str) -> Value {
  let out = std::process::Command::new("curl")
    .args(["-s", &format!("{url}/{route}"), "-d", body])
    .output()
    .unwrap();
  serde_json::from_slice(&out.stdout).unwrap_or(Value::Null)
}
