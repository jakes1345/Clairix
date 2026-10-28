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

# ── Run ───────────────────────────────────────────────────────────────────
FROM node:22.22.0-bookworm-slim@sha256:dd9d21971ec4395903fa6143c2b9267d048ae01ca6d3ea96f16cb30df6187d94
RUN apt-get update -q \
 && DEBIAN_FRONTEND=noninteractive apt-get upgrade -y -q \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y -q --no-install-recommends caddy tor tini ca-certificates \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system --gid 10001 relay \
 && useradd --system --uid 10001 --gid relay --home-dir /nonexistent --no-create-home --shell /usr/sbin/nologin relay
COPY --from=build /out/build /opt/clairix-relay/build
COPY --from=build /out/workerd /opt/clairix-relay/workerd
COPY container/supervisor.mjs container/backup.mjs container/config.mjs /opt/clairix-relay/supervisor/
# 443 and 80: HTTPS, and the HTTP that certificates are issued over.
# 8081: /healthz, for the host's health checks; publish no endpoint for it.
EXPOSE 443 80 8081
ENTRYPOINT ["/usr/bin/tini", "--", "node", "--disable-warning=ExperimentalWarning", "/opt/clairix-relay/supervisor/supervisor.mjs"]
