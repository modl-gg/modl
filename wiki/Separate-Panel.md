# Separate Panel

Run the panel with Docker or the jar, then point your servers at it. Settings are environment variables in `.env` ([Configuration](Configuration#environment-variables)).

## Docker

1. Get the compose file and `.env`:
   ```bash
   git clone https://github.com/modl-gg/modl.git
   cd modl
   cp .env.example .env
   ```
2. Edit `.env`: `MODL_PORT` (host port, default 8080), and `MODL_PUBLIC_URL` if the panel has a domain.
3. Start it and read the setup code:
   ```bash
   docker compose up -d
   docker compose logs modl
   ```
   The code is also in the volume: `docker compose exec modl cat /data/setup-code.txt`.
4. Continue with [First Run](First-Run).

| Item | Value |
|---|---|
| Image | `ghcr.io/modl-gg/modl:${MODL_IMAGE_TAG:-3.0}`, following 3.0 patch releases ([Updating](Updating)) |
| Data | volume `modl-data` at `/data` (database, uploads, backups) |
| User | uid 1001 |
| Health check | `GET /actuator/health` |
| Restart policy | `unless-stopped`; moving the database in the panel relies on it ([Database and Storage](Database-and-Storage#moving-in-the-panel)) |
| Logs | 3 files of 10 MB |
| Time zone | `TZ` in `.env`, default UTC; sets the nightly backup hour |
| Memory | `MODL_MEMORY_LIMIT` in `.env`, such as `2g`, unset by default; Java takes up to 75% of it |
| Host access | `host.docker.internal` reaches the host, also on Linux |

Build the image from source: `docker compose -f docker-compose.yml -f docker-compose.build.yml up -d --build`.

## Java

1. Download `modl-server-<version>.jar` and put it in its own folder.
2. Copy `.env.example` from the repository to `.env` in that folder and edit it.
3. Run it from that folder:
   ```bash
   java -jar modl-server-<version>.jar
   ```
4. Open the `/setup` address it prints ([First Run](First-Run)). The code is also in `data/setup-code.txt`.

Data goes to `./data` (`MODL_DATA_DIR`). Run it under a service manager, for example systemd:

```ini
[Unit]
Description=modl
After=network-online.target

[Service]
User=modl
WorkingDirectory=/opt/modl
ExecStart=/usr/bin/java -Xmx2G -jar /opt/modl/modl-server.jar
Restart=on-failure

[Install]
WantedBy=multi-user.target
```

## Connect servers

On each standalone server or proxy:

1. In Settings > General > Minecraft servers > Add server, choose Standalone or proxy and download `connection.yml`. The instance API key on the same page also works.
2. Save it as `plugins/modl/connection.yml` and restart:
   ```yaml
   mode: remote

   remote:
     url: "https://panel.example.com"
     api-key: "<key>"
   ```
3. Paper servers still need PacketEvents.

Servers behind a proxy use `behind-proxy` instead ([Proxy Network](Proxy-Network)). While the panel is unreachable, a `remote` server keeps retrying and refuses logins.

These keys are full trust ([API](API#keys)); keep them on your own servers. For a domain and HTTPS, see [HTTPS and Reverse Proxy](HTTPS-and-Reverse-Proxy).
