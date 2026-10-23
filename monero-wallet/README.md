# XMR Vault

A security-first Monero wallet for Android. Everything that touches keys is written in **Rust**
(`rust/`); the Android app (`app/`) is a thin Kotlin UI with **no third-party JVM dependencies**.

The Monero cryptography (CLSAG, Bulletproofs+, output scanning, decoy selection) comes from
[monero-oxide](https://github.com/monero-oxide/monero-oxide) (`monero-wallet` 0.2), audited by
Cypher Stack. Seed handling is Serai's `monero-seed` and `polyseed` crates (MIT), vendored in
`rust/vendor/`. This project adds the vault, the node connection, sync/send logic and the app.

Read [SECURITY.md](SECURITY.md) for the threat model, including what this wallet can't protect
against.

## Security features

**Keys and storage**
- The wallet file is encrypted with XChaCha20-Poly1305. Its key is Argon2id (128 MiB, 3 passes)
  of your PIN **plus a 32-byte device pepper**. The pepper is stored only wrapped by a
  non-exportable AES-256-GCM key in the Android Keystore (StrongBox when available, usable only
  while the phone is unlocked). A copied wallet file can't be brute-forced away from the phone.
- Erasing deletes the Keystore key. That is a cryptographic erase: flash remnants of the file are
  useless.
- The header is authenticated. Tampering, and edits that weaken the KDF parameters, are refused.
- Writes are atomic (temp file, fsync, rename). Data lives in `noBackupFilesDir`; backups and
  device-to-device transfer are disabled.
- Secrets are zeroized in Rust (`zeroize`) and cross JNI as `byte[]` that Kotlin zeroes, not
  `String`s.

**PIN and coercion**
- Scrambled on-screen PIN pad: digits move every time, the system keyboard is never involved
  (no IME can log it), and it is hidden from accessibility services on Android 14+.
- Weak PINs (too short, `111111`, `123456`, …) are refused.
- Escalating lockouts after 3 wrong PINs (30 s doubling to 1 h, enforced on both the wall clock
  and the boot clock). The wallet is erased after 10 wrong PINs (configurable: 5, 10, 20).
- **Duress PIN**: it looks like a normal unlock but silently destroys the wallet. The app then
  looks freshly installed.
- The PIN is asked again to **send**, **show the seed**, **change PINs** and **erase**. Wrong
  PINs there count toward the erase limit too.
- No biometric unlock, on purpose: a fingerprint can be taken from you.

**Privacy**
- Blocks are scanned **on the phone**. The node never sees your view key, addresses, outputs or
  balance. Spends are detected by matching key images locally, without asking the node
  "is this spent?".
- **Tor**: with Orbot running, all traffic goes through its SOCKS5 port and DNS is resolved by
  Tor (`socks5h`). *Tor only* mode refuses to connect without it. `.onion` nodes are supported.
- Subaddresses with a 50-address scan lookahead. Every transaction gets a fresh random outgoing
  view key. Monero's standard ring size (16) and decoy selection are used.

**Network**
- HTTPS only, verified against Mozilla's root store **compiled into the app**. Root CAs that
  someone installed on the phone are not trusted. Plain HTTP is allowed only for `.onion` (through
  Tor) and loopback/LAN node addresses.
- Optional **certificate pinning** (SHA-256 of the node's certificate) for self-signed nodes.
- No redirects, no cookies, no TLS session resumption, no proxy from the environment, bounded
  response sizes, digest-auth logins for your own node.
- Fee rates from the node are capped, and the fee is shown before you confirm.

**Correctness**
- Reorganizations are detected (100-block hash window) and rolled back. A spend of yours that
  gets reorged out keeps its inputs reserved, so they can't be double-spent.
- Burning-bug protection: an output that reuses a one-time key we already own is never counted.
- 10-block lock (60 for coinbase) and additional timelocks are honoured.

**App and process hardening**
- `FLAG_SECURE` everywhere: no screenshots or screen recording, blank in recent apps.
  `setRecentsScreenshotEnabled(false)` on Android 13+.
- Tapjacking protection: touches are filtered when obscured, and other apps' overlays are hidden
  while the wallet is in front (`HIDE_OVERLAY_WINDOWS`).
- Locks the moment the app leaves the screen, and after 60 s idle (configurable).
- Clipboard: only addresses and transaction IDs can be copied (never the seed), flagged
  sensitive, cleared after 60 s and on lock.
- Text fields: no suggestions, no personalized learning, no autofill.
- Native library: `PR_SET_DUMPABLE=0` (no ptrace from other apps, no core dumps), full RELRO,
  BIND_NOW, non-executable stack, 16 KB page aligned, symbols stripped, overflow checks on, every
  JNI entry point catches panics.
- Warnings shown for: root, debuggable build, attached debugger, a tracer process,
  Frida/Xposed loaded, emulator, USB debugging on, no screen lock, software-only Keystore.
- Permissions: `INTERNET` and `HIDE_OVERLAY_WINDOWS` only. No camera, storage or contacts.
- Release builds: R8 minification, logging stripped, not debuggable, v2+v3 signatures only, no
  dependency-info blob. The release key comes from CI secrets and is never stored in the repo.

**Supply chain**
- No third-party JVM dependencies. Rust dependencies are locked (`Cargo.lock`, `--locked`) and audited
  with `cargo audit` in CI. The Gradle wrapper is checksum-pinned and CI actions are pinned by
  commit hash.

## Using it

1. Install the APK (from the CI run's artifacts or a release).
2. Create a wallet (Polyseed recommended) or restore one. Write the seed on paper.
3. *Settings → Node*: your own node if you have one. Otherwise install
   [Orbot](https://orbot.app), keep the proxy at `127.0.0.1:9050`, and use an `.onion` node or an
   `https://` node you trust. Tap **Save and test node**.
4. The wallet syncs while the home screen is open.

Stagenet is available for trying things with test coins.

## Building

CI (`.github/workflows/build-wallet-apk.yml`) runs the unit tests, clippy, `cargo audit`, an
end-to-end test against a real regtest `monerod` (mine, scan, send, reorg, receive), then builds
the APK. Push a `wallet-v*` tag to publish a release.

Signing: create a keystore and add these repository secrets:
`XMRVAULT_KEYSTORE_B64` (`base64 -w0 release.jks`), `XMRVAULT_KEYSTORE_PASSWORD`,
`XMRVAULT_KEY_ALIAS`, `XMRVAULT_KEY_PASSWORD`. Without them the APK is signed with a throwaway
debug key, and updates won't install over it once you switch to a real key.

Locally you need the Android SDK, NDK 27.2.12479018, Rust with the Android targets, and
`cargo-ndk`:

```sh
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
cargo install cargo-ndk --locked
cd monero-wallet
./gradlew assembleRelease          # runs cargo ndk for all three ABIs first
```

Tests:

```sh
cd monero-wallet/rust
cargo test
# end to end, against a local regtest daemon
monerod --regtest --offline --fixed-difficulty 1 --rpc-bind-port 18081 --non-interactive &
MONEROD_URL=http://127.0.0.1:18081 cargo test --test regtest -- --nocapture
```

## Layout

| Path | What |
|---|---|
| `rust/src/vault.rs` | Encrypted wallet file, Argon2id + XChaCha20-Poly1305, duress verifier |
| `rust/src/keys.rs` | Polyseed / 25-word seeds, key derivation, key images |
| `rust/src/net.rs` | Node transport: TLS policy, pinning, Tor, digest auth, size limits |
| `rust/src/wallet.rs` | State, scanning, reorgs, input selection, signing |
| `rust/src/api.rs` | The session (one unlocked wallet, wiped on lock) |
| `rust/src/jni.rs`, `hardening.rs` | JNI boundary, process hardening |
| `app/…/KeyGuard.kt` | Android Keystore pepper wrapping |
| `app/…/Guard.kt` | Lockouts and erase-after-N |
| `app/…/PinPad.kt` | Scrambled PIN pad |
| `app/…/MainActivity.kt` | Screens |
