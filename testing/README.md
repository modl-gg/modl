# modl testing harness

End-to-end harness for modl. It boots real Minecraft servers in Docker with the plugin built from the modl repository, drives them with Mineflayer bots, talks to the panel API, and runs scenarios that report pass or fail with evidence. The same functions are available as a CLI and as the `modl-minecraft` MCP server.

## Requirements

- Docker with Compose v2.24 or newer (the harness only binds `127.0.0.1`).
- Node 24 or newer.
- The harness lives in the modl repository's `testing/` folder and builds against that checkout (set `MODL_REPO_ROOT` to use another checkout).
- The product jars: run `./gradlew dist` in the repository. This writes `dist/modl-<version>.jar`, `dist/modl-server-<version>.jar` and `dist/modl-embedded-<version>.zip` for the version in the repository's `gradle.properties`; the harness uses exactly those files and stops with an error when they are missing. Point `MODL_TEST_PLUGIN_JAR`, `MODL_TEST_SERVER_JAR` or `MODL_TEST_EMBEDDED_ARCHIVE` at other files to override; the server jar's folder is bind-mounted into the modl server container, so keep overrides in a folder Docker shares (the repository is; on macOS `/private/tmp` is not, and the container then exits with `Invalid or corrupt jarfile`). The plugin jar does not contain the built-in panel: every server that hosts it gets the archive as a drop-in in `plugins/modl/server/` (unreleased builds have no GitHub release to download from), and with `--update-fixture` the plugin downloads the base test release from the release mirror instead.
- Internet on first use: Paper, Velocity, BungeeCord and PacketEvents are downloaded into `.cache/` and verified against pinned sha256 values; Paper is pre-patched once in a throwaway container.
- Outbound HTTPS from the containers to Maven Central (`repo1.maven.org`): the plugin downloads its runtime libraries into `plugins/modl/lib/` on every server, and the first boot of a fresh server folder that hosts the built-in panel installs the panel from `plugins/modl/server/modl-embedded-<version>.zip` and downloads the panel's libraries (about 100 MB) into `plugins/modl/server/lib/`. Later boots on the same line reuse them; `--fresh` downloads them again.
- The servers run in Eclipse Temurin JRE images whose Java version follows the platform line (see below); Docker pulls them on first use.

```bash
cd testing
npm ci
cp .env.example .env    # optional, every value has a default
npm run doctor
```

Set `MODL_TEST_ARTIFACT_SEED_DIR` to a folder that already holds the pinned jars to skip the downloads (files are copied only when their sha256 matches). The BungeeCord build is pinned to the SpigotMC Jenkins; if that build is ever pruned there, seed the jar from that folder.

## Platform lines

The product supports Minecraft 1.8 to the latest release, Velocity 3 and 4, and Java 21 or newer for the built-in panel. `--line` picks which of those the servers run (default `latest`); `npm run cli -- lines` prints the pins, topologies and the scenarios that apply to each line.

| Line | Paper | Velocity | BungeeCord | Bots | Java | Topologies |
|---|---|---|---|---|---|---|
| `latest` | 26.1.2 build 74 | 4.3.1 build 45 | build 2111 | 26.1.2 | 25 | all |
| `1.21` | 1.21.11 build 132 | 3.5.1 build 615 | build 2111 | 1.21.11 | 21 | all |
| `legacy` | 1.8.8 build 445 | none | build 2111 | 1.8.8 | 8 for Paper, 21 for the proxy and the modl server | `network-bungee`, `remote` |
| `folia` | Folia 26.1.2 build 8 instead of Paper | 4.3.1 build 45 | build 2111 | 26.1.2 | 25 | all |

Every line uses PacketEvents 2.14.0 (its Spigot jar declares `folia-supported`, so the same jar serves Folia). A container runs on the line's Java, raised to what its server needs: Java 21 for a server that hosts the built-in panel and for the modl server, Java 25 for Paper 26.1 and Velocity 4. `--java <version>` asks for a newer Java on every container (it cannot go below the line's Paper requirement). On `legacy`, `standalone` is not offered because Java 8 cannot host the built-in panel (the `embedded-needs-java-21` scenario checks that refusal on `remote`), and `network` is not offered because 1.8 backends have no Velocity modern forwarding; 1.8 networks run behind BungeeCord, which `network-bungee` covers. Each line keeps its own worlds and modl data in `runtime/<topology>/<line>/`, so `down --keep-data` and a later `up` on the same line pick them up again; starting another line stops the running one first. The one exception is `remote --backend compose`: the repository stack's Docker volume (modl data, database included) belongs to the topology, so every line shares it and `--fresh` on any line wipes it. A runtime folder written before the SQL storage cannot be reused: start it once with `--fresh`.

`folia` runs Folia, Paper's regionized fork, wherever the other lines run Paper: the standalone server, the servers behind both proxies, and the remote Paper. It is a line rather than a separate server switch because Folia only exists for recent Minecraft versions, so it is one more set of pins (server build, Velocity, Java, bots) and keeps its own runtime folder like any line. Folia is pinned to the newest build of the Minecraft version that `latest` uses: the newest Folia (26.3) is newer than any version Mineflayer 4.39 can join. What differs on Folia, and how the scenarios handle it:

- Folia has no `/reload` (it disables every plugin and then throws `UnsupportedOperationException`), so `reload-twice` and `member-reload-resyncs-freeze` do not apply, and the `/reload` steps of `hosted-import`, `cloudflare-tunnel`, and `panel-port-in-use` are skipped with a note (`update-embedded` restarts the server instead).
- Folia removes the `/item` and `/data` commands, so the replay scene gives the armor to the scene player and the bot puts it on (the same on every modern line).
- `/mspt` reports no region time on Folia, so `replay-load` samples the `/tps` health report every round: the average utilisation times 50 ms is the comparable tick time, and the average lowest region TPS is the TPS.
- Plugins whose `plugin.yml` lacks `folia-supported` do not load on Folia: AdvancedBan, BanManager, BanSystem, and EssentialsX (vanilla mutes) imports run on Folia only where the plugin sits on the proxy; LibertyBans runs on Folia itself.

The pins live in `src/lib/artifacts.ts` and the lines in `src/lib/lines.ts`. `latest` stays on the newest Minecraft version that the installed Mineflayer can join, even when PacketEvents already supports a newer one (Mineflayer 4.39 speaks up to 26.1, protocol 775, which Paper 26.1.2 shares). Bots use the Minecraft version of the line's Paper build; `MODL_TEST_BOT_VERSION` overrides it on every line.

## Topologies

| Topology | Servers | Minecraft | Panel | Console |
|---|---|---|---|---|
| `standalone` | Paper + PacketEvents + modl `mode: embedded` | `127.0.0.1:25700` | `http://127.0.0.1:18123` | `paper` (RCON 25750) |
| `network` | Velocity + modl embedded (+ PacketEvents), Paper `lobby` and `survival` with `mode: behind-proxy` (one server key each, `remote.url: http://proxy:8080`), modern forwarding with a generated secret | `127.0.0.1:25701` (proxy) | `http://127.0.0.1:18124` | `proxy`, `lobby` (RCON 25751), `survival` (RCON 25752) |
| `network-bungee` | the same network on BungeeCord (build 2111) + PacketEvents for BungeeCord, BungeeCord IP forwarding (`settings.bungeecord: true` on the servers behind it) | `127.0.0.1:25701` (proxy) | `http://127.0.0.1:18124` | `proxy`, `lobby` (RCON 25751), `survival` (RCON 25752) |
| `remote` | modl server from `dist/modl-server-<v>.jar` (or `--backend compose`: the repository Dockerfile and compose stack), Paper with `mode: remote` | `127.0.0.1:25702` | `http://127.0.0.1:18125` | `paper` (RCON 25753), `backend` |

```bash
npm run up -- standalone
npm run up -- network
npm run up -- network-bungee
npm run up -- remote
npm run up -- remote --backend compose                     # builds the repository image first (several minutes)
npm run up -- network --without-proxy-packetevents
npm run up -- standalone --admin-email owner@example.com   # preset the owner instead of claiming with the setup code
npm run up -- network --fresh --defer-setup                # leave the claim to a scenario or the panel (see below)
npm run up -- standalone --line 1.21                       # Paper 1.21.11 on Java 21
npm run up -- remote --line legacy                         # Paper 1.8.8 on Java 8 against the modl server on Java 21
npm run up -- standalone --java 27                         # every container on eclipse-temurin:27-jre
npm run up -- standalone --fresh                           # wipe this topology's data on this line first
npm run up -- standalone --real-client                     # add ViaVersion and ViaBackwards so a newer real client can join
npm run status
npm run down                                               # add --keep-data to keep worlds, modl data and Docker volumes
```

One topology runs at a time. `up` refuses to start when one of its host ports is already in use. It writes `connection.yml`, `config.yml` (`panel_login.allow_offline_mode: true`, realtime on) and `server-features.yml` for every server, seeded from the files packaged in the plugin jar, starts the containers in order and waits for the ready line, the modl boot line and a healthy panel (the wait for the boot line has no fixed deadline while the first boot downloads the panel's libraries; it fails after 240 seconds without a download or panel line). By default the instance stays unclaimed on `standalone`, so the setup code printed to the console and saved in the setup code file is exercised; `network`, `network-bungee` and `remote` claim it during `up` (with the printed code, then they complete the setup wizard so the panel opens normally; when the printed code is invalid, expired or locked, the harness restarts the server that hosts the panel, which prints a new one, and retries once) because the servers behind the proxy need a server key and the remote Paper needs the API key from the panel. Completing the wizard chooses replays off and the anticheat integration off; the scenarios that need replays (`replay`, `replay-load`, `network-members`) turn them on through the panel and off again. `--defer-setup` leaves the claim, or the preset owner's first sign-in, to a scenario or to you in the panel: the servers behind the proxy then stay idle (players can join, staff tools are off) and the remote Paper is not started until `npm run cli -- connect` (or the first scenario that needs them) gets an owner session and connects them. On a fresh network, the servers behind the proxy start without a `connection.yml` so the plugin detects proxy forwarding and writes `mode: behind-proxy` itself; the harness then creates a server key named after each server (`POST /v1/panel/settings/server-keys`, scope behind proxy), fills in `remote.url` and `remote.api-key` and restarts them, like an owner would, and waits for `[network] Connected to the modl network at`.

`down` stops every server through its console (`stop` / `end`), archives the console logs to `reports/logs/`, removes containers and networks (and volumes, unless `--keep-data`), stops the bot daemon, and reports leftovers (`clean: true` means nothing is left and no harness port is bound).

Minecraft containers follow a Pterodactyl egg: non-root (your uid, with a `container` entry in a mounted `/etc/passwd` and `/etc/group`, as Pterodactyl images have; Java 27's `UnixSystem` refuses a uid without one), read-only root filesystem with writable `/data` (the server directory) and a 100 MB executable tmpfs `/tmp` (as Wings mounts it), `--memory` limit (3g for servers hosting the panel, 2g otherwise) without swap, PID limit 512, all capabilities dropped, and the `SERVER_IP`, `SERVER_PORT` and `SERVER_MEMORY` variables Pterodactyl exports (the embedded panel must ignore them). Bots are offline-mode players; a player's identity is its offline UUID (`npm run cli -- uuid <name>`).

## Scenarios

The replay scenarios import the generated protobuf code from `../proto/gen/ts` and `@bufbuild/protobuf` from `../node_modules` (paths in `tsconfig.json`), so run `npm ci` at the repository root first.

```bash
npm run cli -- list
npm run scenario -- smoke ban-kick
npm run scenario -- all                              # every scenario for the running topology
npm run scenario -- all --up standalone --down       # boot, run, tear down (also on failure or Ctrl+C)
npm run scenario -- smoke --up standalone --java 27 --down
npm run scenario -- all --up remote --line legacy --down
```

Scenarios run on every line that offers their topology unless the table names lines. `all` runs the scenarios that apply to the running topology, line and Java; a named scenario that does not apply is reported as skipped with the reason (`embedded-needs-java-21` also needs Paper below Java 21, so it skips under `--java 21`).

The `setup-*` scenarios need a fresh instance in a given setup phase (`setup-wizard` and `setup-no-console`: unclaimed without `--admin-email`; `setup-config-claim`: the preset owner has not signed in yet) and are skipped with the reason otherwise. They run first in `all`, so one fresh `--defer-setup` boot covers `setup-wizard` (or `setup-config-claim` with `--admin-email`); `setup-no-console` needs a boot of its own because the wizard claims the instance. Any other scenario first connects the servers that a deferred setup left waiting.

| Scenario | Topologies | Checks |
|---|---|---|
| `setup-wizard` | standalone, network, remote | fresh unclaimed instance: the console and the code file show the same code, 5 wrong codes lock the address out (the right code then gets `SETUP_CODE_LOCKED`), a restart of the server that hosts the panel prints a new code that claims the instance with the owner email, the wizard refuses to finish (`SETUP_SIGN_IN_PENDING`) or skip the sign-in step (`SETUP_SIGN_IN_REQUIRED`) until the owner confirms it, then every wizard step through the API: server name, panel URL check, test mail to a closed port, connected servers list, `/modl link`, staff invitation, the replays step refused (`SETUP_REPLAYS_NOT_CHOSEN`) until the replay settings are saved, step states, completion; a second claim is `SETUP_ALREADY_CLAIMED` and the code file is gone |
| `setup-no-console` | standalone, network, remote | fresh unclaimed instance claimed with only the code from the setup code file at its documented location (`plugins/modl/setup-code.txt`, or `setup-code.txt` in the data directory); the file is deleted afterwards |
| `setup-config-claim` | standalone, network, remote | preset owner (`--admin-email`): the phase is owner sign-in, nobody can claim, the owner signs in with the owner sign-in code from the file, the code is used up |
| `smoke` | all | join, tab list, chat; panel HTML, health and setup status |
| `panel-link-login` | standalone, network, remote | owner session from the console setup code (or the owner sign-in code, or a console login code), link code from the panel, `/modl link <code>`, single-use code, `/modl panel` link logs in through `/v1/panel/auth/link`, single-use link |
| `ban-kick` | standalone, network, remote | console ban kicks the online player over realtime, rejoin denied |
| `mute-chat` | standalone, network, remote | console mute blocks chat for other players, unmute restores it |
| `gui` | standalone, network, network-bungee, remote | `/standing`, `/report` and `/punish` menus open, render items and react to clicks |
| `report` | standalone, network, remote | report through the menus, ticket id in chat, the linked owner online in game gets the report alert, ticket visible through the panel API |
| `alts` | standalone, network, remote | two accounts from one address listed by `/alts -p` |
| `replay` | standalone, network, network-bungee, remote (every line) | builds the replay reference scene (`fixtures/replay-scene/`, legacy 64 blocks higher), records a player who watches a second player, a summoned zombie, a dropped item, a placed and broken block and chat, captures it from the console, reads the metadata and the file through `GET /v1/panel/replays/<id>` and `/file` (both 401 without a session, the file `no-store`), parses the v5 file with the generated TS proto (`src/lib/replay-file.ts`) and asserts every scene block's name and properties, entities, equipment, player names, event order and the overworld height; on 1.18+ also a nether run (min y 0, height 256). A share link created through the API opens the metadata and the identical file logged out (ranges answered, two views counted) and answers 404 `REPLAY_SHARE_NOT_FOUND` after it is revoked. On standalone and network (where the scene is on survival and the commands run on the proxy console) the panel settings switch replays off (`/replay record` refused, the connected servers list reports replays inactive) and back on for on-demand recording only (record every player and record at flags off, restored afterwards) without a restart, no storage URL serves the stored replay file (404), `/replay status` shows nothing recorded, `/replay record` starts the recording and the status names `/replay record` and the memory, and `/replay stop` ends it at the end. Files land in `reports/replays/` |
| `replay-load` | standalone, remote (latest, 1.21, folia) | 30 players spread to fresh chunks and gather, first with replays off, then with every player auto-recorded (server restarted between); average MSPT grows by at most 5 percent of a 50 ms tick (2.5 ms), 20 TPS, resident memory growth within the replay budget, and a replay captured under load |
| `network-members` | network, network-bungee | `mode: behind-proxy` auto-detection, both servers listed as members linked to the hub (`GET /v1/panel/servers/connected`), `/proxycmd modl import litebans` refused, `/target` across servers in staff mode, hotbar vanish as a staff action, freeze within 5 seconds (latency recorded), a replay captured on survival through the hub and uploaded by survival, a stat wipe (1 minute `-stat-wipe` ban, rejoin, `Executed 1 stat-wipe command(s)` on a member, `statWipeCompleted` on the punishment) |
| `member-key-scope` | network, network-bungee | a behind-proxy key gets 403 `SERVER_KEY_SCOPE` on `/v3/minecraft/players/sync` and when it starts as a core, `/proxycmd modl` refused until the key allows it and then run on the proxy (`[network] Running /modl for survival console`), revoking the key disconnects survival at once with the rejected-key warnings, a new key brings it back |
| `member-reload-resyncs-freeze` | network, network-bungee (not folia) | a player frozen on survival stays frozen after `reload confirm` on survival, once it rejoins the network hub; unfreezing releases it |
| `no-bridge-port` | network, network-bungee | a member reaches `proxy:8080` but a TCP connect to `proxy:25590` is refused, and no server prints a `[bridge]` line |
| `remote-late-backend` | remote | Paper starts before the modl server, refuses logins, then completes its late bootstrap |
| `litebans-import` | standalone, remote; `latest` line only | console-only LiteBans import against the test fixture: refusals (no LiteBans, player, RCON), preview, required choices, more than 100 MB uploaded, restart while processing, one file per job, kept IP history, second run without duplicates, cancel; build the fixture first with `npm run fixture:litebans` |
| `advancedban-import`, `libertybans-import`, `banmanager-import`, `bansystem-import`, `vanilla-import` | standalone, network, network-bungee, remote (per line and proxy in `npm run cli -- lines`, which names the reason where one does not apply) | the real plugin (pinned jar) seeded with its own console commands, `/modl import <source>` preview and confirm, punishments, pardons, notes and history verified through the API, then modl alone refuses the banned player. On a proxy the proxy version of the plugin runs on the proxy (BanManager's Velocity or BungeeCord jar; BanSystem with Floodgate, which it needs there), modl's commands with the same names are switched off in the proxy's `config.yml` while it is seeded and restored afterwards; vanilla is seeded on `lobby` and copied to the proxy as the wiki describes. The `-stopped-` and `advancedban-mariadb-import` variants run on standalone and remote |
| `console-allowlist` | all | consoles during `up` and during play (chat, `/standing` and `/report` menus) show only allowlisted modl lines, no server warning names modl (such as Paper's PlayerLoginEvent listener warning), no console line shows raw formatting codes (apart from the servers' own kick logs), and no console line anywhere references a package that only the bundled modl server ships |
| `reload-twice` | standalone (not folia) | `/reload confirm` twice restarts the embedded panel in the same JVM and keeps sessions working |
| `restart-persistence` | standalone, network, remote | graceful restart of the server holding the data keeps the claim, session, link and bans |
| `crash-recovery` | standalone, network, remote | `docker kill` of that server (Paper, the proxy, or the remote modl server), start again, data intact |
| `database-move` | standalone | Settings > System > Database moves SQLite to PostgreSQL (a `postgres:17-alpine` sidecar on the compose network) and back; only the built-in panel restarts, bans, session and online players survive |
| `embedded-drop-in` | standalone | with an unreachable `embedded.download-url`, the panel starts from the archive in `plugins/modl/server/` without any `Downloading the built-in panel` line, and a player joins |
| `embedded-tampered-archive` | standalone | a same-size corrupted archive is refused (`... does not match this modl plugin jar` inside the `modl is not running` block, modl does not boot, joins are refused); putting the right file back brings modl back |
| `embedded-download` | standalone | `--update-fixture` only: with no archive in `plugins/modl/server/`, the plugin downloads the archive and the signed `modl-release.json` from the release mirror, keeps the archive, and a player joins |
| `cloudflare-tunnel` | standalone | `embedded.tunnel` with a well-formed fake token: the pinned cloudflared is downloaded and matches its SHA-256, the panel listens on 127.0.0.1 only, the not-connected notice appears with no other unexpected modl line, and `/reload` leaves no cloudflared behind; needs outbound HTTPS to `github.com` |
| `missing-packetevents` | standalone | Paper without PacketEvents still loads modl, which fails closed: the console repeats `modl is not running: PacketEvents is not installed...` with the fix and players are refused with a message naming PacketEvents; reinstalling it brings modl back |
| `panel-port-in-use` | standalone | `embedded.port` set to a port that is already bound gives the "Port N is already in use" message; restoring it brings the panel back |
| `wrong-member-key` | network, network-bungee | a wrong `remote.api-key` on survival repeats "rejected the API key" naming the setting and Add server, players still reach survival through the proxy; the right key rejoins the network |
| `wrong-remote-key` | remote | a wrong `remote.api-key` gives "The modl server at <url> rejected the API key" with the setting to fix, logins are refused; the right key connects |
| `embedded-needs-java-21` | remote, `legacy` line only | `mode: embedded` on the Java 8 Paper keeps modl loaded but failed closed: the console repeats `modl is not running: The built-in panel needs Java 21 or newer. Update Java or use mode: remote.` with the remedy, players are refused, no panel starts; `mode: remote` brings modl back |
| `setup-password-claim` | standalone, network, remote | a fresh instance is claimed with the code and the email, then the sign-in step turns on password sign-in: password sign-in needs the owner's password, a bad length is refused, password login works and no login code is sent or printed |
| `hosted-import` | standalone, remote | a fresh instance imports a synthetic modl.gg export: refusals, an interrupted upload, the review, a restart while players import, exact counts, enforcement, settings, files and cleanup |
| `password-login` | standalone, network, remote | the owner sets a password and switches to password sign-in; failures look the same, codes are refused, changing the password ends other sessions |
| `password-staff` | standalone, remote | an invited staff member joins with a password, cannot manage credentials, and gets a single-use reset link from the owner that ends old sessions |
| `password-restart` | standalone | the password, the sign-in method and the authenticator survive a restart of the server that holds the data |
| `totp-login` | standalone, network, remote | the owner enrols an authenticator; a replayed code is refused, a password sign-in is challenged, codes and recovery codes complete it once |
| `second-factor-required` | standalone, remote | the owner cannot require 2FA without one; once required, the last factor cannot be removed |
| `ai-chat-report` | standalone | chat reports reach a stub AI provider through the panel's connection: verdicts, negotiation, failures, limits, SSRF rules and configuration-managed fields |
| `advancedban-import` | standalone, network, network-bungee, remote | the real AdvancedBan 2.3.0: punishments made with its commands on its HSQLDB arrive in modl after it is removed |
| `libertybans-import` | standalone, network, network-bungee, remote | the real LibertyBans 1.1.4: punishments made with its commands arrive in modl, read through its own connection while it runs |
| `banmanager-import` | standalone, network, network-bungee, remote | the real BanManager 7.11.0: punishments made with its commands on its H2 database arrive in modl, read through its own connection |
| `bansystem-import` | standalone, network, network-bungee, remote | the real BanSystem 3.1 (BanSystem mit IDs): ID punishments on its SQLite database arrive in modl, read from a copy while it runs (on a proxy with Floodgate, which BanSystem needs there) |
| `vanilla-import` | standalone, network, network-bungee, remote | banned-players.json, banned-ips.json and EssentialsX 2.22.0 mutes made with real commands arrive in modl (on a network: made on lobby, copied to the proxy) |
| `libertybans-stopped-import` | standalone, remote, `latest` and `1.21` and `folia` lines | the real LibertyBans 1.1.4: after the plugin is removed, modl reads a copy of its HSQLDB files |
| `banmanager-stopped-import` | standalone, remote, `latest` and `1.21` and `folia` lines | the real BanManager 7.11.0: after the plugin is removed, modl reads a copy of its H2 files |
| `bansystem-stopped-import` | standalone, remote, `latest` and `1.21` and `folia` lines | the real BanSystem 3.1 (BanSystem mit IDs): after the plugin is removed, modl reads a copy of its SQLite files |
| `advancedban-mariadb-import` | standalone, remote, `latest` and `1.21` and `folia` lines | the real AdvancedBan 2.3.0 on a MariaDB server: punishments made with its commands arrive in modl, read over the network while it runs |
| `update-check` | remote | the release check shows the offer, notifies once across restarts, honours dismissal, and sends nothing when switched off |
| `update-jar` | remote | Update now installs a signed patch of the server jar, keeps the data and survives a clean stop |
| `update-jar-rollback` | remote | a broken release returns the server jar to the old version and restores the database |
| `update-jar-interrupted` | remote | a SIGKILL during an update is finished or rolled back by the next start, never both |
| `update-backup-failure` | remote | an unwritable backups folder refuses Update now and aborts a manual upgrade without changing anything |
| `update-manual-guard` | remote | a manual upgrade backs up, refuses a downgrade, and the old jar undoes a failed upgrade by itself |
| `update-embedded` | standalone, network | Update now swaps a patch of the built-in panel in place; another line needs the plugin jar |
| `update-embedded-rollback` | standalone | a broken payload returns the built-in panel to the packed version with its data and without a stuck lock |
| `version-mismatch` | remote | older and newer plugins are denied with the remedy, and a backend rollback heals a running plugin without a restart |
| `member-version-mismatch` | network | a server behind the proxy with another line degrades while players still join, and recovers after the jar fix |
| `update-docker` | remote | the compose deployment reports Docker, refuses Update now and names the image tag of the offered release |

The matrix that covers every line (one topology at a time; each command boots, runs and tears down):

```bash
for line in latest 1.21 folia; do
  for topology in standalone network network-bungee remote; do
    npm run scenario -- all --up "$topology" --line "$line" --defer-setup --down
  done
  npm run scenario -- all --up remote --backend compose --line "$line" --defer-setup --down
  for topology in standalone network remote; do
    npm run scenario -- setup-no-console --up "$topology" --line "$line" --defer-setup --down
    npm run scenario -- setup-config-claim --up "$topology" --line "$line" --defer-setup --admin-email owner@modl.test --down
  done
done
npm run scenario -- all --up network-bungee --line legacy --down
npm run scenario -- all --up remote --line legacy --down
npm run scenario -- smoke --up standalone --java 27 --down
```

Every step also fails on new console problems (ERROR, FATAL, warnings with stack traces, lines with raw formatting codes outside the servers' own kick logs) on any server, except the benign patterns in `src/lib/console-policy.ts` and the expected first-start notice of the servers behind the proxy during `up` (and for as long as a deferred setup leaves them waiting for a server key). The console allowlist (`EXPECTED_MODL_LINES`) lists every line modl is meant to print; the leak check derives the server-only packages from the jars under `lib/` in the embedded archive (`dist/modl-embedded-<version>.zip`, or the base test release with `--update-fixture`), minus everything the server jars, PacketEvents, the plugin itself and the plugin's runtime libraries under `plugins/modl/lib/` provide, and caches the result in `.cache/console-leaks/` (keyed by the jars' and the archive's paths, sizes and modification times). Reports with all checks and evidence are written to `reports/scenarios/<topology>/<scenario>--<line>--<time>.json`.

## Driving it by hand

```bash
npm run cli -- cmd ban Steve griefing           # console command (stdin), prints the console lines it caused
npm run cli -- cmd "alts Steve -p" --expect "Page"
npm run cli -- cmd glist all --server proxy
npm run cli -- rcon lobby list                  # Paper RCON, direct reply
npm run cli -- logs proxy --tail 100 --grep network
npm run cli -- mark                             # then: errors --since-mark, logs <server> --since-mark
npm run cli -- errors
npm run cli -- audit paper
npm run cli -- restart proxy                    # also stop, start, kill
```

Bots stay connected between calls through a background daemon on a random loopback port (stopped by `down` or `bot stop`). The daemon only answers requests that carry the random token from `runtime/botd.json` (written with mode 0600) and rejects any request with an `Origin` header, so web pages cannot drive it.

```bash
npm run cli -- bot join Alice
npm run cli -- bot run Alice /report Bob
npm run cli -- bot do Alice window
npm run cli -- bot do Alice click slot=20
npm run cli -- bot do Alice recent limit=30
npm run cli -- bot actions
```

Panel and API:

```bash
npm run cli -- instance                         # panel URL for Playwright, setup phase, setup code and code file, session
npm run cli -- login                            # owner session: reuse, claim, owner sign-in code, or console login code
npm run cli -- setup-code                       # latest setup code printed to the console
npm run cli -- setup-code --file                # the code in the setup code file at its documented location
npm run cli -- connect                          # after up --defer-setup: owner session, then connect the waiting servers
npm run cli -- link-code
npm run cli -- api GET /v1/panel/auth/me
npm run cli -- api POST /v1/minecraft/panel/login-link '{"minecraftUuid":"..."}' --auth api-key
```

Panel sessions are the `MODL_SESSION` cookie plus the session key from the login response, sent as `X-Modl-Session-Key` on every `/v1/panel/**` request; the harness keeps both in `runtime/state.json` and redacts the key from printed responses. Open the `panel` URL from `instance` in the Playwright MCP to test the UI against the same instance; `setupPage` is the setup wizard, which asks for the setup code (or the owner sign-in code) until the owner has signed in. The harness's own claims and owner sign-ins complete the wizard so the panel does not send the owner there; boot with `--defer-setup` to walk it in the browser.

## Dev network (permanent, for playing by hand)

`npm run dev -- <command>` runs a realistic local network that stays up until you stop or reset it: you join it with your own Minecraft account, open the real panel, and watch every log, while bots fill the servers. It is separate from the test topologies: its own Docker project and network (`modl-devnet`), its own ports, and its own data in `.dev/` (never under `runtime/`, never removed by the harness's `down`). The harness and the dev network can run at the same time.

| Server | What | Reached at |
|---|---|---|
| `proxy` | Velocity 4.3.1 in online mode, modl `embedded` (the panel), PacketEvents, ViaVersion 5.12.2 + ViaBackwards 5.12.2 + ViaRewind 4.2.0 | `localhost:25565` (your account), panel `http://localhost:18200` |
| `botgate` | a second Velocity 4.3.1 in offline mode in front of the same two servers (same plugins), modl `remote` against the panel with its own server key, like a second proxy of a real network | `localhost:25566` (bots) |
| `latest` | Paper 26.1.2 on Java 25, the default server (`try`), modl `behind-proxy` with its own server key, PacketEvents | `/server latest` |
| `legacy` | Paper 1.8.8 on Java 8, modl `behind-proxy` with its own server key, PacketEvents | `/server legacy` |
| `logs` | Dozzle log viewer for every dev network container, bound to 127.0.0.1 | `http://localhost:18201` |
| `panel-log` | follows the built-in panel's `plugins/modl/server/logs/server.log`, so it shows up in the log viewer | |

Each port is taken from the table when it is free, else the nearest free one (never a harness port); `up` and `status` print the ones in use. The panel port is fixed at the first boot (`embedded.port` and `embedded.public-url` name it).

Design notes:

- ViaVersion runs on the proxies, not on the servers: current ViaVersion needs Java 17, and Paper 1.8.8 runs on Java 8. On Velocity it translates per backend, so a 26.x client reaches the 1.8.8 server (ViaVersion) and older clients reach the 26.1.2 server (ViaBackwards, and ViaRewind for 1.7 and 1.8 clients).
- Forwarding is Velocity `legacy` (BungeeCord-style) on both proxies, because Velocity has one forwarding mode per proxy and Paper 1.8.8 cannot do modern forwarding. Legacy forwarding is safe only when nothing but the proxies can reach the servers: `latest` and `legacy` publish no port and are reachable only on the dev network's Docker network (`settings.bungeecord: true`, `online-mode=false`).
- Realistic defaults: a normal survival world on both servers (not superflat), view distance 8, normal difficulty, spawn protection 16, whitelist off, nobody is op, MOTD `modl dev network`, bStats off on every server. Memory limits: proxy 2688 MB (2 GB heap, as the built-in panel asks), bot gate 704 MB, latest 1664 MB, legacy 1024 MB, log viewer and panel log 160 MB, about 6.1 GB in total; direct memory is capped at 192 MB per server. Every container is non-root with a read-only root filesystem, and Docker logs rotate at 3 files of 10 MB.
- Configs are written only once, at the first boot of each server; later edits (by you or on request) are kept. Plugin jars are installed at the first boot and replaced only by `rebuild`.
- The panel setup is left to you: `up` prints the setup code while the panel is unclaimed. Until then `latest` and `legacy` idle without a server key (players can join) and the bot gate is not started. After you finish the setup, `connect --email <your owner email>` signs in as the owner (the email code is read from the proxy console when no SMTP is set; with SMTP, pass `--code <emailed code>`), creates the server keys `latest` and `legacy` (behind a proxy) and `botgate` (proxy), writes them into `connection.yml`, reloads the servers and starts the bot gate. The owner session is kept in `.dev/state.json`, so later `up` runs connect on their own.

```bash
npm run dev -- up                                  # boot or resume; prints join address, panel, logs and setup code
npm run dev -- status                              # servers, modl state, panel setup, bots
npm run dev -- connect --email you@example.com     # after the panel setup
npm run dev -- logs latest --follow                # or proxy, botgate, legacy, panel (server.log); no name: all
npm run dev -- cmd legacy op YourName              # console command on proxy, botgate, latest or legacy
npm run dev -- restart latest                      # or no name for the whole network, proxy first
npm run dev -- rebuild                             # install the current dist/ build, restart in order, keep data
npm run dev -- bots add 5 --server legacy --behavior pvp   # behaviors: idle, wander (default), chat, pvp
npm run dev -- bots list
npm run dev -- bots remove all
npm run dev -- down                                # stop everything; worlds, plugins and the panel data stay
npm run dev -- reset --yes                         # delete .dev/ (worlds, plugin folders, panel data)
```

`MODL_DEVNET_HOME` (a folder) and `MODL_DEVNET_PROJECT` (a Docker project name) run a second, separate dev network, for example a throwaway one to try something without touching yours; stop the other one first, since both use the same ports.

Bots are offline-mode Mineflayer players of the latest server's Minecraft version that join through the bot gate and, with `--server legacy`, switch with `/server legacy` (ViaVersion translates for them). A background daemon (`.dev/botd.json`, token-protected like the harness's) keeps them online until `bots remove` or `down`; a bot that is disconnected rejoins after 20 seconds and gives up after 15 failed joins.

## Matrix, screenshots, flows, and verify

```bash
npm run cli -- matrix --tier quick      # standalone and network on latest, in two parallel lanes (about 10 min)
npm run cli -- matrix --tier full       # every line and topology, the setup variants, Java 27 (before a release)
npm run shots                           # screenshots of every screen; changed ones go to the review page
npm run review                          # http://127.0.0.1:18300: before, after, diff, Approve, comments
npm run flows                           # real UI flows against the seeded panel
npm run serve                           # keep the seeded panel up (owner@modl.test, codes in docker logs modl-shots-backend)
npm run verify                          # the gates for the uncommitted changes (add --e2e for the quick matrix)
```

- Matrix jobs run with `--fresh --down --retry-failed`; each lane (`MODL_TEST_LANE`) keeps its state in `runtime-lanes/<lane>/`. A scenario that only passes on its second attempt is listed as flaky. The summary is `reports/matrix/<time>/summary.md`.
- Screenshots run beside any topology (compose project `modl-shots`, port 18130 or the next free one). The panel's clock is faked with libfaketime, so every run starts at the same virtual time, and the data is a snapshot of `src/seed/dataset.ts` played through the real API over 30 virtual days (cached in `.shots/datasets/`, rebuilt when the seed, the SQLite migrations or the version change, or with `--rebuild-dataset`). Baselines live in `.shots/baseline/` and change only through Approve (or `--baseline`). Comments from the review page are collected in `reports/review/feedback.md`. `--only <id>` captures matching screens, `--check` only reports render problems.
- Flows write `reports/flows/<time>/flows.md` with a screenshot of every failure.

## Seed data for the dev backend

`npm run seed` fills the dev backend (`dev/server.sh`, `http://localhost:18080`) with sample data through the minecraft API: 40 players with logins and shared addresses (alt links), chat logs, manual and severity punishments issued by the console, player notes and tickets. The API key is read from `dev/.data/database/modl.db` with `sqlite3`; set `MODL_SEED_URL` and `MODL_SEED_API_KEY` for another backend. A rerun adds more punishments, notes and tickets.

## Wiki videos

`npm run cli -- video <id...|all>` records the tutorial videos embedded in the wiki and the release teaser:

| id | Wiki page | Topology |
|---|---|---|
| `paper-install` | Single-Server | standalone |
| `velocity-network` | Proxy-Network | network |
| `link-account` | First-Run (Link your Minecraft account) | standalone |
| `report-to-replay` | Commands (From report to punishment) | standalone |
| `import-plugins` | Migrating-from-Other-Plugins | standalone |
| `staff-team` | Permissions (Staff team) | standalone |
| `public-pages` | Customizing-the-Public-Pages | standalone |
| `teaser-v3` | none (README) | network |

Each video boots its topology fresh with `--defer-setup` on the `latest` line, claims and sets up the panel through the real UI with Playwright, renders the console and file scenes from the real server logs and plugin folder, films the in-game parts of `report-to-replay` and `link-account` in a real Minecraft client, then tears the topology down (like `scenario --down`, this removes the harness runtime data). It refuses to start while a topology is up. `import-plugins` needs the LiteBans fixture (`npm run fixture:litebans`). Player names in narration, labels, and seeded data are Steve and Alex, apart from the real client's own name.

- Needs Chromium for `playwright-core` (`npx playwright-core install chromium-headless-shell`) and ffmpeg with libx264 (without it, the pinned `linuxserver/ffmpeg` image runs through Docker).
- Step labels are checked word for word against the wiki before anything boots; at most 6 words each.
- Output: `reports/video/<id>.mp4` (H.264, 1920x1080, 30 fps, at most 9.5 MB; 90 s, or 150 s when narrated) and `reports/video/<id>-sheet-NN.png` (one frame every 1.5 seconds).
- Narrated videos get spoken narration, background music and step captions at the bottom, all generated by the recorder. Every narration clip is levelled to the same loudness with one linear gain (no dynamic normalization; at most 1 LU apart), the music bed is levelled to a steady -19 LUFS and ducked by a fixed 11 dB under speech with 250 ms ramps (held across pauses under 1.5 s), and the final mix is normalized linearly. A mix check measures 3 s short-term loudness on the final mix and fails the recording when a window leaves -21 to -13 LUFS or the true peak passes -1.5 dBTP; the window table and momentary jumps above 6 LU are in the video report (`audioQa`). The voice comes from Kyutai Pocket TTS, run locally on the CPU through `uv` (`uvx`) with the package, model and voice pinned and cached under `.cache/video-tts/` (about 2.5 GB on first use); generated clips are cached there too, so re-recording reuses them. `npm run cli -- video-voices` renders voice samples to `reports/video/voice-samples/`. The music source and licence, and the voice and model sources, are in the manifest.
- The teaser (`teaser-v3`) has no voice and no captions. It captures panel footage at twice the output resolution, then composes every frame in Chromium (`src/video/teaser/`: 3D tilt and zoom, console, folder, game and list shots) for two cuts: `teaser-v3.mp4` (1920x1080) and `teaser-v3-vertical.mp4` (1080x1920, each shot re-framed around its focus: the crop starts at the focus's content, never cuts a text line seen in any kept frame of the footage, and widens toward the full frame when no narrower crop qualifies; footage with text drawn on a canvas, such as the replay viewer, is never cropped). Its music is the Minecraft soundtrack (C418 "Sweden" into Lena Raine "Pigstep"), downloaded from Mojang's asset servers and pinned by sha256 under `.cache/video-music/`; the beats are detected in the harness (`src/video/audio/beats.ts`) and every cut after the drop lands on the fitted beat grid. The report lists each cut's offset from its beat.
- `src/video/manifest.json` records each video's title, wiki page, modl version, duration, size and sha256. Upload a new recording as a GitHub attachment and put its URL into the page's `<video src=... controls>` (`muted` for videos without sound).
- Narration voice generated with Kyutai Pocket TTS (model weights CC BY 4.0).
- Re-record when a screen, console line or wiki step that a video shows changes.

Real-client videos (`report-to-replay`, `link-account`; `realClient` in the video definition, narrated standalone videos only) film your own Minecraft client on macOS:

- Start the client from Prism Launcher before `video` and keep only one open. Before anything boots, the command checks that the client runs, that `cliclick` (Homebrew) and the host ffmpeg with avfoundation and `h264_videotoolbox` are there, and that the client's `options.txt` keeps the defaults the clicks rely on (`chatLinks` and `chatLinksPrompt` on, chat scale 1.0 and line spacing 0, chat on `t` and commands on `/`).
- The video boots its server with `--real-client` (ViaVersion and ViaBackwards on the standalone Paper), then logs `waiting for <name> to join localhost:25700 ...` and waits up to 20 minutes: join with Multiplayer > Direct Connection > `localhost:25700` > Join Server. The player name comes from the client log.
- The window is moved to 150,60 and sized to a 1500 point wide game area with the captioned stage's aspect, then brought to the front; leave it there and do not touch the mouse or keyboard while a take records. Every key, typed character, move, and click first checks that the game is the frontmost app and that the window has not moved, and stops otherwise; menus left open (the pause menu after a focus change) are closed with Escape before a take starts.
- Input: `cliclick` moves and clicks the mouse, only inside the game area; keys go through System Events (Escape, Return, `t`, `/`, and typed text; never a function key; the key codes assume a US layout). Each report menu click waits until the next menu is on screen, and the chat link is clicked only while it is the newest chat line. The terminal needs Accessibility and Screen Recording access.
- Recording: the host ffmpeg captures `Capture screen 0` through avfoundation, cropped to the game area at the screen's pixel scale (at most 300 s per take); frames are extracted at 30 fps and padded to the captioned stage, and narration and captions are timed from the first recorded frame. A take fails at once when the recording stops.
- `link-account` copies the `/modl panel` sign-in link with the client's Copy to Clipboard button and accepts it only for this panel's address; the previous clipboard text is restored afterwards (non-text clipboard contents are not kept). The ticket ID in `report-to-replay` is read from the client's chat log (`logs/latest.log`).
- When a real-client video fails, the game screenshots it took are kept in `reports/video/work/<id>/failure/game-window/`.

## MCP server

`src/mcp.ts` exposes the harness over stdio as `modl-minecraft`. Register it from the repository root:

```bash
claude mcp add modl-minecraft -- npm --prefix testing run --silent mcp
```

Tools: `mc_doctor`, `mc_lines`, `mc_status`, `mc_up`, `mc_down`, `mc_server` (restart, stop, start, kill), `mc_console`, `mc_rcon`, `mc_logs`, `mc_mark`, `mc_errors`, `mc_audit`, `mc_bot_join`, `mc_bot_quit`, `mc_bot_list`, `mc_chat`, `mc_run_command`, `mc_recent_chat`, `mc_open_menu`, `mc_read_window`, `mc_click_slot`, `mc_close_window`, `mc_bot_status`, `mc_players`, `mc_scoreboard`, `mc_wander`, `mc_offline_uuid`, `modl_instance`, `modl_login`, `modl_setup_code`, `modl_api`, `modl_link_code`, `mc_connect`, `mc_scenarios`, `mc_scenario`.

## Layout

```
testing/
  docker/run.sh, console.sh   container entrypoint (stdin FIFO console) and console writer
  src/cli.ts                  CLI
  src/mcp.ts                  MCP server
  src/botd.ts                 bot daemon behind the CLI bot commands
  src/lib/                    artifacts, docker, topologies, consoles and logs, console policy, panel client, bots
  src/lib/contract.ts         product contract the harness relies on: console lines, commands, endpoints
  src/scenarios/              scenario framework and scenarios
  fixtures/litebans/          LiteBans stand-in plugin, a standalone Gradle build on the repository's build-logic
  src/devnet/                 the permanent dev network (npm run dev)
  src/video/                  wiki video recorder: studio (screencast, cursor and labels), footage (real client window and screen recording), scenes, scripts per video, ffmpeg composer, manifest
  .cache/                     pinned server jars, the pre-patched Paper bundle and the console leak index
  runtime/                    state, and per topology and line the compose file and server directories
  reports/                    scenario reports and archived console logs
  .dev/                       the dev network: state, compose file, server folders (worlds, plugins, panel data)
```

`npm run check` (type-check and ESLint) is the gate for changes here.
