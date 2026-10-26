#!/bin/bash
# Installs, or updates, a Clairix relay on a Debian 12 or Ubuntu 24.04 server.
#
#   sudo ./selfhost/install.sh --domain relay.example.org [--email you@example.org] [--no-onion]
#
# Run it from a checkout of the repository, in comms-worker/. Running it again
# updates the relay from the checkout and keeps its storage, its keys and the
# settings in /etc/clairix-relay/relay.env.
#
# What it sets up:
#   - The relay: the same Worker that runs on Cloudflare, under workerd, with
#     its Durable Objects on disk in /var/lib/clairix-relay. It listens on
#     127.0.0.1 only and reaches public addresses only.
#   - Caddy in front: HTTPS for --domain with certificates it renews itself,
#     and no access log.
#   - A Tor onion service (unless --no-onion), through Caddy as well.
#   - systemd: restart on failure, a check that restarts the relay if it stops
#     answering, and the relay's scheduled work every 10 minutes.
#   - Automatic security updates, with a reboot at 04:30 when one needs it.
set -euo pipefail

NODE_VERSION=22.22.0
# From https://nodejs.org/dist/v22.22.0/SHASUMS256.txt.asc, whose signature
# checks out against the Node.js release keys (nodejs/release-keys).
NODE_SHA256_X64=9aa8e9d2298ab68c600bd6fb86a6c13bce11a4eca1ba9b39d79fa021755d7c37
NODE_SHA256_ARM64=1bf1eb9ee63ffc4e5d324c0b9b62cf4a289f44332dfef9607cea1a0d9596ba6f

PREFIX=/opt/clairix-relay
STATE=/var/lib/clairix-relay
CONF=/etc/clairix-relay
RELAY_PORT=8787
ONION_PORT=8790

DOMAIN=""
EMAIL=""
ONION=1
while [ $# -gt 0 ]; do
  case "$1" in
    --domain) DOMAIN="${2:-}"; shift 2 ;;
    --email) EMAIL="${2:-}"; shift 2 ;;
    --no-onion) ONION=0; shift ;;
    -h|--help) sed -n '2,22p' "$0"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

die() { echo "install.sh: $*" >&2; exit 1; }
[ "$(id -u)" -eq 0 ] || die "run as root (sudo)"
[ -n "$DOMAIN" ] || die "--domain is required: the name this relay answers at, already pointing at this server"
printf '%s' "$DOMAIN" | grep -Eq '^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$' || die "--domain must be a lowercase host name, like relay.example.org"
[ -z "$EMAIL" ] || printf '%s' "$EMAIL" | grep -Eq '^[^@[:space:]]+@[^@[:space:]]+$' || die "--email does not look like an address"
command -v apt-get >/dev/null || die "this script is for Debian or Ubuntu"
command -v systemctl >/dev/null || die "systemd is required"

SRC="$(cd "$(dirname "$0")/.." && pwd)"
[ -f "$SRC/wrangler.toml" ] && [ -f "$SRC/package-lock.json" ] || die "run from a checkout: $SRC is not comms-worker/"

echo "== Packages"
export DEBIAN_FRONTEND=noninteractive
apt-get update -q
apt-get install -y -q --no-install-recommends caddy tor unattended-upgrades curl ca-certificates xz-utils openssl rsync

echo "== Node.js $NODE_VERSION (to build the relay; it does not run the relay)"
case "$(uname -m)" in
  x86_64) NODE_ARCH=x64; NODE_SHA256=$NODE_SHA256_X64 ;;
  aarch64|arm64) NODE_ARCH=arm64; NODE_SHA256=$NODE_SHA256_ARM64 ;;
  *) die "unsupported processor $(uname -m): x86_64 and arm64 only" ;;
esac
NODE_DIR="$PREFIX/node-v$NODE_VERSION"
if [ ! -x "$NODE_DIR/bin/node" ]; then
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  curl -fsSL -o "$tmp/node.tar.xz" "https://nodejs.org/dist/v$NODE_VERSION/node-v$NODE_VERSION-linux-$NODE_ARCH.tar.xz"
  echo "$NODE_SHA256  $tmp/node.tar.xz" | sha256sum -c --quiet - || die "the Node.js download does not match its published checksum"
  install -d "$PREFIX"
  rm -rf "$NODE_DIR.partial"
  mkdir "$NODE_DIR.partial"
  tar -xJf "$tmp/node.tar.xz" -C "$NODE_DIR.partial" --strip-components=1
  mv "$NODE_DIR.partial" "$NODE_DIR"
fi
export PATH="$NODE_DIR/bin:$PATH"

echo "== Account"
id -u clairix-relay >/dev/null 2>&1 || useradd --system --home-dir "$STATE" --no-create-home --shell /usr/sbin/nologin clairix-relay

echo "== Building the relay"
install -d -m 0755 "$PREFIX"
rsync -a --delete --exclude node_modules --exclude .wrangler --exclude .dev.vars --exclude 'selfhost/build' "$SRC/" "$PREFIX/src/"
(
  cd "$PREFIX/src"
  WRANGLER_SEND_METRICS=false npm ci --no-audit --no-fund --loglevel=error
  WRANGLER_SEND_METRICS=false node selfhost/build.mjs --out "$PREFIX/build.next" --state "$STATE" --listen "127.0.0.1:$RELAY_PORT" --cron-listen 127.0.0.1:8789
)
install -m 0755 "$(readlink -f "$PREFIX/src/node_modules/.bin/workerd")" "$PREFIX/workerd.next"
"$PREFIX/workerd.next" --version

echo "== Settings ($CONF/relay.env)"
install -d -m 0750 -o root -g clairix-relay "$CONF"
ENV_FILE="$CONF/relay.env"
if [ ! -f "$ENV_FILE" ]; then
  umask 077
  {
    echo "# Clairix relay settings. Every setting src/env.ts reads may go here,"
    echo "# one NAME=value per line; restart with: systemctl restart clairix-relay"
    echo "#"
    echo "# The code people type into Clairix to register on this relay."
    echo "ENROLL_SECRET=$(openssl rand -hex 24)"
    echo "# The address other relays and phones reach this one at."
    echo "RELAY_ORIGIN=https://$DOMAIN"
  } > "$ENV_FILE"
  umask 022
else
  # The domain may have changed; everything else is the operator's.
  if grep -q '^RELAY_ORIGIN=' "$ENV_FILE"; then
    sed -i "s|^RELAY_ORIGIN=.*|RELAY_ORIGIN=https://$DOMAIN|" "$ENV_FILE"
  else
    echo "RELAY_ORIGIN=https://$DOMAIN" >> "$ENV_FILE"
  fi
fi
chown root:clairix-relay "$ENV_FILE"
chmod 0640 "$ENV_FILE"

ONION_HOST=""
if [ "$ONION" -eq 1 ]; then
  echo "== Onion service"
  TORRC=/etc/tor/torrc
  sed -i '/^# BEGIN clairix-relay$/,/^# END clairix-relay$/d' "$TORRC"
  cat >> "$TORRC" <<EOF
# BEGIN clairix-relay
HiddenServiceDir /var/lib/tor/clairix-relay/
HiddenServiceVersion 3
HiddenServicePort 80 127.0.0.1:$ONION_PORT
# END clairix-relay
EOF
  systemctl enable tor >/dev/null
  systemctl restart tor
  for _ in $(seq 1 60); do
    [ -s /var/lib/tor/clairix-relay/hostname ] && break
    sleep 1
  done
  ONION_HOST="$(cat /var/lib/tor/clairix-relay/hostname 2>/dev/null || true)"
  printf '%s' "$ONION_HOST" | grep -Eq '^[a-z2-7]{56}\.onion$' || die "Tor did not create the onion service; see journalctl -u tor"
fi

# Phones that connect through Tor learn the onion address from the relay
# itself (GET / "onion"); without one, the line goes.
sed -i '/^RELAY_ONION=/d' "$ENV_FILE"
[ -n "$ONION_HOST" ] && echo "RELAY_ONION=http://$ONION_HOST" >> "$ENV_FILE"

echo "== Services"
install -m 0644 "$SRC/selfhost/systemd/"*.service "$SRC/selfhost/systemd/"*.timer /etc/systemd/system/
install -m 0755 "$SRC/selfhost/clairix-relay-check" /usr/local/sbin/clairix-relay-check
systemctl daemon-reload
# Swap the new build in only now, so a failed build above leaves the running
# relay as it was.
rm -rf "$PREFIX/build.prev"
[ -d "$PREFIX/build" ] && mv "$PREFIX/build" "$PREFIX/build.prev"
mv "$PREFIX/build.next" "$PREFIX/build"
mv "$PREFIX/workerd.next" "$PREFIX/workerd"
systemctl enable clairix-relay.service clairix-relay-cron.timer clairix-relay-check.timer >/dev/null
systemctl restart clairix-relay.service
systemctl start clairix-relay-cron.timer clairix-relay-check.timer
for _ in $(seq 1 30); do
  curl -fsS -m 5 -o /dev/null "http://127.0.0.1:$RELAY_PORT/" && break
  sleep 1
done
curl -fsS -m 5 -o /dev/null "http://127.0.0.1:$RELAY_PORT/" || { journalctl -u clairix-relay -n 50 --no-pager; die "the relay did not start"; }

echo "== Caddy"
CADDYFILE=/etc/caddy/Caddyfile
if [ -f "$CADDYFILE" ] && ! grep -q '^# Managed by clairix-relay install.sh' "$CADDYFILE"; then
  cp "$CADDYFILE" "$CADDYFILE.before-clairix-relay"
fi
{
  echo "# Managed by clairix-relay install.sh; it is rewritten on every run."
  if [ -n "$EMAIL" ]; then
    echo "{"
    echo "	email $EMAIL"
    echo "}"
  fi
  echo
  echo "# No log directive: Caddy keeps no access log, so no address or request"
  echo "# is written down."
  echo "$DOMAIN {"
  echo "	reverse_proxy 127.0.0.1:$RELAY_PORT {"
  echo "		# The relay's registration limit counts per address. Set here, so a"
  echo "		# client cannot choose its own."
  echo "		header_up CF-Connecting-IP {remote_host}"
  echo "	}"
  echo "}"
  if [ -n "$ONION_HOST" ]; then
    echo
    echo "# The onion service. Tor hands its connections to this loopback port;"
    echo "# the Host the relay sees is fixed, and no client address is passed on"
    echo "# (there is none, and one a client sent would let it pick its own"
    echo "# registration limit)."
    echo "http://:$ONION_PORT {"
    echo "	bind 127.0.0.1"
    echo "	reverse_proxy 127.0.0.1:$RELAY_PORT {"
    echo "		header_up Host $ONION_HOST"
    echo "		header_up -CF-Connecting-IP"
    echo "	}"
    echo "}"
  fi
} > "$CADDYFILE"
caddy validate --config "$CADDYFILE" --adapter caddyfile >/dev/null
systemctl enable caddy >/dev/null
systemctl reload-or-restart caddy

echo "== Automatic security updates"
cat > /etc/apt/apt.conf.d/20auto-upgrades <<'EOF'
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
EOF
cat > /etc/apt/apt.conf.d/52clairix-relay <<'EOF'
// Written by clairix-relay install.sh: reboot when an update needs it.
Unattended-Upgrade::Automatic-Reboot "true";
Unattended-Upgrade::Automatic-Reboot-Time "04:30";
EOF

echo
echo "The relay is up."
echo "  Address:          https://$DOMAIN (once DNS points here and Caddy has its certificate)"
[ -n "$ONION_HOST" ] && echo "  Onion address:    http://$ONION_HOST"
echo "  Registration code: ENROLL_SECRET in $ENV_FILE"
echo "  Storage:          $STATE (back it up: it holds the relay's keys and every mailbox)"
echo "  Logs:             journalctl -u clairix-relay"
echo "Update later by pulling the repository and running this script again."
