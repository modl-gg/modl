# Backups and Restore

## Automatic backups

| Backup | When | Kept | Contains |
|---|---|---|---|
| `modl-backup-<date>-<time>.zip` | daily at 04:00 server time, SQLite only | 7 | database |
| `pre-update-<...>.zip` | before a new version first starts | 3 | database |
| `pre-restore-<date>-<time>.zip` | before a restore changes anything | 3 | database |

All are in `backups/` in the data folder. Set the nightly hour (0 to 23) and count (1 to 365) in Settings > System > Storage, or with `MODL_BACKUP_HOUR` and `MODL_BACKUP_KEEP`; with Docker the hour follows `TZ`. A nightly backup is written only if the disk keeps at least 1 GB or 5% free afterwards; to make room modl deletes the oldest nightly ones, always keeping the newest. Uploads are not in automatic backups.

External databases get no nightly backups: schedule `backup` below or your database's dump tool, and prune old files yourself. Copy backups off the machine; they contain password hashes and secrets.

## By hand

The commands are part of `modl-server-<version>.jar`. Run them as the user modl runs as, from the folder you start it in (`.env` is read).

```bash
java -jar modl-server-<version>.jar backup --output modl-backup.zip --with-storage
java -jar modl-server-<version>.jar restore --input modl-backup.zip
```

- `backup` may run while modl runs. `--with-storage` adds uploads; it refuses while uploads are being moved or when they are on S3 (back up the bucket with your provider's tools).
- `restore` needs modl stopped. It checks the whole file, saves a `pre-restore-` backup, then replaces modl's data (only modl's tables on an external database). It accepts nightly and `backup` files from this or an older modl 3 version, into any supported database. `--database-only` skips the uploads (required with S3).
- If a restore stops part way, run the same restore again before starting modl.
- Backups leave out the mark of a database move, so a restored backup starts normally. While a move made in the panel waits for its restart, `backup`, `restore` and `copy-database` refuse: start modl once, then run them.
- When you copy `storage/` with other tools, copy it whole, including `storage/.meta/`.

### Plugin with a built-in panel

Download `modl-server-<version>.jar` of the same version and run it from the Minecraft server folder:

```bash
MODL_DATA_DIR=plugins/modl/data java -jar modl-server-<version>.jar backup --output modl-backup.zip --with-storage
```

The commands do not read `connection.yml`; they follow the database chosen in the panel. If `embedded.database` is set, also pass `MODL_DATABASE_URL`, `MODL_DATABASE_USERNAME` and `MODL_DATABASE_PASSWORD` (and `MODL_SQLITE_NATIVE_DIR` if set). Stop the Minecraft server before `restore`.

### Docker

```bash
docker compose exec modl java -jar /app/modl-server.jar backup --output /data/backups/modl-backup.zip --with-storage
docker compose cp modl:/data/backups/modl-backup.zip .
```

```bash
docker compose stop modl
docker compose run --rm modl restore --input /data/backups/modl-backup.zip
docker compose start modl
```

To restore a file from the host, copy it into the volume first (`docker compose cp modl-backup.zip modl:/data/backups/` while the container runs).
