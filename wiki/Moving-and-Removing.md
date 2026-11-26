# Moving and Removing

Stop modl before copying data, and back up first ([Backups and Restore](Backups-and-Restore)).

## To another machine

1. Stop modl (the Minecraft server, the container or the jar).
2. Copy the whole data folder, including `storage/.meta/`, or make a `backup --with-storage` and `restore` it on the new machine.
3. Copy `connection.yml` or `.env`.
4. Start modl there. If the address changed, update `public-url` or `MODL_PUBLIC_URL`, DNS, and `remote.url` on every server. Passkeys of an old domain stop working.

S3 uploads stay in the bucket; point the new installation at the same bucket.

## Built-in panel to a separate panel

1. Stop the Minecraft server.
2. Copy `plugins/modl/data/` to the new data folder (`./data`, `MODL_DATA_DIR`, or restore a backup into the Docker volume).
3. Move what you set in `embedded` to the environment: `port` to `MODL_PORT`, `bind-address` to `MODL_BIND_ADDRESS`, `public-url` to `MODL_PUBLIC_URL`, `admin-email` to `MODL_ADMIN_EMAIL`, `trust-proxy-headers` and `trusted-proxy` to `MODL_TRUST_PROXY_HEADERS` and `MODL_TRUSTED_PROXY`, `database` to `MODL_DATABASE_*`, `smtp` to `MODL_SMTP_*`, `ai` to `MODL_AI_*`, `tunnel` to `MODL_TUNNEL` and `MODL_TUNNEL_TOKEN`. Panel settings move with the data.
4. Start the separate panel.
5. On the server, set `mode: remote` with `remote.url` and the API key (unchanged, Settings > General > Minecraft servers), and start it. `plugins/modl/server/` (the built-in panel and its libraries) can then be deleted.

The reverse works the same way: copy the data folder into `plugins/modl/data/`, move the settings into `embedded`, set `mode: embedded`.

## To another database

While modl runs, use Settings > System > Database ([Database and Storage](Database-and-Storage#moving-in-the-panel)). Offline, `copy-database` copies everything from the configured database into another one. The target must have no modl tables. Stop modl first; changes made during the copy are lost.

```bash
read -rs MODL_TARGET_PASSWORD && export MODL_TARGET_PASSWORD
java -jar modl-server-<version>.jar copy-database --to "jdbc:postgresql://db.example.com:5432/modl?sslmode=verify-full" --username modl --password-env MODL_TARGET_PASSWORD
```

Then set `embedded.database` or the `MODL_DATABASE_*` variables to the target and start modl. Back to SQLite: move the data folder's `database/` aside (it holds the old SQLite copy, and `copy-database` refuses a target with modl tables), run `copy-database --to sqlite` while the external database is still configured, then remove the database settings. If the external database was chosen in the panel, move back in Settings > System > Database instead; it replaces the old copy. Uploads stay where they are.

Docker:

```bash
docker compose stop modl
docker compose run --rm -e MODL_TARGET_PASSWORD modl copy-database --to "jdbc:postgresql://db.example.com:5432/modl?sslmode=verify-full" --username modl --password-env MODL_TARGET_PASSWORD
```

For a built-in panel run it like the backup command, with `MODL_DATA_DIR=plugins/modl/data` ([Backups and Restore](Backups-and-Restore#plugin-with-a-built-in-panel)).

## Removing

One server: revoke its key in Settings > General > Minecraft servers, stop it, delete `modl-<version>.jar` and `plugins/modl/`. A server behind a proxy without modl no longer blocks signed chat of muted players.

Everything:

1. Back up if you may want the data later.
2. Stop every server and delete `modl-<version>.jar` and `plugins/modl/` from each.
3. Separate panel: `docker compose down -v` (deletes the volume), or delete the jar and its data folder.
4. External database: drop modl's database, or its tables starting with `modl_` (including `modl_schema_history`).
5. S3: delete the objects under `modl_instance/`.

Bans and mutes stop being enforced once modl is gone.

To start over instead: stop modl, back up, delete the data folder (or the Docker volume), and start again.
