# Migrating from Other Plugins

[![Video: Import from LiteBans](https://img.youtube.com/vi/ZgZylJc8abM/maxresdefault.jpg)](https://youtu.be/ZgZylJc8abM)

Back up first ([Backups and Restore](Backups-and-Restore)). Imported bans and mutes take effect as they are imported.

## Supported plugins

| Source | `<source>` | Tested versions | Storage read |
|---|---|---|---|
| LiteBans | `litebans` | 2.x | Through LiteBans, which must be running (any of its databases) |
| AdvancedBan | `advancedban` | 2.3.0 | `plugins/AdvancedBan`: HSQLDB `data/storage` or MySQL (`config.yml`, `MySQL.yml`) |
| LibertyBans | `libertybans` | 1.1.4 | `plugins/LibertyBans/sql.yml`: HSQLDB, MariaDB, MySQL, PostgreSQL, or CockroachDB |
| BanManager | `banmanager` | 7.x | `plugins/BanManager/config.yml` (`databases.local`, its table names): H2 or MySQL/MariaDB |
| BanSystem | `bansystem` | 3.x | `plugins/BanSystem`: SQLite `database.db` or MySQL |
| Vanilla and EssentialsX | `vanilla` | Minecraft 1.8 to 26.x, EssentialsX 2.x | `banned-players.json`, `banned-ips.json`, and `plugins/Essentials/userdata` (mutes, last name, and address) |

- Run it on the server whose `plugins` folder has the data (on a network, the proxy that runs the modl core). For `vanilla` on a network, copy the two ban lists and `plugins/Essentials/userdata` from a backend server to the proxy first.
- On a proxy: LibertyBans, BanManager (its Velocity or BungeeCord jar), and BanSystem work on Velocity and BungeeCord, AdvancedBan only on BungeeCord (it has no Velocity version). LibertyBans 1.1.4 does not start on Velocity 4, and BanSystem 3.1 on a proxy needs Floodgate.
- Remote databases are read with the credentials and SSL settings in the plugin's own config, read-only.
- While LibertyBans or BanManager runs, it is read through its own database connection. Embedded database files (HSQLDB, H2) of a stopped plugin, and BanSystem's SQLite `database.db` also while BanSystem runs, are read from a copy in `plugins/modl/import/` that is deleted afterwards; the copy needs that much free disk space. If BanSystem writes while it is copied, the import stops and asks to run it again. AdvancedBan with HSQLDB must not be running: remove its jar (keep its folder) and restart.
- The database driver an import needs is downloaded from Maven Central into `plugins/modl/lib/` and checked against a pinned checksum; it is kept for later imports and replaced when a modl update pins another version. The SQLite driver unpacks its native library into `plugins/modl/import/native/`, which is deleted when the import ends.

## Run it

1. Finish the modl setup.
2. List the sources: `modl import` shows the sources found on this server.
3. Preview: `modl import <source>` shows what it found and imports nothing.
4. Confirm within 60 seconds: `modl import <source> confirm <widen|skip> <keep|standard>`.
5. Check the panel: imported punishments are on each player's page once the console says the import completed.

| Choice | Meaning |
|---|---|
| `widen` | IP-wide and server-scoped punishments apply to the account on every server; IP bans made for a player also block its alts. |
| `skip` | They are left out. |
| `keep` | Imported IP history is kept regardless of `MODL_IP_HISTORY_DAYS`. |
| `standard` | IP history follows `MODL_IP_HISTORY_DAYS`; this also ends an earlier `keep`. |

`modl import status` shows progress or the last result; `modl import cancel` stops it at once and keeps what was imported. Only the local console can run these; players, RCON, command blocks, and `/proxycmd` cannot.

## What is imported

| Source data | In modl |
|---|---|
| Ban, temporary ban | Manual Ban with its duration |
| Mute, temporary mute | Manual Mute with its duration |
| Kick | Kick (history) |
| Warning | Player note of kind warning; removed warnings are left out |
| Note (AdvancedBan, BanManager) | Player note; AdvancedBan notes removed with `/unnote` are left out |
| IP ban with a player | That player's ban, IP-wide (`widen` adds alt blocking) |
| IP ban with only an address | Ban of the only account that used that address (LibertyBans and BanManager: its address history; vanilla: the last address in the EssentialsX user files), IP-wide without alt blocking; otherwise left out |
| Removed by staff, with who and when (LiteBans, BanManager, BanSystem) | Manual pardon by that name, with the unban reason |
| Ended early without a record (AdvancedBan, LibertyBans) | Pardon by System |
| Expired | Expired, not pardoned |
| Issuer | Its name; the console as `Console` |
| Names and addresses | Username and IP history |
| A player stored by name only (AdvancedBan in offline mode) | The UUID from `usercache.json`; on an offline-mode server without a proxy, the offline UUID; otherwise left out |

Not imported: IP range bans, name bans and the global database (BanManager), punishments without a player account, and EssentialsX mutes that already ended. Limit: 1,000,000 players.

- Running it again only adds records; it does not change or lift earlier imports. After a `standard` import, kept IP history is released in the background; a new import waits until that is done.
- One import runs at a time, and none starts while uploads move to another storage or the database moves.
- With `mode: remote` the export (it contains IP addresses) goes to `remote.url`: use HTTPS, and let any proxy or CDN accept bodies up to `MODL_IMPORT_MAX_UPLOAD_MB` (500 MB).
- If the Minecraft server stops before the panel received the export, run it again. After that the panel finishes on its own, also after a restart.
- BanSystem stores local times without a zone; they are read in the server's time zone.
