# Commands

Commands run on the server that runs the modl core: a standalone server, or the proxy of a network. Servers behind a proxy only have `/modlserver` and `/proxycmd`.

- Permissions are panel role permissions ([Permissions](Permissions)), not Bukkit or LuckPerms nodes. Staff get them by linking their Minecraft account ([First Run](First-Run#link-your-minecraft-account)).
- "staff" below means a linked staff member. With `staff_2fa.enabled`, staff commands also need a confirmed `/verify`.
- The console runs everything except player-only commands, with no permission check.
- Aliases are set under `commands:` in `config.yml` ([Plugin Files](Plugin-Files#configyml)).
- `<required>`, `[optional]`.

## Players

| Command | Aliases | Arguments | Does |
|---|---|---|---|
| `/report` | | `<player>` | Report menu for an online player (needs menus) |
| `/chatreport` | `cr` | `<player>` | Report with the player's recent chat attached |
| `/hackreport` | `hr` | `<player> [details]` | Cheating report, with a replay when recording is on |
| `/apply` | | | Staff application ticket |
| `/bugreport` | | | Bug report ticket |
| `/support` | | | Support ticket |
| `/tclaim` | `claimticket` | `<ticket id> <key>` | Link a ticket made on the website to your account |
| `/standing` | | | Your standing and punishments (menu) |
| `/iammuted` | | `<player>` | Tell an online player you are muted (only while muted, every 5 minutes) |
| `/modl` | | | Version |
| `/modl help` | | `[page]` | Commands you can use |
| `/modl link` | | `<code>` | Link your Minecraft account to your panel account |
| `/modl panel` | | | One-time panel sign-in link for linked staff; refused on offline-mode servers unless `panel_login.allow_offline_mode` |

Ticket commands have a 60 second cooldown per ticket type (`/report` and `/hackreport` share one). `/standing` has a 60 second cooldown too.

## From report to punishment

https://github.com/user-attachments/assets/624395db-d5af-4c94-8408-900165f51c13

1. In game: `/report <player>`, pick a reason, and confirm. Cheating reasons can capture a replay (when recording is on, [Replays](Replays)); chat reasons can attach the player's recent chat.
2. Panel > Tickets: open the new report.
3. View replay opens the replay over the ticket (Esc closes it).
4. Reply as Accept, then apply the punishment in the panel that opens. Applying it closes the ticket and sends the reply.

## Staff

| Command | Aliases | Arguments | Permission |
|---|---|---|---|
| `/staffmenu` | | | staff (players only, needs menus) |
| `/inspect` | `ins`, `check`, `lookup`, `i` | `<player> [-p]`, or `<#punishment id>` | view players |
| `/history` | `hist` | `<player> [-p [page]]` | view players |
| `/notes` | | `<player> [-p [page]]` | view players |
| `/alts` | `alt` | `<player> [-p [page]]` | view players |
| `/reports` | | `[player] [-p [page]]` | staff |
| `/punish` | `p` | `<player> [type] [reason] [flags]` | `punishment.apply.<ordinal>` of the type |
| `/ban` | | `<player> [duration] [reason] [flags]` | `punishment.apply.2` |
| `/mute` | | `<player> [duration] [reason] [flags]` | `punishment.apply.1` |
| `/kick` | | `<player> [reason]` | `punishment.apply.0` |
| `/blacklist` | | `<player> [reason] [flags]` | `punishment.apply.5` |
| `/warn` | | `<player> <reason> [-s]` | `player.warn` |
| `/pardon` | | `<player or punishment id> [reason]` | `punishment.modify.pardon` |
| `/unban` | | `<player or punishment id> [reason]` | `punishment.modify.pardon` |
| `/unmute` | | `<player or punishment id> [reason]` | `punishment.modify.pardon` |
| `/staffchat` | `sc` | `[message]` | staff |
| `/localchat` | `lc` | `[message]` | staff (players only) |
| `/chat` | | | staff (shows help) |
| `/chat staff` | | | staff (players only, staff chat enabled) |
| `/chat toggle` | | | `staff.chat.toggle` |
| `/chat clear` | | `[lines]` (at most 500) | `staff.chat.clear` |
| `/chat slow` | | `<seconds\|off>` | `staff.chat.slow` |
| `/stafflist` | | `[-p [page]]` | staff |
| `/staffmode` | `modmode`, `sm`, `mm` | | `staff.modactions` (players only) |
| `/vanish` | `v` | | `staff.modactions` (players only) |
| `/freeze` | | `<player>` | `staff.modactions` |
| `/target` | | `[player]` | `staff.modactions` (players only) |
| `/replay status` | | `[player]` | `staff.modactions` |
| `/replay record` | | `<player>` | `staff.modactions` |
| `/replay stop` | | `<player>` | `staff.modactions` |
| `/replay capture` | | `[player]` | `staff.modactions` |
| `/chatlogs` | | `<player> [page]` | `staff.chatlogs` |
| `/commandlogs` | | `<player> [page]` | `staff.commandlogs` |
| `/interceptnetworkchat` | `inc` | | `staff.intercept` (players only) |
| `/verify` | | | staff, players only; with `staff_2fa.enabled` |
| `/modl reload` | | | owner (Super Admin) or console; also the Reload button in the staff menu's settings |

View players: `punishment.view`, `punishment.modify`, or any `punishment.apply.<ordinal>`.

`-p` (or `print`) prints to chat instead of opening a menu. Consoles and proxies without PacketEvents always print. `/punish` with no type opens the punishment menu.

`/pardon` ends every active or not-yet-started punishment of the player; `/unban` and `/unmute` only bans or mutes. An 8-character uppercase punishment id targets that punishment.

## Console only

In the console of the server that runs the core, not through RCON or `/proxycmd`.

| Command | Does |
|---|---|
| `/modl import` | Usage and the sources found here |
| `/modl import <source>` | Preview an import from another plugin (required before confirm) |
| `/modl import <source> confirm <widen\|skip> <keep\|standard>` | Start it ([Migrating from Other Plugins](Migrating-from-Other-Plugins)) |
| `/modl import status` | Progress or last result |
| `/modl import cancel` | Stop it |
| `/modl status` | Support report without keys or emails, also while modl is not running (Settings > System > Status shows the same) |

## Spigot, Paper, and Folia servers

| Command | Who | Does |
|---|---|---|
| `/modlserver reload` | Bukkit permission `modl.server.reload` (op) | Reload `server-features.yml` and `staff_mode.yml` |
| `/proxycmd <command>` | console or RCON of a server behind a proxy | Run a modl command on the proxy, if its key allows it |

## Punishment arguments

| Flag | On | Meaning |
|---|---|---|
| `-s`, `-silent` | `/ban`, `/mute`, `/kick`, `/blacklist`, `/punish`, `/warn` | No public announcement (staff are still notified); on `/warn`, no staff notice |
| `-ab`, `-alt-blocking` | `/ban`, `/blacklist`, `/punish` | Also blocks alt accounts; `/punish` refuses it if the type does not allow it |
| `-sw`, `-stat-wipe` | `/ban`, `/blacklist`, `/punish` | Wipe stats when it ends; `/punish` refuses it if the type does not allow it |
| `-lenient`, `-regular` (`-normal`), `-severe`, `-severity <level>` | `/punish` | Severity, default regular. Levels: `low`, `lenient`, `regular`, `normal`, `severe`, `aggravated`. Refused on single-severity and until-change types. The offense level comes from the player's history. |

- `/ban` and `/mute` take a duration: a number with a unit, combinable (`1d12h`): `s`, `m` (minutes), `h`, `d`, `w`, `mo` (30 days). The first word that reads as a duration is taken, anywhere in the arguments. Without one they are permanent.
- `/punish` always uses the type's configured duration; a duration word becomes part of the reason.
- `/punish` takes the configurable types (ordinal 6 and up, [Permissions](Permissions#punishment-types)); names match case-insensitively and may have up to 4 words: `/punish Steve Chat Abuse spam in chat -severe`. Use `/kick`, `/mute`, `/ban`, and `/blacklist` for the fixed types.
