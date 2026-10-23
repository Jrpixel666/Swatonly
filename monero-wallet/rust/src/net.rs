//! Talking to a Monero node, with a strict connection policy.
//!
//! * HTTPS is required, verified against Mozilla's roots compiled into the app (user-installed
//!   CAs on the phone are not trusted), or against a pinned certificate fingerprint for nodes
//!   with self-signed certificates.
//! * Plain HTTP is allowed only to `.onion` nodes through Tor (onion services are end-to-end
//!   encrypted and authenticated by their address) and to loopback/private-network addresses.
//! * With Tor on, every request goes through the SOCKS5 proxy and DNS is resolved by Tor
//!   (`socks5h`), so nothing leaks to the local network. "Tor only" refuses to run without it.
//! * No redirects, no cookies, no proxy from the environment, bounded response sizes.

use std::{
  net::IpAddr,
  sync::{Arc, Mutex},
  time::Duration,
};

use reqwest::{header, redirect, Client, StatusCode, Url};
use rustls::{
  client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier},
  crypto::{ring, CryptoProvider, WebPkiSupportedAlgorithms},
  pki_types::{CertificateDer, ServerName, UnixTime},
  DigitallySignedStruct, SignatureScheme,
};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use zeroize::Zeroizing;

use monero_daemon_rpc::{prelude::InterfaceError, HttpTransport, MoneroDaemon};

use crate::Error;

/// Hard cap when the caller gives no limit (Monero blocks are unbounded, but not this unbounded).
const MAX_RESPONSE: usize = 128 * 1024 * 1024;

#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct NodeConfig {
  /// `https://host:port`, `http://xyz.onion:18081`, optionally with `user:pass@`.
  pub url: String,
  /// SOCKS5 proxy for Tor, e.g. `127.0.0.1:9050` (Orbot). Empty = direct.
  #[serde(default)]
  pub tor_proxy: String,
  /// Refuse to connect unless Tor is configured.
  #[serde(default)]
  pub tor_only: bool,
  /// SHA-256 of the node's TLS certificate (hex). When set, only that certificate is accepted.
  #[serde(default)]
  pub cert_sha256: String,
}

fn is_private(ip: IpAddr) -> bool {
  match ip {
    IpAddr::V4(v4) => v4.is_loopback() || v4.is_private() || v4.is_link_local(),
    IpAddr::V6(v6) => v6.is_loopback() || (v6.segments()[0] & 0xfe00) == 0xfc00,
  }
}

/// A parsed, policy-checked node configuration.
#[derive(Clone)]
pub struct Endpoint {
  base: Url,
  user: Option<Zeroizing<String>>,
  pass: Option<Zeroizing<String>>,
  proxy: Option<String>,
  pin: Option<[u8; 32]>,
}

impl NodeConfig {
  pub fn endpoint(&self) -> Result<Endpoint, Error> {
    let mut url = Url::parse(self.url.trim()).map_err(|_| Error::Input("invalid node URL"))?;
    let host = url.host_str().ok_or(Error::Input("node URL has no host"))?.to_ascii_lowercase();
    let onion = host.ends_with(".onion");

    let proxy = match self.tor_proxy.trim() {
      "" => None,
      p => {
        let hostport = p.trim_start_matches("socks5h://").trim_start_matches("socks5://");
        let (h, port) = hostport.rsplit_once(':').ok_or(Error::Input("Tor proxy must be host:port"))?;
        let _: u16 = port.parse().map_err(|_| Error::Input("bad Tor proxy port"))?;
        let ip: IpAddr = h
          .trim_matches(|c| c == '[' || c == ']')
          .parse()
          .map_err(|_| Error::Input("the Tor proxy must be an IP address"))?;
        if !ip.is_loopback() {
          return Err(Error::Input("the Tor proxy must run on this device (127.0.0.1)"));
        }
        // socks5h: the proxy resolves names, so DNS never leaves Tor.
        Some(format!("socks5h://{hostport}"))
      }
    };

    if self.tor_only && proxy.is_none() {
      return Err(Error::Input("Tor only is on but no Tor proxy is set"));
    }
    if onion && proxy.is_none() {
      return Err(Error::Input(".onion nodes need the Tor proxy"));
    }
    match url.scheme() {
      "https" => {}
      "http" => {
        let private = host
          .trim_matches(|c| c == '[' || c == ']')
          .parse::<IpAddr>()
          .map(is_private)
          .unwrap_or(false);
        if !(onion || private) {
          return Err(Error::Input(
            "plain http is only allowed for .onion and local-network nodes; use https",
          ));
        }
      }
      _ => return Err(Error::Input("node URL must be https:// or http://")),
    }

    let pin = match self.cert_sha256.trim() {
      "" => None,
      p => {
        let clean: String = p.chars().filter(|c| c.is_ascii_hexdigit()).collect();
        let bytes = hex::decode(clean).map_err(|_| Error::Input("bad certificate fingerprint"))?;
        Some(bytes.try_into().map_err(|_| Error::Input("certificate fingerprint must be SHA-256"))?)
      }
    };
    if pin.is_some() && url.scheme() != "https" {
      return Err(Error::Input("a certificate pin needs an https node"));
    }

    let user = (!url.username().is_empty()).then(|| Zeroizing::new(url.username().to_owned()));
    let pass = url.password().map(|p| Zeroizing::new(p.to_owned()));
    let _ = url.set_username("");
    let _ = url.set_password(None);
    url.set_query(None);
    url.set_fragment(None);
    if !url.path().ends_with('/') {
      let path = format!("{}/", url.path());
      url.set_path(&path);
    }
    Ok(Endpoint { base: url, user, pass, proxy, pin })
  }
}

fn algorithms() -> WebPkiSupportedAlgorithms {
  ring::default_provider().signature_verification_algorithms
}

/// Accepts exactly one certificate, by SHA-256 of its DER encoding. Handshake signatures are still
/// verified, so a man in the middle can't replay the pinned certificate.
#[derive(Debug)]
struct PinnedCert([u8; 32]);

impl ServerCertVerifier for PinnedCert {
  fn verify_server_cert(
    &self,
    end_entity: &CertificateDer<'_>,
    _intermediates: &[CertificateDer<'_>],
    _server_name: &ServerName<'_>,
    _ocsp: &[u8],
    _now: UnixTime,
  ) -> Result<ServerCertVerified, rustls::Error> {
    let got: [u8; 32] = Sha256::digest(end_entity.as_ref()).into();
    if subtle::ConstantTimeEq::ct_eq(&got[..], &self.0[..]).into() {
      Ok(ServerCertVerified::assertion())
    } else {
      Err(rustls::Error::General("certificate does not match the pinned fingerprint".into()))
    }
  }
  fn verify_tls12_signature(
    &self,
    message: &[u8],
    cert: &CertificateDer<'_>,
    dss: &DigitallySignedStruct,
  ) -> Result<HandshakeSignatureValid, rustls::Error> {
    rustls::crypto::verify_tls12_signature(message, cert, dss, &algorithms())
  }
  fn verify_tls13_signature(
    &self,
    message: &[u8],
    cert: &CertificateDer<'_>,
    dss: &DigitallySignedStruct,
  ) -> Result<HandshakeSignatureValid, rustls::Error> {
    rustls::crypto::verify_tls13_signature(message, cert, dss, &algorithms())
  }
  fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
    algorithms().supported_schemes()
  }
}

fn tls_config(pin: Option<[u8; 32]>) -> Result<rustls::ClientConfig, Error> {
  let provider = Arc::new(CryptoProvider { ..ring::default_provider() });
  let builder = rustls::ClientConfig::builder_with_provider(provider)
    .with_safe_default_protocol_versions()
    .map_err(|_| Error::Internal("tls versions"))?;
  let mut config = match pin {
    Some(pin) => builder
      .dangerous()
      .with_custom_certificate_verifier(Arc::new(PinnedCert(pin)))
      .with_no_client_auth(),
    None => {
      let roots = rustls::RootCertStore { roots: webpki_roots::TLS_SERVER_ROOTS.to_vec() };
      builder.with_root_certificates(roots).with_no_client_auth()
    }
  };
  config.alpn_protocols = vec![b"http/1.1".to_vec()];
  // No session resumption: tickets would link this connection to earlier ones.
  config.resumption = rustls::client::Resumption::disabled();
  config.enable_sni = true;
  Ok(config)
}

/// The `HttpTransport` monero-oxide's daemon client runs on.
#[derive(Clone)]
pub struct Transport {
  client: Client,
  ep: Arc<Endpoint>,
  /// Digest-auth state: the last challenge and its nonce count.
  auth: Arc<Mutex<Option<(digest_auth::WwwAuthenticateHeader, u32)>>>,
}

impl Transport {
  pub fn new(ep: Endpoint) -> Result<Transport, Error> {
    let onion = ep.base.host_str().is_some_and(|h| h.ends_with(".onion"));
    let mut b = Client::builder()
      .use_preconfigured_tls(tls_config(ep.pin)?)
      .redirect(redirect::Policy::none())
      .http1_only()
      .connect_timeout(Duration::from_secs(if ep.proxy.is_some() { 90 } else { 20 }))
      .timeout(Duration::from_secs(if ep.proxy.is_some() || onion { 300 } else { 120 }))
      .pool_idle_timeout(Duration::from_secs(30))
      .referer(false);
    b = match &ep.proxy {
      Some(p) => b.proxy(reqwest::Proxy::all(p).map_err(|_| Error::Input("bad Tor proxy"))?),
      None => b.no_proxy(),
    };
    let client = b.build().map_err(|_| Error::Internal("http client"))?;
    Ok(Transport { client, ep: Arc::new(ep), auth: Arc::new(Mutex::new(None)) })
  }

  fn authorization(&self, uri_path: &str, body: &[u8]) -> Option<String> {
    let (user, pass) = (self.ep.user.as_ref()?, self.ep.pass.as_ref()?);
    let mut guard = self.auth.lock().ok()?;
    let (challenge, nc) = guard.as_mut()?;
    *nc += 1;
    challenge.nc = *nc;
    let ctx = digest_auth::AuthContext::new_with_method(
      user.as_str(),
      pass.as_str(),
      uri_path,
      Some(body),
      digest_auth::HttpMethod::POST,
    );
    challenge.respond(&ctx).ok().map(|a| a.to_header_string())
  }

  async fn send(&self, route: &str, body: &[u8], limit: usize) -> Result<(StatusCode, Vec<u8>, Option<String>), InterfaceError> {
    let url = self.ep.base.join(route).map_err(|_| InterfaceError::InternalError("bad route".into()))?;
    let mut req = self
      .client
      .post(url.clone())
      .header(header::CONTENT_TYPE, "application/json")
      .header(header::ACCEPT, "*/*")
      .body(body.to_vec());
    if let Some(a) = self.authorization(url.path(), body) {
      req = req.header(header::AUTHORIZATION, a);
    }
    let mut resp = req
      .send()
      .await
      .map_err(|e| InterfaceError::InterfaceError(format!("connection failed: {}", redact(&e))))?;
    let status = resp.status();
    let challenge = resp
      .headers()
      .get(header::WWW_AUTHENTICATE)
      .and_then(|h| h.to_str().ok())
      .map(str::to_owned);
    if let Some(len) = resp.content_length() {
      if len > limit as u64 {
        return Err(InterfaceError::InvalidInterface("node response too large".into()));
      }
    }
    let mut out = Vec::new();
    while let Some(chunk) = resp
      .chunk()
      .await
      .map_err(|e| InterfaceError::InterfaceError(format!("read failed: {}", redact(&e))))?
    {
      if out.len() + chunk.len() > limit {
        return Err(InterfaceError::InvalidInterface("node response too large".into()));
      }
      out.extend_from_slice(&chunk);
    }
    Ok((status, out, challenge))
  }
}

/// reqwest errors include the URL, which may carry credentials. Keep only the kind.
fn redact(e: &reqwest::Error) -> &'static str {
  if e.is_timeout() {
    "timed out"
  } else if e.is_connect() {
    "could not connect (is the node / Tor up? is the certificate valid?)"
  } else if e.is_body() || e.is_decode() {
    "bad response body"
  } else if e.is_request() {
    "request error"
  } else {
    "network error"
  }
}

impl HttpTransport for Transport {
  fn post(
    &self,
    route: &str,
    body: Vec<u8>,
    response_size_limit: Option<usize>,
  ) -> impl Send + core::future::Future<Output = Result<Vec<u8>, InterfaceError>> {
    let route = route.trim_start_matches('/').to_owned();
    async move {
      let limit = response_size_limit.unwrap_or(MAX_RESPONSE).min(MAX_RESPONSE);
      let (mut status, mut out, challenge) = self.send(&route, &body, limit).await?;
      if status == StatusCode::UNAUTHORIZED && self.ep.user.is_some() {
        let challenge = challenge
          .and_then(|c| digest_auth::parse(&c).ok())
          .ok_or_else(|| InterfaceError::InterfaceError("node wants a login we can't do".into()))?;
        if let Ok(mut g) = self.auth.lock() {
          *g = Some((challenge, 0));
        }
        (status, out, _) = self.send(&route, &body, limit).await?;
      }
      match status {
        StatusCode::OK => Ok(out),
        StatusCode::UNAUTHORIZED => Err(InterfaceError::InterfaceError("node login rejected".into())),
        s => {
          out.clear();
          Err(InterfaceError::InterfaceError(format!("node answered HTTP {}", s.as_u16())))
        }
      }
    }
  }
}

pub type Daemon = MoneroDaemon<Transport>;

pub async fn connect(cfg: &NodeConfig) -> Result<Daemon, Error> {
  let transport = Transport::new(cfg.endpoint()?)?;
  MoneroDaemon::new(transport).await.map_err(Error::from)
}

#[cfg(test)]
mod tests {
  use super::*;

  fn cfg(url: &str, proxy: &str) -> NodeConfig {
    NodeConfig { url: url.into(), tor_proxy: proxy.into(), ..Default::default() }
  }

  #[test]
  fn policy() {
    assert!(cfg("https://node.example.com:18089", "").endpoint().is_ok());
    assert!(cfg("http://node.example.com:18089", "").endpoint().is_err());
    assert!(cfg("http://192.168.1.5:18081", "").endpoint().is_ok());
    assert!(cfg("http://127.0.0.1:18081", "").endpoint().is_ok());
    assert!(cfg("http://8.8.8.8:18081", "").endpoint().is_err());
    let onion = "http://abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz23.onion:18081";
    assert!(cfg(onion, "").endpoint().is_err());
    assert!(cfg(onion, "127.0.0.1:9050").endpoint().is_ok());
    assert!(cfg("https://n.example.com", "10.0.0.2:9050").endpoint().is_err());
    assert!(cfg("ftp://n.example.com", "").endpoint().is_err());
    let mut c = cfg("https://n.example.com", "");
    c.tor_only = true;
    assert!(c.endpoint().is_err());
    c.tor_proxy = "127.0.0.1:9050".into();
    assert!(c.endpoint().is_ok());
    c.cert_sha256 = "ab".repeat(32);
    assert!(c.endpoint().is_ok());
    c.cert_sha256 = "abcd".into();
    assert!(c.endpoint().is_err());
  }

  #[test]
  fn credentials_are_split_from_url() {
    let ep = cfg("https://alice:s3cret@n.example.com:18089/x?y=1", "").endpoint().unwrap();
    assert_eq!(ep.base.as_str(), "https://n.example.com:18089/x/");
    assert_eq!(ep.user.as_deref().map(String::as_str), Some("alice"));
    assert_eq!(ep.pass.as_deref().map(String::as_str), Some("s3cret"));
  }
}
