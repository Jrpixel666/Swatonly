//! Wallet state, block scanning and transaction building.
//!
//! Scanning happens on the phone: the node only ever sees requests for whole ranges of blocks,
//! never the view key, the addresses, or which outputs are ours. Spends are detected by matching
//! key images (which need the spend key) against block inputs locally, instead of asking the node
//! "is this key image spent?".

use std::{
  collections::HashMap,
  time::{SystemTime, UNIX_EPOCH},
};

use rand_core::{OsRng, RngCore};
use serde::{Deserialize, Serialize};
use zeroize::{Zeroize, Zeroizing};

use monero_wallet::{
  address::MoneroAddress,
  interface::{
    FeePriority, ProvidesBlockchain, ProvidesBlockchainMeta, ProvidesFeeRates,
    ProvidesScannableBlocks, PublishTransaction,
  },
  ringct::RctType,
  send::{Change, SignableTransaction},
  transaction::{Input, Timelock, Transaction},
  OutputWithDecoys, Scanner, WalletOutput,
};

use crate::{
  keys::{Keys, Mnemonic, Net, SeedKind},
  net::{Daemon, NodeConfig},
  Error,
};

/// Ring size enforced by Monero since v15.
const RING_LEN: u8 = 16;
const LOCK_BLOCKS: u64 = 10;
const COINBASE_LOCK_BLOCKS: u64 = 60;
/// Subaddresses beyond the last one handed out that are still scanned for.
const SUBADDRESS_LOOKAHEAD: u32 = 50;
/// Block hashes kept to detect reorganizations.
const REORG_WINDOW: usize = 100;
/// Highest fee per weight unit accepted from a node, so a malicious node can't drain us via fees.
/// About 5x mainnet's top priority tier (~0.03 XMR for a typical transaction); the fee is also
/// shown for confirmation before anything is broadcast.
const MAX_FEE_PER_WEIGHT: u64 = 20_000_000;
/// A broadcast spend not seen on chain after this many blocks is treated as dropped.
const PENDING_EXPIRY_BLOCKS: u64 = 720;
const BATCH: usize = 50;

fn now() -> u64 {
  SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0)
}

#[derive(Clone, Serialize, Deserialize, Zeroize)]
pub struct OwnedOutput {
  /// `WalletOutput::serialize`, hex.
  pub raw: String,
  pub txid: String,
  pub amount: u64,
  pub height: u64,
  pub coinbase: bool,
  pub subaddress: u32,
  pub key_image: String,
  pub spent_height: Option<u64>,
  pub spent_txid: Option<String>,
  /// Broadcast by us, not yet seen in a block: (txid, chain height when sent).
  pub pending: Option<(String, u64)>,
  /// Shares its key image with an output we already own (the "burning bug"); never spendable.
  pub burnt: bool,
}

#[derive(Clone, Serialize, Deserialize, Zeroize)]
pub struct TxRecord {
  pub txid: String,
  /// None while in the pool.
  pub height: Option<u64>,
  pub timestamp: u64,
  pub fee: Option<u64>,
  pub destination: Option<String>,
}

#[derive(Serialize, Deserialize)]
pub struct State {
  pub version: u32,
  pub net: Net,
  pub seed_kind: SeedKind,
  pub mnemonic: Zeroizing<String>,
  pub restore_height: u64,
  /// The next block number to scan.
  pub next_height: u64,
  /// Height of the node's tip at the last sync.
  pub chain_tip: u64,
  pub recent_hashes: Vec<(u64, String)>,
  pub outputs: Vec<OwnedOutput>,
  pub txs: Vec<TxRecord>,
  /// Subaddresses handed out (indices 1..=n of account 0).
  pub subaddresses: u32,
  pub node: Option<NodeConfig>,
  /// Created on this device (not restored): nothing can predate its creation.
  #[serde(default)]
  pub fresh: bool,
}

impl Drop for State {
  fn drop(&mut self) {
    self.outputs.zeroize();
    self.txs.zeroize();
    for (_, h) in &mut self.recent_hashes {
      h.zeroize();
    }
  }
}

impl State {
  pub fn new(net: Net, mnemonic: &Mnemonic, restore_height: Option<u64>, fresh: bool) -> State {
    let height = restore_height.unwrap_or_else(|| {
      // Polyseed birthdays are rounded; start about a month earlier to be safe.
      mnemonic.birthday.map(|b| net.height_at(b).saturating_sub(21_600)).unwrap_or(0)
    });
    State {
      version: 1,
      net,
      seed_kind: mnemonic.kind,
      mnemonic: mnemonic.phrase.clone(),
      restore_height: height,
      next_height: height,
      chain_tip: 0,
      recent_hashes: vec![],
      outputs: vec![],
      txs: vec![],
      subaddresses: 0,
      node: None,
      fresh,
    }
  }

  pub fn to_bytes(&self) -> Result<Zeroizing<Vec<u8>>, Error> {
    serde_json::to_vec(self).map(Zeroizing::new).map_err(|_| Error::Internal("serialize"))
  }

  pub fn from_bytes(bytes: &[u8]) -> Result<State, Error> {
    serde_json::from_slice(bytes).map_err(|_| Error::Corrupt("wallet contents unreadable"))
  }

  pub fn mnemonic(&self) -> Mnemonic {
    Mnemonic { kind: self.seed_kind, phrase: self.mnemonic.clone(), birthday: None }
  }

  fn spendable_at(&self, o: &OwnedOutput, raw: &WalletOutput, tip: u64) -> bool {
    if o.spent_height.is_some() || o.pending.is_some() || o.burnt {
      return false;
    }
    let lock = if o.coinbase { COINBASE_LOCK_BLOCKS } else { LOCK_BLOCKS };
    // The output can be spent in block `tip + 1` once it is `lock` blocks deep.
    if o.height + lock > tip + 1 {
      return false;
    }
    match raw.additional_timelock() {
      Timelock::None => true,
      Timelock::Block(b) => (b as u64) <= tip + 1,
      Timelock::Time(t) => t <= now(),
    }
  }

  /// (total, unlocked) balance in atomic units.
  pub fn balance(&self) -> (u64, u64) {
    let mut total = 0u64;
    let mut unlocked = 0u64;
    for o in &self.outputs {
      if o.spent_height.is_some() || o.pending.is_some() || o.burnt {
        continue;
      }
      total = total.saturating_add(o.amount);
      if let Ok(raw) = decode_output(&o.raw) {
        if self.spendable_at(o, &raw, self.chain_tip) {
          unlocked = unlocked.saturating_add(o.amount);
        }
      }
    }
    (total, unlocked)
  }

  fn rollback_to(&mut self, height: u64) {
    self.outputs.retain(|o| o.height < height);
    let ours: Vec<String> =
      self.txs.iter().filter(|t| t.destination.is_some()).map(|t| t.txid.clone()).collect();
    for o in &mut self.outputs {
      if o.spent_height.is_some_and(|h| h >= height) {
        o.spent_height = None;
        // A spend of ours that got reorged out is most likely back in the pool.
        if let Some(txid) = o.spent_txid.take() {
          if ours.contains(&txid) {
            o.pending = Some((txid, height));
          }
        }
      }
    }
    // Recompute the burnt flags from what is left.
    let mut seen: HashMap<String, usize> = HashMap::new();
    for i in 0 .. self.outputs.len() {
      self.outputs[i].burnt = false;
      if let Some(&j) = seen.get(&self.outputs[i].key_image) {
        if self.outputs[i].amount > self.outputs[j].amount {
          self.outputs[j].burnt = true;
          seen.insert(self.outputs[i].key_image.clone(), i);
        } else {
          self.outputs[i].burnt = true;
        }
      } else {
        seen.insert(self.outputs[i].key_image.clone(), i);
      }
    }
    self.txs.retain(|t| t.height.is_none_or(|h| h < height) || t.destination.is_some());
    for t in &mut self.txs {
      if t.height.is_some_and(|h| h >= height) {
        t.height = None;
      }
    }
    self.recent_hashes.retain(|(h, _)| *h < height);
    self.next_height = height.max(self.restore_height);
  }

  fn record_hash(&mut self, height: u64, hash: [u8; 32]) {
    self.recent_hashes.push((height, hex::encode(hash)));
    if self.recent_hashes.len() > REORG_WINDOW {
      let drop = self.recent_hashes.len() - REORG_WINDOW;
      self.recent_hashes.drain(.. drop);
    }
  }

  fn tx_mut(&mut self, txid: &str, height: Option<u64>, timestamp: u64) -> &mut TxRecord {
    let idx = match self.txs.iter().position(|t| t.txid == txid) {
      Some(i) => i,
      None => {
        self.txs.push(TxRecord {
          txid: txid.to_owned(),
          height,
          timestamp,
          fee: None,
          destination: None,
        });
        self.txs.len() - 1
      }
    };
    let t = &mut self.txs[idx];
    if height.is_some() {
      t.height = height;
      t.timestamp = timestamp;
    }
    t
  }

  /// Release spends we broadcast that never made it into a block.
  fn expire_pending(&mut self) {
    let tip = self.chain_tip;
    let mut dropped = vec![];
    for o in &mut self.outputs {
      if let Some((txid, sent_at)) = &o.pending {
        if sent_at + PENDING_EXPIRY_BLOCKS < tip {
          dropped.push(txid.clone());
          o.pending = None;
        }
      }
    }
    self.txs.retain(|t| !(t.height.is_none() && dropped.contains(&t.txid)));
  }

  pub fn scanner(&self, keys: &Keys) -> Scanner {
    let mut scanner = Scanner::new(keys.pair.clone());
    for i in 1 ..= self.subaddresses + SUBADDRESS_LOOKAHEAD {
      if let Some(idx) = monero_wallet::address::SubaddressIndex::new(0, i) {
        scanner.register_subaddress(idx);
      }
    }
    scanner
  }
}

fn decode_output(raw: &str) -> Result<WalletOutput, Error> {
  let bytes = Zeroizing::new(hex::decode(raw).map_err(|_| Error::Corrupt("output"))?);
  WalletOutput::read(&mut bytes.as_slice()).map_err(|_| Error::Corrupt("output"))
}

#[derive(Serialize)]
pub struct SyncProgress {
  pub height: u64,
  pub tip: u64,
  pub done: bool,
  pub new_outputs: usize,
}

/// Scan up to one batch of blocks. Call repeatedly until `done`, saving in between.
pub async fn sync_step(state: &mut State, keys: &Keys, daemon: &Daemon) -> Result<SyncProgress, Error> {
  let tip = daemon.latest_block_number().await? as u64;
  state.chain_tip = tip;

  // Reorg check: the block before the next one must still be the one we scanned.
  if let Some((h, hash)) = state.recent_hashes.last().cloned() {
    let live = hex::encode(daemon.block_hash(h as usize).await?);
    if live != hash {
      let mut fork = None;
      for (h, hash) in state.recent_hashes.iter().rev() {
        if hex::encode(daemon.block_hash(*h as usize).await?) == *hash {
          fork = Some(*h + 1);
          break;
        }
      }
      // Deeper than our window: rescan from the start.
      state.rollback_to(fork.unwrap_or(state.restore_height));
    }
  }
  state.expire_pending();

  // A date-based restore height can overshoot the real chain (block times drift). Before the
  // first scan, never start past the tip: right at it for a wallet made here, a month back for a
  // restored one.
  if state.recent_hashes.is_empty() && state.outputs.is_empty() && state.next_height > tip {
    let start = if state.fresh { tip.saturating_sub(10) } else { tip.saturating_sub(21_600) };
    state.restore_height = start.min(state.restore_height);
    state.next_height = state.restore_height;
  }

  if state.next_height > tip {
    return Ok(SyncProgress { height: state.next_height, tip, done: true, new_outputs: 0 });
  }

  let start = state.next_height;
  let end = (start + BATCH as u64 - 1).min(tip);
  let blocks = daemon.contiguous_scannable_blocks(start as usize ..= end as usize).await?;
  if blocks.len() as u64 != end - start + 1 {
    return Err(Error::Node("node returned the wrong number of blocks".into()));
  }

  // Key image -> index into state.outputs
  let mut key_images: HashMap<String, usize> =
    state.outputs.iter().enumerate().map(|(i, o)| (o.key_image.clone(), i)).collect();
  let mut scanner = state.scanner(keys);
  let mut new_outputs = 0;

  for (offset, block) in blocks.into_iter().enumerate() {
    let height = start + offset as u64;
    if block.block.number() as u64 != height {
      return Err(Error::Node("node returned a block with the wrong height".into()));
    }
    if let Some((h, hash)) = state.recent_hashes.last() {
      if *h + 1 == height && hex::encode(block.block.header.previous) != *hash {
        return Err(Error::Node("chain changed during sync; retry".into()));
      }
    }
    let timestamp = block.block.header.timestamp;
    let block_hash = block.block.hash();

    // Spends: our key images showing up as inputs.
    for (tx, txid) in block.transactions.iter().zip(block.block.transactions.iter()) {
      let txid = hex::encode(txid);
      for input in &tx.prefix().inputs {
        if let Input::ToKey { key_image, .. } = input {
          if let Some(&i) = key_images.get(&hex::encode(key_image.to_bytes())) {
            let o = &mut state.outputs[i];
            if o.burnt {
              continue;
            }
            o.spent_height = Some(height);
            o.spent_txid = Some(txid.clone());
            o.pending = None;
            state.tx_mut(&txid, Some(height), timestamp);
          }
        }
      }
    }

    // Receives.
    let miner_txid = block.block.miner_transaction().hash();
    let found = scanner.scan(block).map_err(|e| Error::Node(format!("scan: {e}")))?;
    for out in found.ignore_additional_timelock() {
      let txid_bytes = out.transaction();
      let txid = hex::encode(txid_bytes);
      let key_image = hex::encode(keys.key_image(out.key_offset(), out.key()).to_bytes());
      let amount = out.commitment().amount;
      let subaddress = out.subaddress().map(|s| s.address()).unwrap_or(0);
      let mut burnt = false;
      if let Some(&j) = key_images.get(&key_image) {
        // Same one-time key twice: only one can ever be spent. Keep the bigger one.
        if amount > state.outputs[j].amount && state.outputs[j].spent_height.is_none() {
          state.outputs[j].burnt = true;
        } else {
          burnt = true;
        }
      }
      state.outputs.push(OwnedOutput {
        raw: hex::encode(Zeroizing::new(out.serialize()).as_slice()),
        txid: txid.clone(),
        amount,
        height,
        coinbase: txid_bytes == miner_txid,
        subaddress,
        key_image: key_image.clone(),
        spent_height: None,
        spent_txid: None,
        pending: None,
        burnt,
      });
      if !burnt {
        key_images.insert(key_image, state.outputs.len() - 1);
        state.tx_mut(&txid, Some(height), timestamp);
        new_outputs += 1;
      }
      if subaddress > state.subaddresses {
        state.subaddresses = subaddress;
        scanner = state.scanner(keys);
      }
    }

    state.record_hash(height, block_hash);
    state.next_height = height + 1;
  }

  Ok(SyncProgress { height: state.next_height, tip, done: state.next_height > tip, new_outputs })
}

/// A signed transaction waiting for the user's final confirmation.
pub struct Prepared {
  pub tx: Transaction,
  pub txid: String,
  pub destination: String,
  pub amount: u64,
  pub fee: u64,
  pub inputs: Vec<String>,
  pub chain_tip: u64,
}

#[derive(Serialize)]
pub struct PreparedSummary {
  pub destination: String,
  pub amount: u64,
  pub fee: u64,
  pub inputs: usize,
  pub subaddress: bool,
}

pub fn priority(code: i32) -> FeePriority {
  match code {
    0 => FeePriority::Unimportant,
    2 => FeePriority::Elevated,
    3 => FeePriority::Priority,
    _ => FeePriority::Normal,
  }
}

pub fn parse_address(net: Net, s: &str) -> Result<MoneroAddress, Error> {
  MoneroAddress::from_str(net.network(), s.trim())
    .map_err(|_| Error::Input("not a valid address for this network"))
}

/// Pick inputs, select decoys, build and sign. Nothing is broadcast.
pub async fn prepare_send(
  state: &State,
  keys: &Keys,
  daemon: &Daemon,
  destination: &str,
  amount: u64,
  fee_priority: FeePriority,
) -> Result<Prepared, Error> {
  let address = parse_address(state.net, destination)?;
  if amount == 0 {
    return Err(Error::Input("amount must be more than zero"));
  }
  let tip = daemon.latest_block_number().await? as u64;
  if state.next_height <= tip.saturating_sub(1) {
    return Err(Error::Input("sync the wallet before sending"));
  }
  let fee_rate = daemon
    .fee_rate(fee_priority, MAX_FEE_PER_WEIGHT)
    .await
    .map_err(|e| Error::Node(format!("fee: {e}")))?;

  let mut candidates: Vec<(usize, WalletOutput)> = vec![];
  for (i, o) in state.outputs.iter().enumerate() {
    let raw = decode_output(&o.raw)?;
    if state.spendable_at(o, &raw, tip) {
      candidates.push((i, raw));
    }
  }
  // Largest first keeps the input count (and the fingerprint of many inputs) low.
  candidates.sort_by(|a, b| state.outputs[b.0].amount.cmp(&state.outputs[a.0].amount));
  let available: u64 = candidates.iter().map(|(i, _)| state.outputs[*i].amount).sum();
  if available < amount {
    return Err(Error::Input("not enough unlocked funds"));
  }

  // Enough inputs for the amount plus a generous fee guess; add more if the real fee needs it.
  let est_fee = |n: u64| fee_rate.per_weight().saturating_mul(2_000 + 1_500 * n);
  let mut n = 0;
  let mut sum = 0u64;
  while n < candidates.len() && sum < amount.saturating_add(est_fee(n as u64 + 1)) {
    sum += state.outputs[candidates[n].0].amount;
    n += 1;
  }
  n = n.max(1);

  loop {
    let mut inputs = vec![];
    for (_, out) in &candidates[.. n] {
      inputs.push(
        OutputWithDecoys::new(&mut OsRng, daemon, RING_LEN, tip as usize, out.clone())
          .await
          .map_err(|e| Error::Node(format!("decoys: {e}")))?,
      );
    }
    // A fresh outgoing view key per transaction, never reused.
    let mut ovk = Zeroizing::new([0u8; 32]);
    OsRng.fill_bytes(ovk.as_mut());
    let built = SignableTransaction::new(
      RctType::ClsagBulletproofPlus,
      ovk,
      inputs,
      vec![(address, amount)],
      Change::new(keys.pair.clone(), None),
      vec![],
      fee_rate,
    );
    match built {
      Ok(signable) => {
        let fee = signable.necessary_fee();
        let tx = signable
          .sign(&mut OsRng, &keys.spend)
          .map_err(|e| Error::Internal(if matches!(e, monero_wallet::send::SendError::WrongPrivateKey) { "wrong key" } else { "signing failed" }))?;
        let txid = hex::encode(tx.hash());
        return Ok(Prepared {
          tx,
          txid,
          destination: address.to_string(),
          amount,
          fee,
          inputs: candidates[.. n].iter().map(|(i, _)| state.outputs[*i].key_image.clone()).collect(),
          chain_tip: tip,
        });
      }
      Err(monero_wallet::send::SendError::NotEnoughFunds { .. }) if n < candidates.len() => n += 1,
      Err(monero_wallet::send::SendError::NotEnoughFunds { .. }) => {
        return Err(Error::Input("not enough unlocked funds to cover the fee"))
      }
      Err(e) => return Err(Error::Node(format!("can't build transaction: {e}"))),
    }
  }
}

pub fn summary(p: &Prepared) -> PreparedSummary {
  PreparedSummary {
    destination: p.destination.clone(),
    amount: p.amount,
    fee: p.fee,
    inputs: p.inputs.len(),
    subaddress: p.destination.starts_with('8') || p.destination.starts_with('7') || p.destination.starts_with('B'),
  }
}

/// Broadcast and record a prepared transaction.
pub async fn publish(state: &mut State, daemon: &Daemon, p: Prepared) -> Result<String, Error> {
  daemon.publish_transaction(&p.tx).await.map_err(|e| Error::Node(format!("{e}")))?;
  for o in &mut state.outputs {
    if p.inputs.contains(&o.key_image) {
      o.pending = Some((p.txid.clone(), p.chain_tip));
    }
  }
  let t = state.tx_mut(&p.txid, None, now());
  t.fee = Some(p.fee);
  t.destination = Some(p.destination.clone());
  Ok(p.txid)
}

#[derive(Serialize)]
pub struct HistoryItem<'a> {
  pub txid: &'a str,
  pub height: Option<u64>,
  pub confirmations: u64,
  pub timestamp: u64,
  /// Signed net change for the wallet (received - spent).
  pub net: i128,
  pub fee: Option<u64>,
  pub destination: Option<&'a str>,
}

/// Amounts per transaction are derived from the outputs, so they can't drift from the balance.
pub fn history(state: &State) -> Vec<HistoryItem<'_>> {
  let mut net: HashMap<&str, i128> = HashMap::new();
  for o in state.outputs.iter().filter(|o| !o.burnt) {
    *net.entry(&o.txid).or_default() += i128::from(o.amount);
    let spender = o.spent_txid.as_deref().or(o.pending.as_ref().map(|(t, _)| t.as_str()));
    if let Some(t) = spender {
      *net.entry(t).or_default() -= i128::from(o.amount);
    }
  }
  let mut items: Vec<HistoryItem<'_>> = state
    .txs
    .iter()
    .map(|t| HistoryItem {
      txid: &t.txid,
      height: t.height,
      confirmations: t.height.map(|h| (state.chain_tip + 1).saturating_sub(h)).unwrap_or(0),
      timestamp: t.timestamp,
      net: net.get(t.txid.as_str()).copied().unwrap_or(0),
      fee: t.fee,
      destination: t.destination.as_deref(),
    })
    .collect();
  items.sort_by(|a, b| {
    b.height.unwrap_or(u64::MAX).cmp(&a.height.unwrap_or(u64::MAX)).then(b.timestamp.cmp(&a.timestamp))
  });
  items
}

#[cfg(test)]
mod tests {
  use super::*;
  use crate::keys;

  fn out(ki: &str, amount: u64, height: u64) -> OwnedOutput {
    OwnedOutput {
      raw: String::new(),
      txid: format!("tx{height}"),
      amount,
      height,
      coinbase: false,
      subaddress: 0,
      key_image: ki.into(),
      spent_height: None,
      spent_txid: None,
      pending: None,
      burnt: false,
    }
  }

  #[test]
  fn rollback_restores_state() {
    let m = keys::generate(SeedKind::Polyseed);
    let mut s = State::new(Net::Stagenet, &m, Some(100), false);
    s.outputs.push(out("a", 5, 110));
    s.outputs.push(out("b", 7, 120));
    s.outputs[0].spent_height = Some(125);
    s.txs.push(TxRecord { txid: "t".into(), height: Some(121), timestamp: 0, fee: None, destination: None });
    for h in 100 .. 130 {
      s.record_hash(h, [h as u8; 32]);
    }
    s.next_height = 130;
    s.rollback_to(115);
    assert_eq!(s.outputs.len(), 1);
    assert_eq!(s.outputs[0].spent_height, None);
    assert!(s.txs.is_empty());
    assert_eq!(s.next_height, 115);
    assert_eq!(s.recent_hashes.last().unwrap().0, 114);
    // Never before the restore height.
    s.rollback_to(3);
    assert_eq!(s.next_height, 100);
  }

  #[test]
  fn burnt_duplicates_are_flagged_on_rollback() {
    let m = keys::generate(SeedKind::Legacy);
    let mut s = State::new(Net::Mainnet, &m, Some(0), false);
    s.outputs.push(out("same", 1, 10));
    s.outputs.push(out("same", 9, 11));
    s.rollback_to(50);
    assert!(s.outputs[0].burnt);
    assert!(!s.outputs[1].burnt);
  }

  #[test]
  fn state_roundtrip() {
    let m = keys::generate(SeedKind::Polyseed);
    let s = State::new(Net::Mainnet, &m, None, true);
    assert!(s.restore_height > 3_000_000, "polyseed birthday sets the restore height");
    let bytes = s.to_bytes().unwrap();
    let s2 = State::from_bytes(&bytes).unwrap();
    assert_eq!(*s2.mnemonic, *s.mnemonic);
    assert_eq!(s2.restore_height, s.restore_height);
  }
}
