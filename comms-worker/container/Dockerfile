# A Clairix relay in one container, for hosts that run containers rather than
# lend a machine (bunny.net Magic Containers; see container/README.md). The
# same relay selfhost/install.sh puts on a server: the Worker under workerd
# with its Durable Objects on disk, Caddy for HTTPS, Tor for the onion
# service, and container/supervisor.mjs in place of systemd.
#
#   docker build -f container/Dockerfile -t clairix-relay .     (from comms-worker/)
#
# Settings come from the environment (container/README.md); everything kept
# lives on the volume mounted at /data.

# ── Build: bundle the Worker and write its workerd config ─────────────────
FROM node:22.22.0-bookworm-slim@sha256:dd9d21971ec4395903fa6143c2b9267d048ae01ca6d3ea96f16cb30df6187d94 AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN WRANGLER_SEND_METRICS=false npm ci --no-audit --no-fund --loglevel=error
COPY wrangler.toml tsconfig.json ./
COPY src ./src
COPY selfhost ./selfhost
RUN WRANGLER_SEND_METRICS=false node selfhost/build.mjs --out /out/build --state /data/relay \
      --listen 127.0.0.1:8787 --cron-listen 127.0.0.1:8789 \
 && install -m 0755 "$(readlink -f node_modules/.bin/workerd)" /out/workerd \
 && /out/workerd --version

# ── Build: Caddy, with the Cloudflare DNS module ─────────────────────────
# Debian's caddy package (2.6) has no DNS providers, so a certificate could
# only be checked over ports 80 and 443. This one can also be checked through
# a TXT record in Cloudflare DNS (ACME_DNS=cloudflare), which needs no
# connection from Let's Encrypt to the container at all. Versions are pinned;
# Go's checksum database vouches for every module the build fetches.
FROM golang:1.26.10-bookworm@sha256:23ce71337b726608afb1bc00986e630a05034a87a87ea1fc2cd1ae5cf33387eb AS caddy
ARG XCADDY_VERSION=v0.4.7
ARG CADDY_VERSION=v2.11.7
ARG CADDY_DNS_CLOUDFLARE_VERSION=v0.2.4
RUN go install github.com/caddyserver/xcaddy/cmd/xcaddy@${XCADDY_VERSION} \
 && CGO_ENABLED=0 xcaddy build ${CADDY_VERSION} \
      --with github.com/caddy-dns/cloudflare@${CADDY_DNS_CLOUDFLARE_VERSION} \
      --output /out/caddy \
 && /out/caddy version \
 && /out/caddy list-modules | grep -qx dns.providers.cloudflare

# ── Run ───────────────────────────────────────────────────────────────────
FROM node:22.22.0-bookworm-slim@sha256:dd9d21971ec4395903fa6143c2b9267d048ae01ca6d3ea96f16cb30df6187d94
RUN apt-get update -q \
 && DEBIAN_FRONTEND=noninteractive apt-get upgrade -y -q \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y -q --no-install-recommends tor tini ca-certificates \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system --gid 10001 relay \
 && useradd --system --uid 10001 --gid relay --home-dir /nonexistent --no-create-home --shell /usr/sbin/nologin relay
COPY --from=build /out/build /opt/clairix-relay/build
COPY --from=build /out/workerd /opt/clairix-relay/workerd
COPY --from=caddy /out/caddy /usr/bin/caddy
COPY container/supervisor.mjs container/backup.mjs container/config.mjs /opt/clairix-relay/supervisor/
# 443 and 80: HTTPS, and the HTTP that certificates are issued over (unless
# ACME_DNS has them checked through DNS instead).
# 8081: /healthz, for the host's health checks; publish no endpoint for it.
EXPOSE 443 80 8081
ENTRYPOINT ["/usr/bin/tini", "--", "node", "--disable-warning=ExperimentalWarning", "/opt/clairix-relay/supervisor/supervisor.mjs"]
