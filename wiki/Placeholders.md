# Placeholders

## In-game messages

Put only the keys you change in `plugins/modl/locale/<locale>.yml`; they are merged over the bundled text. Run `/modl reload`.

- Colors: `&` codes (`&c`, `&l`), or MiniMessage color and style tags: `<red>`, `<bold>`, `<#ff8800>`, `<color:#ff8800>`. Other MiniMessage tags (click, hover, gradients) are removed.
- Placeholders are `{name}`. A message only receives the placeholders its default text uses.
- A missing key shows `Missing locale: <key>` (`Missing locale list: <key>` for a list) and is logged once.

### Punishment messages

`punishment_types.ordinal_<n>.player_notification` (lines shown to the punished player, kick or ban screen, and mute notice) and `.public_notification` (broadcast). Types without their own entry use `punishments.player_notification.*` and `punishments.public_notification.*`.

| Placeholder | Player | Public | Value |
|---|---|---|---|
| `{target}` | yes | yes | Player name (`You` for the player) |
| `{reason}`, `{description}` | yes | yes | Player-facing reason |
| `{duration}` | yes | yes | Time left, or the permanent word |
| `{id}` | yes | yes | Punishment id |
| `{appeal_url}` | yes | yes | Panel address + `/appeal` |
| `{temp}` | yes | yes | `temporarily` or `permanently` |
| `{issued}` | yes | | Issue date (`locale_config.date_format`) |
| `{issuer}` | yes | | Staff name |
| `{player_description}` | yes | | Player description of the type |
| `{tense}`, `{tense2}` | yes | | `have been`/`are`, `has been`/`is` |
| `{for_duration}` | yes | | ` for 3d` on timed punishments, empty otherwise |
| `{will_expire}` | yes | | Expiry line on timed punishments, empty otherwise |
| `{duration_formatted}`, `{mute_duration_formatted}` | yes | | Alternative expiry texts |

The words behind `{tense}`, `{temp}` and the expiry texts are in `punishment_words`.

### Other messages

Other messages take the placeholders their default text in `en_US.yml` uses. Staff join, leave, switch, and verified notices, chat management broadcasts, the staff list, and the freeze, staff mode, and vanish broadcasts also accept `{in-game-name}`.

## Plugin files

| Where | Placeholders |
|---|---|
| `config.yml` `staff_chat.format` | `{player}`, `{panel-name}`, `{message}` |
| `config.yml` `locale_config.duration_format` | `{days}`, `{hours}`, `{minutes}`, `{seconds}` |
| `server-features.yml` `stat-wipe-commands` | `{player}`, `{uuid}` |
| `staff_mode.yml` `staff_scoreboard` | `{player_name}`, `{server}`, `{online}`, `{max_players}`, `{date}`, `{vanish}`, `{vanish_status}`, `{vanished}`, `{staff_online}` |
| `staff_mode.yml` `target_scoreboard` | the above plus `{target_name}`, `{target_health}`, `{target_ping}`, `{freeze_status}` |
| `punish_gui.yml` slot `description` | `{staff-description}`, `{social-status}`, `{gameplay-status}`, `{social-points}`, `{gameplay-points}` |

## Panel

| Where | Placeholders |
|---|---|
| Discord webhook, new ticket (Settings > General > Webhooks) | `{{id}}`, `{{type}}`, `{{title}}`, `{{priority}}`, `{{category}}`, `{{submittedBy}}`, `{{ticketUrl}}`, `{{panelUrl}}` |
| Discord webhook, new punishment | `{{id}}`, `{{playerName}}`, `{{playerUuid}}`, `{{type}}`, `{{severity}}`, `{{reason}}`, `{{duration}}`, `{{issuer}}`, `{{ticketId}}`, `{{playerUrl}}`, `{{panelUrl}}` |
| Discord webhook, audit log | `{{user}}`, `{{action}}`, `{{target}}`, `{{details}}`, `{{auditUrl}}`, `{{panelUrl}}` |
| Quick responses (Settings > Tickets > Quick replies) | `{reported-player}`, filled in when staff pick the response in the reply box |

Webhook events share one Discord message every 5 seconds, with up to 9 events each. Mentions are limited to the configured role. Staff show by username, the owner without a staff account as `Admin`, never by email. The webhook URL is write-only.

## Not supported

- PlaceholderAPI: modl provides no expansion and does not resolve PlaceholderAPI placeholders in its messages.
- Email texts and the AI prompt are not editable.
