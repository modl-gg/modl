# Configuration

Where each setting lives. A value set in `connection.yml` or an environment variable is fixed, and the panel shows it read-only.

| Kind | Plugin with built-in panel | Separate panel (Docker, jar) | Panel |
|---|---|---|---|
| Panel URL | `embedded.public-url` | `MODL_PUBLIC_URL` | Settings > General > Panel |
| Server name | | `MODL_SERVER_NAME` | Settings > General > Panel |
| Owner email | `embedded.admin-email` | `MODL_ADMIN_EMAIL` | |
| API key | | `MODL_API_KEY` | Settings > General > Minecraft servers |
| Email | `embedded.smtp` | `MODL_SMTP_*` | Settings > General > Email |
| AI | `embedded.ai` | `MODL_AI_*` | Settings > Tickets > AI moderation |
| Database | `embedded.database` | `MODL_DATABASE_*` | Settings > System > Database |
| Upload storage, S3, upload limits, backups | | `MODL_STORAGE`, `MODL_S3_*`, `MODL_MAX_*`, `MODL_BACKUP_*` | Settings > System > Storage |
| IP history, notifications, log and replay retention | | `MODL_IP_HISTORY_DAYS`, `MODL_NOTIFICATION_EXPIRY_DAYS` | Settings > System > Data retention |
| IP lookups, shared address limit | | | Settings > Moderation > Player addresses |
| Everything else | defaults below | environment variables | |

The built-in panel ignores environment variables except the switches named at the end of [Environment variables](#environment-variables). `config.yml` `server.name` only names that Minecraft server in logs and staff lists. In-game behavior is in the [Plugin Files](Plugin-Files).

## connection.yml

`plugins/modl/connection.yml` on every server and proxy, created on the first start. Restart after editing.

```yaml
mode: embedded               # embedded, remote or behind-proxy

embedded:                    # only for mode: embedded
  port: 8080
  bind-address: "0.0.0.0"
  public-url: ""             # e.g. https://panel.example.com
  admin-email: ""            # claims the panel for this email
  trust-proxy-headers: false # true only behind a reverse proxy or tunnel
  trusted-proxy: "private"   # private, or loopback for a proxy or tunnel agent on this machine
  download-url: ""           # mirror of the modl GitHub releases; empty uses GitHub
  database:                  # empty url = built-in SQLite
    url: ""                  # jdbc:mysql://, jdbc:mariadb:// or jdbc:postgresql://, no credentials
    username: ""
    password: ""
    sqlite-native-dir: ""    # only when plugins/modl/data is mounted noexec
  ai:
    provider: ""             # see AI Moderation
    api-key: ""
    model: ""
    base-url: ""
  smtp:
    host: ""
    port: 587
    username: ""
    password: ""
    from: ""
    starttls: true

remote:                      # for mode: remote and behind-proxy
  url: "http://localhost:8080"
  api-key: ""                # remote: API key or "Standalone or proxy" key; behind-proxy: a "Behind a proxy" key
```

| Mode | Valid on | Notes |
|---|---|---|
| `embedded` | Paper, Folia, Spigot, Velocity, BungeeCord | Needs Java 21 |
| `remote` | all | Fails closed (no joins) until it connects |
| `behind-proxy` | Paper, Folia, Spigot behind a proxy | Players can join while it is offline; the proxy enforces |

A fresh Paper, Folia or Spigot server with proxy forwarding enabled creates the file with `mode: behind-proxy`. A server upgraded from the modl.gg plugin converts `boot.yml` into this file (kept as `boot.yml.old`) and starts with an empty panel.

`MODL_API_URL` or `-Dmodl.api.url` on a server overrides `remote.url`.

`embedded.download-url` is a mirror with the GitHub release layout (`download/v<version>/<file>` and `latest/download/modl-release.json`) for the first panel download, Update now and update checks. `MODL_UPDATE_BASE_URL` wins over it. Plain `http` works only for a mirror you set; files are checked against the plugin jar either way.

## Environment variables

For `modl-server.jar` and Docker, in `.env` in the working directory or in the environment; `.env` wins over the environment. Empty values count as unset. `MODL_MAX_UPLOAD_MB`, `MODL_MAX_STORAGE_GB`, `MODL_BACKUP_KEEP` and `MODL_BACKUP_HOUR` (Settings > System > Storage) and `MODL_IP_HISTORY_DAYS` and `MODL_NOTIFICATION_EXPIRY_DAYS` (Settings > System > Data retention) are also panel settings; a variable that is set wins and locks its field. `MODL_STORAGE` fixes the whole storage backend; `MODL_S3_*` apply only with it.

| Variable | Default | Meaning |
|---|---|---|
| `MODL_PUBLIC_URL` | | Panel address used in links. Fixed when set. |
| `MODL_PORT` | `8080` | HTTP port. With Docker, the published host port. |
| `MODL_BIND_ADDRESS` | `0.0.0.0` | Listen address. |
| `MODL_ADMIN_EMAIL` | | Owner email. Changing it moves ownership and signs the old owner out. |
| `MODL_SERVER_NAME` | `Minecraft Server` until setup | Server name. Fixed when set. |
| `MODL_API_KEY` | generated | Instance API key, 32 to 255 characters. Fixed when set. |
| `MODL_TRUST_PROXY_HEADERS` | `false` | Trust `X-Forwarded-*` from a reverse proxy or tunnel. |
| `MODL_TRUSTED_PROXY` | `private` | Who may send them: `private` (loopback and private addresses) or `loopback` ([Panel Port and Tunnels](Panel-Port-and-Tunnels)). |
| `MODL_DATA_DIR` | `./data` | Database, uploads, backups, update and temporary files. |
| `MODL_SETUP_CODE_FILE` | `<data>/setup-code.txt` | Where the setup code is saved. |
| `MODL_BACKUP_KEEP` | `7` | Nightly SQLite backups kept, 1 to 365. |
| `MODL_BACKUP_HOUR` | `4` | Hour of the nightly backup, 0 to 23, server time zone. |
| `MODL_DATABASE_URL` | | External database JDBC URL, no credentials. Wins over the database chosen in the panel. Empty = the panel's choice, else SQLite. |
| `MODL_DATABASE_USERNAME` | | External database user. |
| `MODL_DATABASE_PASSWORD` | | External database password. |
| `MODL_DATABASE_POOL_SIZE` | `10` | Connections, 1 to 100. |
| `MODL_SQLITE_NATIVE_DIR` | `<data>/tmp/sqlite` | Where the SQLite native library is unpacked. |
| `MODL_DATABASE_REOPEN` | | The move id from a `this database was moved to` refusal: lets modl use that database again ([Troubleshooting](Troubleshooting)). |
| `MODL_IP_HISTORY_DAYS` | `365` | Days an IP is kept after last seen, 1 to 3650. |
| `MODL_NOTIFICATION_EXPIRY_DAYS` | `90` | Days an undelivered player notification is kept, 1 to 3650. |
| `MODL_SMTP_HOST` | | SMTP server. Overrides the panel's email settings. |
| `MODL_SMTP_PORT` | `587` | 465 uses implicit TLS. |
| `MODL_SMTP_USERNAME` | | |
| `MODL_SMTP_PASSWORD` | | |
| `MODL_SMTP_FROM` | | Sender. Required unless the username is an email address. |
| `MODL_SMTP_STARTTLS` | `true` | Ignored on port 465. |
| `MODL_AI_PROVIDER` | | [AI Moderation](AI-Moderation) provider id. |
| `MODL_AI_API_KEY` | | |
| `MODL_AI_MODEL` | | Model id at the provider. |
| `MODL_AI_BASE_URL` | | Override the provider address. |
| `MODL_GEMINI_API_KEY` | | Gemini key, used when `MODL_AI_API_KEY` is empty and the provider is empty or `gemini`. |
| `MODL_STORAGE` | `local` | `local` or `s3`. |
| `MODL_S3_ENDPOINT` | AWS | S3 endpoint URL. |
| `MODL_S3_REGION` | `us-east-1` | |
| `MODL_S3_BUCKET` | | Required with `s3`. |
| `MODL_S3_ACCESS_KEY` | | Required with `s3`. |
| `MODL_S3_SECRET_KEY` | | Required with `s3`. |
| `MODL_S3_PUBLIC_URL` | endpoint/bucket | Public base URL of the bucket. |
| `MODL_MAX_UPLOAD_MB` | `100` | Largest single upload, 1 to 10240. |
| `MODL_IMPORT_MAX_UPLOAD_MB` | `500` | Largest import file from another plugin. |
| `MODL_MAX_STORAGE_GB` | `0` | Total upload storage, `0` = no limit. Set it on hosts with a disk quota. |
| `MODL_STORAGE_UNATTACHED_UPLOAD_TTL` | `PT24H` | How long an upload never submitted is kept (ISO 8601). |
| `MODL_UPDATE_CHECK` | panel setting | `false` sends no update request. |
| `MODL_PRE_UPDATE_BACKUP` | `true` | `false` skips the backup before an update. |
| `MODL_ALLOW_DOWNGRADE` | `false` | Let an older modl start on a database a newer one used, when the schema did not change. |
| `MODL_UPDATE_BASE_URL` | GitHub releases | Release mirror with the GitHub release layout. |
| `MODL_UPDATE_API_URL` | GitHub API | Release list mirror. |
| `MODL_IMAGE_TAG` | `3.0` | Docker only: image tag. To update, set the `<version>@sha256:<digest>` the panel shows ([Updating](Updating)). |
| `MODL_MEMORY_LIMIT` | no limit | Docker only: container memory limit, such as `2g`. |
| `TZ` | `UTC` | Docker only: container time zone. |

The switches `MODL_UPDATE_CHECK`, `MODL_PRE_UPDATE_BACKUP`, `MODL_ALLOW_DOWNGRADE`, `MODL_UPDATE_BASE_URL`, `MODL_UPDATE_API_URL` and `MODL_DATABASE_REOPEN` also reach the built-in panel as environment variables or `-D` properties of the Minecraft server. `update_checker.enabled: false` in `config.yml` turns checks off there.

The plugin and Docker set `MODL_LOG_DIR`, `MODL_INTERNAL_API_KEY` and `MODL_DEPLOYMENT` themselves; do not set them.

## Player addresses

Settings > Moderation > Player addresses (owner only) sets the IP lookup used for alt detection and VPN checks. Nothing is preselected.

| Lookup | Notes |
|---|---|
| No lookups | Player IPs never leave the panel. |
| proxycheck.io without a key | 100 new addresses a day per server address; then new addresses stay unknown until UTC midnight. |
| proxycheck.io, vpnapi.io, IPHub or ipapi.is with a key | The Test button checks a key with `1.1.1.1`; it cannot confirm a proxycheck.io key, which answers unknown keys like keyless queries. |

The panel sends each address once and refreshes it after 30 days.

Accounts are linked by shared public addresses. Private, loopback, carrier-grade NAT and VPN addresses never link, and an address used by more accounts than the shared address limit (default 10) is skipped. An alt-blocking ban follows only accounts that share an address the provider reported as not a VPN, checked whenever the ban is enforced; with lookups off, or for an address still unknown, it does not follow them.
