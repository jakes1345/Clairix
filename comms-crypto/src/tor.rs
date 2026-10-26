//! Tor for the app's relay traffic: Arti (the Tor Project's Rust client)
//! behind a SOCKS5 proxy on 127.0.0.1, which the app's HTTP clients use while
//! "Connect through Tor" is on.
//!
//! - Other apps on the phone can reach 127.0.0.1 too, so the proxy takes a
//!   username and password (RFC 1929) made fresh for each start, and refuses
//!   any connection without them.
//! - Names are resolved by the exit, never on the phone: only SOCKS5 CONNECT
//!   is served, with the host passed as given.
//! - Every connection gets a circuit of its own (`isolate_every_stream`): two
//!   requests the app keeps apart (an anonymous delivery and a signed one, say)
//!   leave Tor from different exits, so the relay cannot pair them by address.
//! - Arti refuses local and private addresses through an exit, and onion
//!   addresses are allowed.

use std::net::{Ipv4Addr, Ipv6Addr, SocketAddr};
use std::sync::{Arc, Mutex};

use arti_client::{StreamPrefs, TorClient, TorClientConfig};
use futures::StreamExt;
use subtle::ConstantTimeEq;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::watch;
use tor_rtcompat::PreferredRuntime;

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum TorFailure {
    #[error("Tor could not start: {reason}")]
    Start { reason: String },
}

fn fail(reason: impl std::fmt::Display) -> TorFailure {
    TorFailure::Start { reason: reason.to_string() }
}

/// How far Tor has got.
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct TorStatus {
    /// Ready to carry traffic.
    pub ready: bool,
    /// Bootstrap progress, 0 to 100.
    pub percent: u8,
    /// What it is doing, in Arti's words.
    pub summary: String,
    /// Why it cannot go on (no network, the clock badly off), when it cannot.
    pub blocked: Option<String>,
}

/// A running Tor client and its SOCKS5 proxy. Stopped by [TorProxy::stop] or when dropped.
#[derive(uniffi::Object)]
pub struct TorProxy {
    runtime: Mutex<Option<tokio::runtime::Runtime>>,
    port: u16,
    status: watch::Receiver<TorStatus>,
    shutdown: watch::Sender<bool>,
}

#[uniffi::export]
impl TorProxy {
    /// Starts Tor with its state in [state_dir] and its directory cache in
    /// [cache_dir], both inside [app_dir], and a SOCKS5 proxy on a free
    /// 127.0.0.1 port that admits only [username] and [password]. Returns at
    /// once; [TorProxy::status] follows the bootstrap, and connections wait
    /// for it.
    #[uniffi::constructor]
    pub fn start(app_dir: String, state_dir: String, cache_dir: String, username: String, password: String) -> Result<Arc<Self>, TorFailure> {
        if username.is_empty() || password.is_empty() || username.len() > 255 || password.len() > 255 {
            return Err(fail("the proxy needs a username and password of 1-255 bytes"));
        }
        // Arti's TLS needs a process-wide rustls provider; another start (or
        // another library) may have installed it already, which is fine.
        let _ = rustls::crypto::ring::default_provider().install_default();
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .thread_name("clairix-tor")
            .enable_all()
            .build()
            .map_err(fail)?;

        let mut config = arti_client::config::TorClientConfigBuilder::from_directories(&state_dir, &cache_dir);
        // Android puts an app's directory under ones owned by the system
        // (/data/user/0), which Arti's permission check would refuse; what is
        // inside the app's own directory is checked as usual.
        config.storage().permissions().ignore_prefix(std::path::PathBuf::from(&app_dir));
        config.address_filter().allow_onion_addrs(true);
        let config: TorClientConfig = config.build().map_err(fail)?;

        let (status_tx, status_rx) = watch::channel(TorStatus {
            ready: false,
            percent: 0,
            summary: "Starting".into(),
            blocked: None,
        });
        let (shutdown_tx, shutdown_rx) = watch::channel(false);

        let (client, listener) = runtime.block_on(async {
            let client = TorClient::<PreferredRuntime>::builder()
                .config(config)
                .create_unbootstrapped()
                .map_err(fail)?;
            let listener = TcpListener::bind(SocketAddr::from((Ipv4Addr::LOCALHOST, 0))).await.map_err(fail)?;
            Ok::<_, TorFailure>((client, listener))
        })?;
        let port = listener.local_addr().map_err(fail)?.port();

        // Bootstrap progress, as Arti reports it.
        {
            let client = client.clone();
            let status_tx = status_tx.clone();
            let mut shutdown = shutdown_rx.clone();
            runtime.spawn(async move {
                let mut events = client.bootstrap_events();
                let report = |s: &arti_client::status::BootstrapStatus| TorStatus {
                    ready: s.ready_for_traffic(),
                    percent: (s.as_frac().clamp(0.0, 1.0) * 100.0).round() as u8,
                    summary: s.to_string(),
                    blocked: s.blocked().map(|b| b.to_string()),
                };
                status_tx.send_replace(report(&client.bootstrap_status()));
                loop {
                    tokio::select! {
                        _ = shutdown.changed() => break,
                        next = events.next() => match next {
                            Some(s) => { status_tx.send_replace(report(&s)); }
                            None => break,
                        },
                    }
                }
            });
        }
        {
            let client = client.clone();
            runtime.spawn(async move {
                // Failures show in the status; connections retry the bootstrap.
                let _ = client.bootstrap().await;
            });
        }

        let credentials = Arc::new((username.into_bytes(), password.into_bytes()));
        let mut prefs = StreamPrefs::new();
        prefs.isolate_every_stream();
        let prefs = Arc::new(prefs);
        {
            let mut shutdown = shutdown_rx;
            runtime.spawn(async move {
                loop {
                    tokio::select! {
                        _ = shutdown.changed() => break,
                        accepted = listener.accept() => {
                            let Ok((socket, _)) = accepted else { continue };
                            let client = client.clone();
                            let credentials = credentials.clone();
                            let prefs = prefs.clone();
                            tokio::spawn(async move {
                                let _ = serve(socket, &client, &credentials, &prefs).await;
                            });
                        }
                    }
                }
            });
        }

        Ok(Arc::new(TorProxy { runtime: Mutex::new(Some(runtime)), port, status: status_rx, shutdown: shutdown_tx }))
    }

    /// The proxy's port on 127.0.0.1.
    pub fn socks_port(&self) -> u16 {
        self.port
    }

    pub fn status(&self) -> TorStatus {
        self.status.borrow().clone()
    }

    /// Stops the proxy and Tor. Connections through it end.
    pub fn stop(&self) {
        let _ = self.shutdown.send(true);
        if let Some(runtime) = self.runtime.lock().unwrap_or_else(|e| e.into_inner()).take() {
            runtime.shutdown_background();
        }
    }
}

impl Drop for TorProxy {
    fn drop(&mut self) {
        self.stop();
    }
}

const SOCKS5: u8 = 0x05;
const AUTH_USER_PASSWORD: u8 = 0x02;
const AUTH_NONE_ACCEPTABLE: u8 = 0xFF;
const CMD_CONNECT: u8 = 0x01;
const ATYP_IPV4: u8 = 0x01;
const ATYP_DOMAIN: u8 = 0x03;
const ATYP_IPV6: u8 = 0x04;
const REPLY_OK: u8 = 0x00;
const REPLY_NOT_ALLOWED: u8 = 0x02;
const REPLY_HOST_UNREACHABLE: u8 = 0x04;
const REPLY_COMMAND_UNSUPPORTED: u8 = 0x07;
const REPLY_ADDRESS_UNSUPPORTED: u8 = 0x08;

/// One SOCKS5 connection: authenticate, read the CONNECT, open the Tor
/// stream, then copy both ways until either side closes.
async fn serve(
    mut socket: TcpStream,
    client: &TorClient<PreferredRuntime>,
    credentials: &(Vec<u8>, Vec<u8>),
    prefs: &StreamPrefs,
) -> std::io::Result<()> {
    let (host, port) = match handshake(&mut socket, credentials).await? {
        Some(target) => target,
        None => return Ok(()),
    };
    match client.connect_with_prefs((host.as_str(), port), prefs).await {
        Ok(mut stream) => {
            reply(&mut socket, REPLY_OK).await?;
            let _ = tokio::io::copy_bidirectional(&mut socket, &mut stream).await;
            Ok(())
        }
        Err(_) => reply(&mut socket, REPLY_HOST_UNREACHABLE).await,
    }
}

/// The SOCKS5 greeting, the RFC 1929 login and the request. The target, or
/// None when the connection was refused (and already answered).
async fn handshake(socket: &mut TcpStream, credentials: &(Vec<u8>, Vec<u8>)) -> std::io::Result<Option<(String, u16)>> {
    let mut head = [0u8; 2];
    socket.read_exact(&mut head).await?;
    if head[0] != SOCKS5 {
        return Ok(None);
    }
    let mut methods = vec![0u8; head[1] as usize];
    socket.read_exact(&mut methods).await?;
    if !methods.contains(&AUTH_USER_PASSWORD) {
        socket.write_all(&[SOCKS5, AUTH_NONE_ACCEPTABLE]).await?;
        return Ok(None);
    }
    socket.write_all(&[SOCKS5, AUTH_USER_PASSWORD]).await?;

    // RFC 1929: VER(1) ULEN USER PLEN PASS.
    let mut ver_len = [0u8; 2];
    socket.read_exact(&mut ver_len).await?;
    let mut user = vec![0u8; ver_len[1] as usize];
    socket.read_exact(&mut user).await?;
    let mut plen = [0u8; 1];
    socket.read_exact(&mut plen).await?;
    let mut pass = vec![0u8; plen[0] as usize];
    socket.read_exact(&mut pass).await?;
    let user_ok = user.len() == credentials.0.len() && bool::from(user.ct_eq(&credentials.0));
    let pass_ok = pass.len() == credentials.1.len() && bool::from(pass.ct_eq(&credentials.1));
    if ver_len[0] != 0x01 || !(user_ok & pass_ok) {
        socket.write_all(&[0x01, 0x01]).await?;
        return Ok(None);
    }
    socket.write_all(&[0x01, 0x00]).await?;

    // VER CMD RSV ATYP DST.ADDR DST.PORT
    let mut req = [0u8; 4];
    socket.read_exact(&mut req).await?;
    if req[0] != SOCKS5 {
        return Ok(None);
    }
    if req[1] != CMD_CONNECT {
        reply(socket, REPLY_COMMAND_UNSUPPORTED).await?;
        return Ok(None);
    }
    let host = match req[3] {
        ATYP_DOMAIN => {
            let mut len = [0u8; 1];
            socket.read_exact(&mut len).await?;
            let mut name = vec![0u8; len[0] as usize];
            socket.read_exact(&mut name).await?;
            match String::from_utf8(name) {
                Ok(name) if !name.is_empty() => name,
                _ => {
                    reply(socket, REPLY_ADDRESS_UNSUPPORTED).await?;
                    return Ok(None);
                }
            }
        }
        ATYP_IPV4 => {
            let mut ip = [0u8; 4];
            socket.read_exact(&mut ip).await?;
            Ipv4Addr::from(ip).to_string()
        }
        ATYP_IPV6 => {
            let mut ip = [0u8; 16];
            socket.read_exact(&mut ip).await?;
            Ipv6Addr::from(ip).to_string()
        }
        _ => {
            reply(socket, REPLY_ADDRESS_UNSUPPORTED).await?;
            return Ok(None);
        }
    };
    let mut port = [0u8; 2];
    socket.read_exact(&mut port).await?;
    let port = u16::from_be_bytes(port);
    if port == 0 {
        reply(socket, REPLY_NOT_ALLOWED).await?;
        return Ok(None);
    }
    Ok(Some((host, port)))
}

async fn reply(socket: &mut TcpStream, code: u8) -> std::io::Result<()> {
    // BND.ADDR 0.0.0.0:0: nothing about this end is the client's business.
    socket.write_all(&[SOCKS5, code, 0x00, ATYP_IPV4, 0, 0, 0, 0, 0, 0]).await
}

#[cfg(test)]
mod tests {
    use super::*;

    async fn greet(socket: &mut TcpStream, user: &[u8], pass: &[u8]) -> Vec<u8> {
        socket.write_all(&[SOCKS5, 1, AUTH_USER_PASSWORD]).await.unwrap();
        let mut choice = [0u8; 2];
        socket.read_exact(&mut choice).await.unwrap();
        assert_eq!(choice, [SOCKS5, AUTH_USER_PASSWORD]);
        let mut login = vec![0x01, user.len() as u8];
        login.extend_from_slice(user);
        login.push(pass.len() as u8);
        login.extend_from_slice(pass);
        socket.write_all(&login).await.unwrap();
        let mut verdict = [0u8; 2];
        socket.read_exact(&mut verdict).await.unwrap();
        verdict.to_vec()
    }

    /// The handshake alone, against a listener that runs [handshake]: no Tor needed.
    async fn listener() -> (SocketAddr, tokio::task::JoinHandle<Option<(String, u16)>>) {
        let l = TcpListener::bind(SocketAddr::from((Ipv4Addr::LOCALHOST, 0))).await.unwrap();
        let addr = l.local_addr().unwrap();
        let task = tokio::spawn(async move {
            let (mut s, _) = l.accept().await.unwrap();
            handshake(&mut s, &(b"user".to_vec(), b"secret".to_vec())).await.unwrap()
        });
        (addr, task)
    }

    #[tokio::test]
    async fn the_proxy_refuses_a_wrong_password() {
        let (addr, task) = listener().await;
        let mut s = TcpStream::connect(addr).await.unwrap();
        assert_eq!(greet(&mut s, b"user", b"wrong!").await, vec![0x01, 0x01]);
        assert_eq!(task.await.unwrap(), None);
    }

    #[tokio::test]
    async fn the_proxy_refuses_a_client_offering_no_password() {
        let (addr, task) = listener().await;
        let mut s = TcpStream::connect(addr).await.unwrap();
        s.write_all(&[SOCKS5, 1, 0x00]).await.unwrap();
        let mut choice = [0u8; 2];
        s.read_exact(&mut choice).await.unwrap();
        assert_eq!(choice, [SOCKS5, AUTH_NONE_ACCEPTABLE]);
        assert_eq!(task.await.unwrap(), None);
    }

    #[tokio::test]
    async fn the_proxy_reads_a_host_name_unresolved() {
        let (addr, task) = listener().await;
        let mut s = TcpStream::connect(addr).await.unwrap();
        assert_eq!(greet(&mut s, b"user", b"secret").await, vec![0x01, 0x00]);
        let host = b"relay.example.org";
        let mut req = vec![SOCKS5, CMD_CONNECT, 0, ATYP_DOMAIN, host.len() as u8];
        req.extend_from_slice(host);
        req.extend_from_slice(&443u16.to_be_bytes());
        s.write_all(&req).await.unwrap();
        assert_eq!(task.await.unwrap(), Some(("relay.example.org".to_string(), 443)));
    }

    #[tokio::test]
    async fn the_proxy_serves_connect_only() {
        let (addr, task) = listener().await;
        let mut s = TcpStream::connect(addr).await.unwrap();
        assert_eq!(greet(&mut s, b"user", b"secret").await, vec![0x01, 0x00]);
        // BIND
        s.write_all(&[SOCKS5, 0x02, 0, ATYP_IPV4, 1, 2, 3, 4, 0, 80]).await.unwrap();
        let mut answer = [0u8; 10];
        s.read_exact(&mut answer).await.unwrap();
        assert_eq!(answer[1], REPLY_COMMAND_UNSUPPORTED);
        assert_eq!(task.await.unwrap(), None);
    }

    /// Through the real Tor network: bootstraps, connects through the proxy and
    /// asks the Tor Project whether the request came from Tor. Needs the
    /// internet, so CI runs it on its own (`cargo test -- --ignored tor_`).
    #[test]
    #[ignore]
    fn tor_carries_a_request_and_it_leaves_from_tor() {
        let dir = std::env::temp_dir().join(format!("clairix-tor-test-{}", std::process::id()));
        let state = dir.join("files/tor/state");
        let cache = dir.join("files/tor/cache");
        std::fs::create_dir_all(&state).unwrap();
        std::fs::create_dir_all(&cache).unwrap();
        let proxy = TorProxy::start(
            dir.display().to_string(), state.display().to_string(), cache.display().to_string(), "u".into(), "p".into(),
        )
        .unwrap();
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(300);
        while !proxy.status().ready {
            assert!(std::time::Instant::now() < deadline, "Tor did not bootstrap: {:?}", proxy.status());
            std::thread::sleep(std::time::Duration::from_secs(1));
        }
        let rt = tokio::runtime::Runtime::new().unwrap();
        let body = rt.block_on(async {
            let mut s = TcpStream::connect(("127.0.0.1", proxy.socks_port())).await.unwrap();
            assert_eq!(greet(&mut s, b"u", b"p").await, vec![0x01, 0x00]);
            let host = "check.torproject.org";
            let mut req = vec![SOCKS5, CMD_CONNECT, 0, ATYP_DOMAIN, host.len() as u8];
            req.extend_from_slice(host.as_bytes());
            req.extend_from_slice(&443u16.to_be_bytes());
            s.write_all(&req).await.unwrap();
            let mut answer = [0u8; 10];
            s.read_exact(&mut answer).await.unwrap();
            assert_eq!(answer[1], REPLY_OK, "the proxy could not connect");
            let mut roots = tokio_rustls::rustls::RootCertStore::empty();
            roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
            let tls = tokio_rustls::rustls::ClientConfig::builder().with_root_certificates(roots).with_no_client_auth();
            let connector = tokio_rustls::TlsConnector::from(Arc::new(tls));
            let name = tokio_rustls::rustls::pki_types::ServerName::try_from(host).unwrap();
            let mut s = connector.connect(name, s).await.unwrap();
            s.write_all(b"GET /api/ip HTTP/1.0\r\nHost: check.torproject.org\r\n\r\n").await.unwrap();
            let mut body = String::new();
            let _ = s.read_to_string(&mut body).await;
            body
        });
        proxy.stop();
        let _ = std::fs::remove_dir_all(&dir);
        assert!(body.contains("\"IsTor\":true"), "not from Tor: {body}");
    }
}
