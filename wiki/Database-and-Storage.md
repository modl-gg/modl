# Database and Storage

## Data folder

| Setup | Folder |
|---|---|
| Plugin | `plugins/modl/data/` |
| Jar | `./data/` or `MODL_DATA_DIR` |
| Docker | `/data` in the `modl-data` volume |

| Inside | Holds |
|---|---|
| `database/` | SQLite: `modl.db`, `modl.db-wal`, `modl.db-shm`, `modl.lock` |
| `storage/` | Uploads (evidence, replays, icons) with the hidden `storage/.meta/` |
| `backups/` | Nightly, pre-update and pre-restore backups ([Backups and Restore](Backups-and-Restore)) |
| `updates/` | Update state and downloaded versions ([Updating](Updating)) |
| `imports/`, `tmp/` | Import files and temporary files |
| `database-connection.json` | The database chosen in Settings > System > Database, with its password; readable only by modl's user |

## SQLite (default)

One modl server at a time can use a database; a second one refuses to start. Do not copy or edit files in `database/` while modl runs; use `backup`. Keep the folder on a local disk. If it is mounted `noexec`, set `embedded.database.sqlite-native-dir` or `MODL_SQLITE_NATIVE_DIR` to a folder that allows native libraries.

## MySQL, MariaDB, PostgreSQL

Tested: MySQL 8.4+, MariaDB 10.11+, PostgreSQL 15+.

1. Create a database and a user:
   ```sql
   -- MySQL / MariaDB
   CREATE DATABASE modl CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
   CREATE USER 'modl'@'%' IDENTIFIED BY '<password>';
   GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP, INDEX, REFERENCES ON modl.* TO 'modl'@'%';
   ```
   ```sql
   -- PostgreSQL
   CREATE ROLE modl LOGIN PASSWORD '<password>';
   CREATE DATABASE modl;
   \c modl
   CREATE SCHEMA modl AUTHORIZATION modl;
   ALTER ROLE modl IN DATABASE modl SET search_path = modl;
   ```
2. An existing panel moves to it in Settings > System > Database ([below](#moving-in-the-panel)) or offline with `copy-database` ([Moving and Removing](Moving-and-Removing#to-another-database)). Otherwise set the URL, user, and password: `embedded.database` in `connection.yml`, or `MODL_DATABASE_URL`, `MODL_DATABASE_USERNAME`, `MODL_DATABASE_PASSWORD`.
   ```
   jdbc:mysql://db.example.com:3306/modl?sslMode=VERIFY_IDENTITY
   jdbc:mariadb://db.example.com:3306/modl?sslMode=verify-full
   jdbc:postgresql://db.example.com:5432/modl?sslmode=verify-full
   ```
3. Start modl. It creates and upgrades its tables itself.

Rules:
- Never put credentials in the URL; modl refuses to start.
- Do not add `useAffectedRows=true`.
- Use TLS to a database on another machine.
- Connect directly or through a session-mode pooler, not a transaction-mode pooler.
- modl's tables, indexes and migration history (`modl_schema_history`) start with `modl_`; other tables in the database are left alone.
- modl writes no nightly backups for external databases; schedule `backup` or your database's own tools.

modl warns at start about each of these that is off:

| Database | Required |
|---|---|
| MySQL, MariaDB | `innodb_flush_log_at_trx_commit=1`; `sync_binlog=1` when the binary log is on; strict SQL mode (`STRICT_ALL_TABLES`) |
| PostgreSQL | `fsync=on`, `synchronous_commit=on`, `full_page_writes=on` |
| SQLite | WAL journal mode, `synchronous` 2 |

### Moving in the panel

In Settings > System > Database the owner picks SQLite, MySQL, MariaDB, or PostgreSQL, enters host, port, database, user, and password (TLS verifies the server certificate), presses Test, then Move.

- Test connects, checks the server version and the privileges above (with temporary `modl_probe_*` tables it drops again), takes the database's lock, and lists the durability settings that are off, and that a move off SQLite ends the nightly backups. It refuses the current database, one another modl server uses, and one that holds another panel's data. A database that holds an older copy of this panel (the one you moved away from, or an interrupted move) is replaced.
- Move pauses all changes, copies every row, compares the row count of every table in the target, then restarts onto it:

  | Install | Restart |
  |---|---|
  | Plugin (built-in panel) | the plugin restarts the built-in panel |
  | Jar | restarts itself |
  | Docker image | exits; the container's restart policy starts it on the new database (the compose file sets `restart: unless-stopped`; without a policy, start the container again) |
  | Anything else | asks you to confirm that you restart modl yourself, prints `Restart modl to finish the move` and stays paused until you restart |

- While paused, changes answer 503 `DATABASE_MOVING` with `Retry-After`, players cannot join (they are told modl is moving its data) and nobody can sign in; reading works. Imports and storage moves cannot start (409 `IMPORT_DATABASE_MOVING`, `STORAGE_DATABASE_MOVING`), and a move cannot start while one of them runs.
- If anything fails before the restart, the pause ends and modl keeps the current database; press Move again. If the new database does not answer within 90 seconds of the first start, or another modl server still holds it after 130 seconds (long enough for the lock of a crashed server to expire), modl returns to the previous one and says so; it also returns when the new database no longer holds the copy it made. The previous database is never deleted. modl marks it as moved, so it stops receiving changes and refuses to start on it later (`MODL_DATABASE_REOPEN` with the move id from that refusal overrides it). To go back, move back in the panel, which replaces the old copy and its mark.
- `embedded.database` or `MODL_DATABASE_URL` win over the choice and make the page read-only; modl then renames `database-connection.json` to `database-connection.superseded.json`, so removing them later starts on SQLite.

## Uploads

Local by default, in `storage/`. Settings > System > Storage shows the space used by replays, evidence, ticket and appeal files, article media, icons, and other files (refreshed every minute) against the total limit or, on local storage, the free disk. Limits are set there too (or `MODL_MAX_UPLOAD_MB`, `MODL_MAX_STORAGE_GB`): 100 MB per file and no total limit by default; set a total limit on hosts with a disk quota. Articles take at most 50 MB per file, icons and replays 10 MB. Anonymous uploads (tickets, appeals) are limited to 64 MB per network every 6 hours and 25 files per ticket or appeal. Writes stop when the disk would drop below 1 GB or 5% free.

### S3-compatible storage

In Settings > System > Storage, the owner enters, tests, and saves the S3 connection, then chooses Move uploads to S3. Or set `MODL_STORAGE=s3` and the `MODL_S3_*` variables ([Configuration](Configuration)); the page is then read-only.

- A move copies and checks every file in the background. Files stay available from the old place until it finishes; new uploads go to the new place at once. It finishes once every upload started before the move has landed or expired, at least one minute after the switch. If the copy stops, the page offers Retry the copy and Move uploads back.
- The old place keeps its copies: they are never served, deletes still reach them, and the owner removes them with Remove old copies. After 4 old places, remove them before the next move.
- With `MODL_STORAGE` set, files left in the storage chosen in the panel stay readable; the console says how to move them.
- The provider must support conditional writes (`If-None-Match: *`); AWS S3, MinIO, and Cloudflare R2 do.
- Browsers and servers upload straight to the bucket, so its CORS rules must allow `PUT` from the panel's origin with the headers `Content-Type` and `If-None-Match`.
- Use one bucket per modl installation; keys start with `modl_instance`. modl marks the bucket and refuses one that another installation uses. The secret key is write-only.
- Never make `modl_instance/replays/` public-read. Replays are served only through the panel and share links; a public-read bucket policy must exclude that prefix.

## Retention

| Data | Default | Set in |
|---|---|---|
| Chat and command logs | 30 days (1 to 3650) | Settings > System > Data retention |
| Audit log | 365 days (30 to 3650) | Settings > System > Data retention |
| Replays | 10 days (1 to 365, or until deleted) | Settings > System > Data retention |
| Player IP addresses | 365 days after last seen (1 to 3650) | Settings > System > Data retention, or `MODL_IP_HISTORY_DAYS` |
| Undelivered player notifications | 90 days (1 to 3650) | Settings > System > Data retention, or `MODL_NOTIFICATION_EXPIRY_DAYS` |
| IP lookup results | refreshed after 30 days; at most 100,000 addresses, never longer than IP history | fixed |
| Uploads never submitted | 24 hours | `MODL_STORAGE_UNATTACHED_UPLOAD_TTL` |

Deletion runs every hour. Replays attached to an open ticket, or to the ticket of a punishment in force or not started, are kept until both end, and replays with an open share link until the link is revoked or expires. Chat reports keep their own copy of the messages. Imported IP history kept by an import's "keep" choice stays until a live login from that address or a later import with "standard" ([Importing](Importing)).
