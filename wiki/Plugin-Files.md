# Plugin Files

Everything under `plugins/modl/` on every platform. Files are created on the first start; an update adds new options without touching your values.

## Files and folders

| Path | Where | Holds | Applies |
|---|---|---|---|
| `connection.yml` | all | Mode and panel connection ([Configuration](Configuration#connectionyml)) | restart |
| `config.yml` | servers running the core | In-game behavior (below) | restart; `/modl reload` for some |
| `locale/en_US.yml` | servers running the core | Message overrides ([Placeholders](Placeholders)) | `/modl reload` |
| `punish_gui.yml` | servers running the core | `/punish` menu slots | `/modl reload` |
| `report_gui.yml` | servers running the core | `/report` menu slots | `/modl reload` |
| `server-features.yml` | Paper, Folia, Spigot | Staff mode, freeze, anticheat reports, stat wipes | `/modlserver reload` (server-name needs a restart) |
| `staff_mode.yml` | Paper, Folia, Spigot | Staff and target hotbars and scoreboards | `/modlserver reload` |
| `setup-code.txt` | panel host | Current setup code, until setup is done | |
| `lib/` | all | Runtime libraries (downloaded) | |
| `server/modl-embedded-<version>.zip` | panel host | The panel, downloaded on the first start or put there by hand; checked against the plugin jar on every start, other versions are removed | |
| `server/lib/` | panel host | Panel libraries (downloaded) | |
| `server/logs/server.log` | panel host | Full panel log, 10 MB, 4 older files | |
| `data/` | panel host | Database, uploads, backups, updates ([Database and Storage](Database-and-Storage)) | |
| `replays/` | Paper, Folia, Spigot | Replays being written or uploaded, owner-only ([Replays](Replays#recording)) | |
| `staff-custody/`, `staff-mode-returns.txt` | Paper, Folia, Spigot | Inventories and return points saved during staff mode. Do not delete them; `.kept` files are copies modl did not restore, left for you to check | |

Servers running the core: standalone servers and proxies (`embedded`, `remote`). A `behind-proxy` server uses `connection.yml`, `server-features.yml`, and `staff_mode.yml`, plus `locale` from a `config.yml` you add and `locale/<locale>.yml` overrides for its own messages (`/modlserver reload` applies them); its commands come from the proxy.

## config.yml

| Key | Default | Meaning |
|---|---|---|
| `server.name` | `Server 1` | Name of this server in chat and command logs, reports, and staff lists. It does not rename the panel. |
| `server.query_mojang` | `false` | Look up unknown player names at Mojang. |
| `locale` | `en_US` | `en_US`, `de_DE`, `es_ES`, `fr_FR`, `hi_IN`, `it_IT`, `ja_JP`, `nl_NL`, `pt_BR`, `ru_RU`, `zh_CN`. Restart after changing. |
| `staff_chat.enabled` | `true` | Staff chat. |
| `staff_chat.prefix` | `!` | Chat prefix that sends to staff chat. |
| `staff_chat.format` | `&b[Staff] &f{player}: &7{message}` | Also takes `{panel-name}`. |
| `staff_2fa.enabled` | `false` | Staff must confirm a `/verify` link before staff commands; valid 7 days per IP. |
| `chat_management.clear_lines` | `100` | Blank lines sent by `/chat clear`, at most 500. |
| `realtime.enabled` | `true` | Live push from the panel. Off falls back to slow polling, and bans then apply at next login. |
| `staff_mode.vanish_on_enable` | `true` | Vanish on entering staff mode. |
| `panel_login.allow_offline_mode` | `false` | Allow `/modl panel` on servers that do not verify accounts. Only if players are authenticated another way. |
| `proxy.packet_interception` | `auto` | Proxies: `auto`, `enabled`, `disabled`. Needs PacketEvents on the proxy. `auto` turns off next to plugins that rewrite packets, such as LimboAPI. |
| `update_checker.enabled` | `true` | One update notice at start; `false` also stops a built-in panel's checks. |
| `muted_commands` | `msg tell w whisper m pm message r reply me teammsg tm` | Commands muted players cannot use, without the slash. Plugin prefixes (`essentials:msg`) and every alias the server knows for a listed command are blocked too; a proxy cannot see a backend server's aliases. |
| `redacted_commands` | `[]` | Extra commands whose arguments are hidden from command logs. Login, register, password, email, and 2FA commands of common auth plugins, `/modl link` and `/tclaim` are always hidden. |
| `logging.chat`, `logging.commands` | `true` | Store chat and commands for the panel's logs. Chat reports still capture recent chat with chat logging off. |
| `logging.never_logged_commands` | `[]` | Commands never stored, matched like `muted_commands`. |
| `commands.<name>` | see [Commands](Commands) | Aliases, separated by `\|`; the first is the main name. Empty disables the command. Restart. |
| `locale_config.default_reason` | `No reason specified` | Shown in game in place of a missing punishment reason, and the text of an imported warning that has none. |
| `locale_config.date_format` | `MM/dd/yyyy HH:mm` | Java `SimpleDateFormat` pattern. An invalid one uses the default and logs one warning. |
| `locale_config.timezone` | server zone | e.g. `UTC`, `Europe/London`, `EST`. An unknown zone uses the server zone and logs one warning. |
| `locale_config.duration_format.*` | `{days}d` ... `permanent` | Duration words. |
| `standing_gui.*.item` | | Items of the `/standing` menu. |
| `punishment_type_items_by_ordinal` | | Item per punishment type in menus. |
| `debug` | `false` | Verbose logs. |

`/modl reload` (or Reload in the in-game settings menu) rereads `locale/<locale>.yml`, the punishment types, both menu files, `muted_commands`, `logging`, `redacted_commands`, `punishment_type_items_by_ordinal`, `staff_mode.vanish_on_enable`, `panel_login.allow_offline_mode`, and `standing_gui`. Restart for everything else.

## server-features.yml

| Key | Default | Meaning |
|---|---|---|
| `server-name` | `""` | This server's name in the proxy config. Required behind a proxy. |
| `stat-wipe-commands` | `clearstats {player}` | Console commands run on a stat wipe; `{player}`, `{uuid}`. |
| `report-cooldown` | `60` | Seconds between auto reports per player. |
| `report-violation-threshold.default` | `10` | Violations before a report; per check name also possible. |
| `debug` | `false` | Verbose logs. |

Replays and the anticheat integration are set in the panel ([Replays](Replays)), not in this file.

## Messages

All in-game messages, including staff mode, freeze, and `/modlserver` on Paper (section `server_features`), follow `locale` and the overrides in `locale/<locale>.yml` ([Placeholders](Placeholders)). Only `en_US.yml` is written to disk; for another language copy its file from `minecraft/core/src/main/resources/locale/` in the repository.
