# Threat model

No software wallet is "unhackable". This page says what XMR Vault defends against and what it
doesn't, so you can decide what to keep in it.

## Defended

| Threat | Defense |
|---|---|
| Phone stolen while locked; wallet file copied off it (backup, forensic image) | The vault key needs the PIN **and** a pepper wrapped by a non-exportable Keystore key (StrongBox/TEE). Offline brute force isn't possible without breaking the secure hardware. |
| Someone guessing PINs on the phone | Escalating lockouts, erase after N tries, Argon2id makes each guess slow. |
| Being forced to unlock | Duress PIN silently erases the wallet. No biometrics. |
| Someone grabbing the unlocked phone | Locks when the app leaves the screen and after inactivity; PIN again to send, show the seed or erase. |
| Screenshots, screen recording, recent-apps thumbnails, screen sharing | `FLAG_SECURE`, `setRecentsScreenshotEnabled(false)`. |
| Overlay/tapjacking apps | Obscured touches filtered; overlays hidden while in front. |
| Keyboard apps logging the PIN | The PIN pad never uses the system keyboard. Seed entry disables suggestions and learning, but **the keyboard still sees typed seed words**: use a trusted, offline keyboard when restoring. |
| Clipboard snooping | The seed can't be copied; addresses are flagged sensitive and cleared after 60 s. |
| Cloud/adb backups, device transfer | Disabled; data in `noBackupFilesDir`. |
| Other apps attaching a debugger / reading memory via ptrace | `PR_SET_DUMPABLE=0`. |
| Network observers | TLS with built-in roots (user-installed CAs ignored), optional pinning, Tor with DNS through Tor. |
| A curious node | Scanning is local: the node sees which blocks you download (all of them), not which outputs are yours. Spends are detected locally from key images. |
| A malicious node inflating fees | Fee rate capped; fee shown before you confirm. |
| Chain reorganizations, duplicate one-time keys (burning bug) | Handled in the scanner (see README). |

## Not defended

- **A compromised phone.** Root malware, a malicious OS build, or a kernel exploit can read the
  keys from memory while the wallet is unlocked, or record the PIN pad. The root/hook checks
  only warn; they can be bypassed. For large amounts, use a hardware wallet or an air-gapped
  setup.
- **A malicious node actively lying.** monero-oxide notes that an *active* adversarial node can
  serve a crafted view of the chain to try to identify which ring member you spend (for example
  with a wrong first-output index). Use your own node, or at least an `.onion` node you trust.
  It can't steal funds.
- **Transaction timing and amounts seen by the node** when you broadcast. Tor hides your IP, but
  the node you broadcast through learns the transaction came from its client.
- **JVM strings.** The seed is shown on screen through Android `TextView`s and the 16/25 words
  briefly exist as Java strings, which can't be wiped. The Rust side zeroizes its copies.
- **Flash storage remanence** of the vault file. That's why erase relies on deleting the Keystore
  key (cryptographic erase).
- **Your paper seed.** Whoever has it has the coins.

## Unaudited code

monero-oxide's cryptography was audited. The rest — the vault format, the network policy, the
sync and send logic, the JNI layer and the Android app — was written for this project and has
**not had an independent audit**. It has unit tests and an end-to-end test against a real
`monerod` regtest chain. Treat it accordingly: try it on stagenet first and don't hold more than
you can afford to lose until it has been reviewed.

## Reporting

Open a GitHub security advisory on this repository (not a public issue).
