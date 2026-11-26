# API

There is no separate public API. Scripts and bots use the plugin's Minecraft API with an API key. Read [Stability](#stability) first.

| Prefix | Auth | Body | For |
|---|---|---|---|
| `/v1/minecraft/**` | `X-API-Key` | JSON | Scripts (examples below) |
| `/v3/minecraft/**` | `X-API-Key` | protobuf (`application/x-protobuf`) | The plugin |
| `/v1/public/**` | none | protobuf or JSON | Public website pages |
| `/v1/panel/**` | session cookie plus `X-Modl-Session-Key` | protobuf or JSON | The panel only |
| `/v1/health`, `/actuator/health` | none | JSON | Health checks |

## Keys

The owner creates keys in Panel > Settings > General > Minecraft servers.

| Key | Made by | Can |
|---|---|---|
| Instance API key | generated on first start, or `MODL_API_KEY` | Everything under `/v1` to `/v3/minecraft` |
| Server key, "Standalone or proxy" | Add server | Same as the instance key; revocable on its own |
| Server key, "Behind a proxy" | Add server | Only what a backend server needs: startup, the version check, its own anticheat reports (normal priority, no tags or chat, a replay only of the reported player), replay upload, and confirming a due stat wipe. Anything else answers 403 `SERVER_KEY_SCOPE`. |

- Server keys start with `msk_`, are shown once, stored only as a hash, and can be revoked. At most 256; names are 1 to 64 letters, digits, `.`, `_`, or `-`.
- A full key can act as the console or as any staff member and read all player data. Give each script its own "Standalone or proxy" key so you can revoke it alone.

## Acting as a staff member

Writes (punishments, pardons, notes, evidence, duration, ticket links, report resolution) must name exactly one issuer:

- `"issuerId": "<staff id>"`: checked against that staff member's role permissions (403 if missing). Staff ids come from `GET /v1/minecraft/staff/permissions` (`data.staff[].staffId`; only staff with a linked Minecraft account are listed).
- `"console": true`: no permission check, recorded as `issuerName` or Console.

Neither or both: 400 `MINECRAFT_ISSUER_REQUIRED`.

## Examples

```bash
PANEL=https://panel.example.com
KEY='<key>'
```

Look up a player by name or UUID (`queryMojang`, default true, asks Mojang for unknown names):

```bash
curl -s -X POST "$PANEL/v1/minecraft/players/lookup" -H "X-API-Key: $KEY" \
  -H 'Content-Type: application/json' -d '{"query":"Steve","queryMojang":false}'
```

Full profile and punishments:

```bash
curl -s "$PANEL/v1/minecraft/players/<uuid>" -H "X-API-Key: $KEY"
curl -s "$PANEL/v1/minecraft/players/<uuid>/punishments?page=1&limit=20" -H "X-API-Key: $KEY"
```

Ban for one day (`type_ordinal` from [Permissions](Permissions#punishment-types); `duration` in milliseconds, omitted = permanent for fixed types, the configured duration for others; the player must have joined once):

```bash
curl -s -X POST "$PANEL/v1/minecraft/punishments/dynamic" -H "X-API-Key: $KEY" -H 'Content-Type: application/json' \
  -d '{"targetUuid":"<uuid>","type_ordinal":2,"reason":"Cheating","duration":86400000,"console":true,"issuerName":"anticheat-bot"}'
```

Pardon a punishment, or all bans of a player (`punishmentType`: `ban`, `mute`, or omitted for everything):

```bash
curl -s -X POST "$PANEL/v1/minecraft/punishments/<punishment id>/pardon" -H "X-API-Key: $KEY" \
  -H 'Content-Type: application/json' -d '{"console":true,"reason":"Appeal accepted"}'
curl -s -X POST "$PANEL/v1/minecraft/players/pardon" -H "X-API-Key: $KEY" \
  -H 'Content-Type: application/json' -d '{"playerName":"Steve","punishmentType":"ban","console":true}'
```

Add a note or a warning (`kind`: `NOTE` or `WARNING`):

```bash
curl -s -X POST "$PANEL/v1/minecraft/players/<uuid>/notes" -H "X-API-Key: $KEY" \
  -H 'Content-Type: application/json' -d '{"text":"Watch for x-ray","kind":"NOTE","console":true}'
```

## Endpoints worth using

| Method and path | Returns |
|---|---|
| `POST /v1/minecraft/players/lookup` | Player summary |
| `GET /v1/minecraft/players/by-name?username=<name>` | Player |
| `GET /v1/minecraft/players/<uuid>` | Profile with punishments and notes |
| `GET /v1/minecraft/players/<uuid>/punishments` `/notes` | Pages: `page`, `limit` (1 to 100, default 7) |
| `GET /v1/minecraft/players/<uuid>/linked-accounts` | All linked accounts, or a page when both `page` and `limit` (1 to 100) are given |
| `GET /v1/minecraft/players/<uuid>/chat-logs` `/command-logs` | `limit` up to 500 (default 200) |
| `GET /v1/minecraft/players/online` | Online players, pages of up to 500 (`limit`); pass `nextCursor` back as `cursor` while it is present |
| `GET /v1/minecraft/punishments/types` | Types with ordinals |
| `GET /v1/minecraft/punishments/<id>` | One punishment |
| `GET /v1/minecraft/punishments/recent?hours=48` | Up to 100 recent punishments, 1 to 8760 hours |
| `POST /v1/minecraft/punishments/dynamic` | Create; returns `punishmentId` |
| `POST /v1/minecraft/punishments/<id>/pardon` `/note` `/evidence` `/duration` `/toggle` | Modify (`toggle` takes `option` `ALT_BLOCKING` or `STAT_WIPE` and `enabled`) |
| `POST /v1/minecraft/players/pardon` | Pardon by player name |
| `POST /v1/minecraft/players/<uuid>/notes` | Note or warning |
| `GET /v1/minecraft/tickets?status=&type=&limit=50` | Tickets (limit up to 100) |
| `GET /v1/minecraft/tickets/<id>` | One ticket |
| `POST /v1/minecraft/tickets` | Create (`creatorUuid`, `type` such as `support`, `bug`, `player`, `chat`, `subject`, `description`); returns `ticketId`, `publicUrl` |
| `GET /v1/minecraft/staff/permissions` | Linked staff with ids, roles, and permissions |
| `GET /v1/minecraft/dashboard/stats` | Counts for a status display |
| `GET /v1/health` | `{"status":"ok","timestamp":<epoch ms>}` |

Do not call `/players/login`, `/players/disconnect`, `/players/sync`, `/punishments/acknowledge`, or `/v2/minecraft/startup`: they change how the panel sees online players and enforcement.

## Responses and errors

Success bodies carry `"status": 200` and the data. Errors (those from filters, such as 401, 413, 429, and 503, also carry `"success": false`):

```json
{"status":403,"error":"You do not have permission to do this","message":"You do not have permission to do this","code":"PERMISSION_DENIED"}
```

| Status | Code | Meaning |
|---|---|---|
| 400 | `INVALID_ARGUMENT`, `MINECRAFT_ISSUER_REQUIRED`, `PLAYER_NOTE_KIND_REQUIRED` | Bad input |
| 401 | `UNAUTHENTICATED` | Missing, wrong or revoked key |
| 403 | `PERMISSION_DENIED`, `SERVER_KEY_SCOPE` | Issuer lacks the permission, or key scope |
| 404 | `NOT_FOUND` | Player or record not found |
| 409 | `CONFLICT`, `VERSION_MISMATCH` | Conflict; `VERSION_MISMATCH` only when you send `X-Modl-Plugin-Version` (do not) |
| 413 | `PAYLOAD_TOO_LARGE` | Body over 16 MiB |
| 429 | `RATE_LIMITED` | Wait `Retry-After` seconds (`retryAfterSeconds` in the body) |
| 503 | `DATABASE_BUSY`, `DATABASE_UNAVAILABLE`, `DATABASE_DISK_FULL`, `UPDATE_STARTING` | Retry later |

Branch on `code`, never on the message. Some v1 routes answer 400 and 404 with only `status` and `message`.

## Rate limits

Minecraft routes count per key, so a script with its own key has its own budget. Before the key check, one client address may send 30,000 requests per minute and at most 1,000 with a wrong key; beyond that it gets 429, even with a valid key.

| Routes | Limit |
|---|---|
| `/v1`, `/v2`, `/v3` `minecraft` | 6000 per minute per key |
| `/v1/minecraft/players/login` | 10000 per minute per key |
| `/v1/minecraft/panel/**` | 300 per minute per key |
| `/v1/public/**` | 60 per minute per address (ticket creation 2, other ticket writes 10) |

`X-RateLimit-Remaining` shows what is left.

## Realtime

The websocket at `/v1/realtime/ws` is for the plugin and the panel only. For notifications use Discord webhooks (Settings > General > Webhooks).

## Stability

- Routes and fields may change in any release, including patch releases. Pin scripts to a modl version and test after updating.
- Unknown JSON fields are ignored on input; read only the fields you need.
