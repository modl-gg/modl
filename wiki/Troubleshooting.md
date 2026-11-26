# Troubleshooting

Check the console first, then `plugins/modl/server/logs/server.log` (built-in panel), `docker compose logs modl`, or the jar's output. `<...>` parts of the messages below vary.

## The server does not let players in

A server that cannot start modl refuses joins and repeats every minute:

```
modl is not running: <reason>
<remedy>
Players cannot join until this is fixed.
```

| Reason contains | Fix |
|---|---|
| `PacketEvents is not installed, but modl needs PacketEvents 2.14.0 or newer` | Install or update PacketEvents and restart. |
| `connection.yml: mode must be one of embedded, remote, behind-proxy`, or another `connection.yml:` line | Fix the named key and restart. |
| `remote.api-key is empty` | Paste the key ([Separate Panel](Separate-Panel#connect-servers), [Proxy Network](Proxy-Network)). |
| `Port 8080 is already in use, so the built-in panel cannot start` | Free the port or change `embedded.port`. |
| `embedded.port is <n>, the port players join on` | Give the panel a second port, or use a tunnel ([Panel Port and Tunnels](Panel-Port-and-Tunnels)). |
| `embedded.port is <n>, the RCON port of this server` | Give the panel another port, or change `rcon.port` in `server.properties`. |
| `is not allowed to listen on port` | Use a port of 1024 or above. |
| `The built-in panel needs Java 21 or newer` | Update Java, or run the panel apart with `mode: remote`. |
| `The built-in panel could not download modl-embedded-<version>.zip` | Allow outbound HTTPS to `github.com`, or download the URL in the message and put the file in `plugins/modl/server/`, or set `embedded.download-url` to a mirror. |
| `The built-in panel could not download <library> from Maven Central` | Allow outbound HTTPS to `repo1.maven.org`; it retries on the next start. |
| `The built-in panel could not save` | Free disk space or fix the permissions of the folder it names; it retries on the next start. |
| `modl-embedded-<version>.zip does not match this modl plugin jar` | The file is from another release or build, or damaged. Delete it to download it again, or use the file from the same release (for a jar you built, the same build's `dist/`). |
| `The modl release at <url> does not verify` or `does not list the modl-embedded-<version>.zip this plugin jar pins` | The mirror serves another or an altered release. Fix it or put the file in by hand. |
| `The built-in panel made no startup progress for 180 seconds` | Read `server.log`; usually a slow disk or network. Restart. |
| `Another process is running the built-in panel from` | Two servers share one `plugins/modl` folder; give each its own. |
| `Restart the Minecraft server to finish or roll back the update` | Restart the Minecraft server. |
| `The Cloudflare Tunnel needs the panel's https address` | Set `embedded.public-url` (`MODL_PUBLIC_URL`) to the tunnel's `https://` hostname ([Panel Port and Tunnels](Panel-Port-and-Tunnels#built-in-cloudflare-tunnel)). |
| `The Cloudflare Tunnel needs its token` or `is not a Cloudflare Tunnel token` | Copy only the token after `--token` from the Cloudflare dashboard. |

Players see `The moderation system on this server is not running`, `is being updated` (version mismatch), `cannot accept players until PacketEvents ... is installed`, or `Connection to panel failed` while the plugin is still connecting.

## Connecting to the panel

| Message | Fix |
|---|---|
| `Failed to connect to the modl server at <url>, retrying in <n>s (<cause>)` | Panel down or unreachable from this server; check `remote.url`, firewall, DNS. |
| `The modl server at <url> rejected the API key` | Copy the key again; it was regenerated or revoked. |
| `This plugin is modl <a>, but the modl server at <url> runs <b>.` | Run the same MAJOR.MINOR on both ([Updating](Updating)). |
| `remote.url <url> sends the API key without encryption to a public address` | Use `https://` ([HTTPS and Reverse Proxy](HTTPS-and-Reverse-Proxy)). |
| `[network] This server has no server key yet` | Create a Behind a proxy key, paste it into `remote.api-key`, and run `/modlserver reload`. Players can join meanwhile. |
| `[network] The modl server rejected the server key; retrying` | The key was revoked or belongs to another panel; create a new one. |
| `[network] Another server connected to the modl network as <name> with this server key, so this server was disconnected` | Two servers use the same key or name. Give every server its own key ([Proxy Network](Proxy-Network)). |
| `[network] ... its websocket could not be opened` | Your reverse proxy blocks websocket upgrades on `/v1/realtime/ws`. |
| `[server-features] server-name is empty in server-features.yml` | Set it to the server's name in the proxy config. |
| `[network] /proxycmd is disabled for this server's key` | Allow `/proxycmd` for that key in Settings > General > Minecraft servers. |
| `This server has a maximum heap of <n> MiB` | Give the server at least 2 GB (`-Xmx2G`). |
| `The built-in panel is listening on port <n>`, but the page does not open | The host does not forward that port ([Panel Port and Tunnels](Panel-Port-and-Tunnels)). |
| `This server could not open the panel at <url>` | Same cause, or a tunnel that is down. Networks that cannot reach their own public address show it even when the page works. |
| `This server could not open the panel at <url>. Nothing answers at that address on this machine` | The Panel URL is a local address: start the panel or the proxy in front of it, or fix the Panel URL. |
| `modl <a> is available (this plugin is <b>).` | An update is out ([Updating](Updating)). |
| `Players are connecting from <kind> addresses, so their real IPs are not being forwarded` | Turn on real-IP forwarding (PROXY protocol) in the tunnel or DDoS protection in front of your server or proxy. |

## Cloudflare Tunnel

| Message | Fix |
|---|---|
| `Cloudflare Tunnel connected: <url>` | Working. |
| `Cloudflare Tunnel is not connected after 1 minute` | A wrong or deleted token, or the host blocks outbound port 7844. Copy the token again or ask the host to allow it; modl keeps retrying. |
| `Cloudflare Tunnel cannot start: cloudflared <version> could not be downloaded from <url>` | Allow outbound HTTPS to `github.com`, or put the file from that URL in the folder the message names. |
| `Cloudflare Tunnel cannot start: cloudflared could not be started from <path>` | The data folder is mounted noexec; ask the host to allow running programs there. |
| `Cloudflare Tunnel cannot start: modl has no cloudflared build for <platform>` | Run cloudflared yourself ([Panel Port and Tunnels](Panel-Port-and-Tunnels#other-tunnels)). |

## Database

| Message | Fix |
|---|---|
| `The disk that holds the modl database is full, so modl cannot save changes` | Free disk space. Writes answer 503 `DATABASE_DISK_FULL` until then. |
| `The data directory does not allow executing native libraries` | Set `MODL_SQLITE_NATIVE_DIR` or `embedded.database.sqlite-native-dir` ([Database and Storage](Database-and-Storage)). |
| `Another modl server is using this database; waiting up to 150 seconds for it to stop.` | Stop the other server. After a crash the lock expires in about 2 minutes. |
| `modl lost its database lock and stopped serving to protect your data. Restart the server.` | Restart. Requests answer 503 `DATABASE_UNAVAILABLE` until then. |
| `The stopped modl server still has open database connections and lost its database lock` | Restart the server before another modl server uses this database. |
| `A restore of <backup> stopped before it finished replacing the uploaded files` | Stop modl and run the same restore again, or delete the named file to keep both as they are. |
| `The database setting <name> is <value>; set it to <expected>` | Change it on the database server. |
| `The database session is not in strict SQL mode` | Remove whatever overrides the SQL mode, such as a proxy. |
| `The database is in <mode> journal mode instead of WAL` | Restart modl. |
| `The database file does not return freed space to the disk` | Restore the newest nightly backup with `restore --input`. |
| `MODL_DATABASE_URL must not contain a user name or password` | Move them to `MODL_DATABASE_USERNAME` and `MODL_DATABASE_PASSWORD`. |
| `modl copied its data to <database>. Restart modl to finish the move; changes are paused until then.` | Restart modl. Changes answer 503 `DATABASE_MOVING` until then. |
| `modl copied its data to <database> and exits now; its container restart policy starts it again on the new database.` | Nothing with the compose file. A container without a restart policy stays stopped: start it again. |
| `Moving the database to <database> stopped: <reason>. modl keeps using <current>.` | Fix the reason, then press Move again in Settings > System > Database. `The copy does not hold every row` names the first tables whose counts differ. |
| `modl could not reach <database> (<reason>), so it keeps using <previous>.` | The move did not finish. Fix the connection, then move again. `another modl server is using it` means another server holds that database. |
| `<database> does not hold the copy modl made (<reason>), so modl keeps using <previous>.` | The new database was emptied, restored, or replaced before the restart. Move again. |
| `modl cannot start: this database was moved to <database> at <time>.` | modl moved its data away from this database, which no longer receives changes. Use the database it names, or move back in the panel. To use this old copy anyway, start once with the `MODL_DATABASE_REOPEN=<move id>` the line names, then remove it. |
| `modl cannot start: <database> does not hold the copy modl made, so the next start uses <previous> again.` | Restart modl; it returns to the previous database. Then move again. |
| `modl reopened this database, which was moved to <database> at <time>.` | Expected after `MODL_DATABASE_REOPEN`. Remove the variable. |
| `modl now uses <database>. The previous database was kept unchanged.` | Nothing; the move finished. |
| `Waiting up to <n> seconds for <database> to answer.` | The first start after a move waits for the new database: 90 seconds when it does not answer (start it), 130 seconds while another modl server holds it (after a crash its lock expires in about 2 minutes). |
| `The database is set by MODL_DATABASE_URL or connection.yml, so modl no longer uses the one chosen in the panel` | Expected when you set them. Remove them and move in the panel to choose there again. |
| `<data folder>/database-connection.json is not a valid modl database choice` | Restore the file, or remove it to use SQLite again ([Database and Storage](Database-and-Storage#moving-in-the-panel)). |
| `Restart the Minecraft server to finish moving the database.` | The built-in panel did not start again after a move. Restart the server. |
| `This database was last used by modl <newer>` | Start that version again, or restore an older backup ([Updating](Updating#going-back-after-a-completed-update)). |
| `An update from modl <a> to <b> is unfinished` | Start `<a>` to roll back or `<b>` to finish. |
| `The backup before updating modl from <a> to <b> failed` | Free disk space or fix `backups/`; nothing was changed. |
| `modl <b> has been starting for more than 15 minutes after the update from <a>` | It keeps running. Read the log; the line says how to go back ([Updating](Updating)). |

On Velocity 3 with Java 24 or newer, loading SQLite prints four harmless `WARNING:` lines about a restricted method.

## Storage

| Message | Fix |
|---|---|
| `Moving the uploaded files to <kind> storage stopped` | Press Retry in Settings > System > Storage. Files stay available meanwhile. |
| `Moved the uploaded files to <kind> storage (...). The old copies were kept` | Remove them in Settings > System > Storage once you no longer need them. |
| `MODL_STORAGE selects <kind> storage, but files uploaded earlier are still in the storage chosen in the panel` | They stay readable. To move them, remove `MODL_STORAGE`, move them in Settings > System > Storage, then set it again. |

## Sign-in

| Symptom | Fix |
|---|---|
| No email arrives | Without SMTP, codes are in the console. With SMTP, use the test in Settings > General > Email. |
| `Codes are not sent to <address> for the next <time>` | 20 wrong codes in 24 hours; wait, or sign in another way. |
| Setup code expired or locked | Restart the server that hosts the panel ([First Run](First-Run)). |
| Passkey option missing or failing | Use HTTPS on the panel's own domain. |
| `In-game panel login is disabled because this server does not verify player accounts.` | Offline-mode server or BungeeCord forwarding; see `panel_login.allow_offline_mode`. |
| Wrong links in game or email | Set the panel URL in Settings > General > Panel, or `public-url` / `MODL_PUBLIC_URL`. |

## In game

| Symptom | Fix |
|---|---|
| `You don't have permission to use this command.` | Link the Minecraft account to a staff account and check the role ([Permissions](Permissions)). |
| `You must verify your identity before using staff commands. Use /verify` | Staff 2FA is on; run `/verify`. |
| `GUI menus are not available on this proxy.` | Install PacketEvents on the proxy, or use `-p`. |
| `Punishment types not loaded.` | The plugin has not reached the panel yet; check the connection lines above. |
| Muted players still chat on a backend server | Install modl on every backend server ([Proxy Network](Proxy-Network#signed-chat-and-mutes)). |
| Bans apply only at next login | `realtime.enabled` is off, or the websocket is blocked. |
| Players hang on "Encrypting..." behind a proxy with LimboAPI | Keep `proxy.packet_interception: auto` (or `disabled`). |
| Discord messages do not arrive | Settings > General > Webhooks shows the last failure until a restart. Only Discord webhook URLs are accepted. |
| `proxycheck.io without a key reached its limit of 100 lookups today` | New addresses stay unknown until tomorrow (UTC); save a free key in Settings > Moderation > Player addresses. |
| `Refused the login of <uuid>: its player name does not follow the Minecraft name rule` | The name is not 1 to 16 letters, digits, or underscores (or a Bedrock prefix); fix the name source, such as an offline-mode or Bedrock plugin. |
| `<name> was refused before modl checked their bans, but another plugin allowed the login` | Another plugin overrides login refusals (whitelist, bans); remove that override. |
| `An earlier import's kept IP history is still being released` | A `standard` import is still releasing kept IP history; try again later. |
| `There is not enough free disk space to copy the <source> database` | Free disk space on the server; the copy is deleted after the import. |
| `<source> is running and keeps its database file open` | Remove that plugin's jar (keep its folder), restart, and import again ([Migrating from Other Plugins](Migrating-from-Other-Plugins)). |
| `Uploaded files are being moved to another storage` | Wait for the move in Settings > System > Storage, then import. |
| `modl is moving to another database` | Wait for the move in Settings > System > Database, then import. |
| `Missing locale: <key>` | A bundled message is missing; report it with the key. Your own `locale/<locale>.yml` cannot cause it. |

Still stuck: open an [issue](https://github.com/modl-gg/modl/issues) with the version, platform, mode, and log lines, without keys or emails. `/modl status` prints a report without them.
