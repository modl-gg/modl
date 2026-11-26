# Updating

## Version rule

The panel and every plugin must run the same MAJOR.MINOR version (3.0.x with 3.0.y is fine). A plugin on another line is refused: players cannot join a standalone server or proxy until both match, and a server behind a proxy keeps its staff tools off. Update the panel first, then every plugin.

Back up before a MAJOR.MINOR update ([Backups and Restore](Backups-and-Restore)).

## How

| Setup | Patch release (same line) | New MAJOR.MINOR |
|---|---|---|
| Docker | Set `MODL_IMAGE_TAG=<version>@sha256:<digest>` in `.env` (shown in Settings > System > Updates and in the console), then `docker compose pull && docker compose up -d` | same |
| Docker built from source | `git pull`, then `docker compose -f docker-compose.yml -f docker-compose.build.yml up -d --build` | same |
| Jar | Settings > System > Updates > Update now, or `java -jar modl-server.jar update` | same, or replace the jar and restart |
| Built-in panel (plugin) | Settings > System > Updates > Update now | Replace the plugin jar on every server and restart; the new jar downloads its own `modl-embedded-<version>.zip` (or takes it from `plugins/modl/server/`) |
| Plugin on any server | Replace `modl-<version>.jar` and restart | same |

- Update now on a built-in panel restarts only the panel; players cannot join for a few seconds and online players stay. If the console asks for it, restart the Minecraft server to finish or roll back.
- `java -jar modl-server.jar update` downloads and verifies the release; start modl as usual to finish. Flags: `--check`, `--version <v>`, `--prerelease`, `--acknowledge-plugin-update` (needed for another MAJOR.MINOR). A jar updated this way runs under a small supervisor, so two JVMs run; count `-Xms` twice.
- Release candidates (versions such as `3.0.0-rc.2`) are offered only with Settings > System > Updates > Include pre-releases on (Super Admin), or with `--prerelease` on the `update` command; Docker shows the `MODL_IMAGE_TAG` line to set.
- The Docker digest is the one the signed release names, so a moved tag cannot change what you run.
- Releases are signed. Only a release signed with a key built into your modl installs; a build without keys shows no updates, and you replace files by hand.
- Update checks: a conditional check of the signed release file about every 12 hours, identified only by `User-Agent: modl/<version>`. Turn them off in Settings > System > Updates or with `MODL_UPDATE_CHECK=false`.

## What happens on the first start of a new version

1. modl backs up the database to `backups/pre-update-<...>.zip` (keeps 3). `MODL_PRE_UPDATE_BACKUP=false` skips it.
2. It upgrades the database schema. The panel answers 503 `UPDATE_STARTING` until the start completes.
3. If the new version fails to start or is not healthy within 15 minutes, a supervised update (Update now, `update` command) returns to the previous version and restores that backup by itself. Docker and other unsupervised starts keep running and print once how to go back. After a manual update that has not finished (`updates/pending.json` exists), starting the previous version restores the pre-update backup by itself.

A failed pre-update backup aborts the update and keeps the old data; free disk space or fix `backups/`.

## Going back after a completed update

A database used by a newer version refuses an older one. Either start the newer version again, or restore a backup made by the older version into an empty database with the older version's `restore` ([Backups and Restore](Backups-and-Restore)). `MODL_ALLOW_DOWNGRADE=true` lets an older version start when the schema did not change.

Files in `data/updates/`: `pending.json` (an update in progress), `committed.json`, `last-failure.json`, `versions/` (downloaded releases) and `run/` (verified copies in use).
