# Polyseed

Rust implementation of [Polyseed](https://github.com/tevador/polyseed).

This library is usable under no-std when the `std` feature (on by default) is
disabled.

### Cargo Features

- `std` (on by default): Enables `std` (and with it, more efficient internal
  implementations).

## Vendored copy

Copied from serai-dex/serai at commit 18897978. `Polyseed::key` is patched to match the
reference implementation (PBKDF2-SHA256, salt domain-separated by coin, birthday and features);
see `polyseed_key_matches_reference` in `src/keys.rs`.
