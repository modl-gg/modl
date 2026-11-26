# Permissions

Permissions belong to roles, edited in Panel > Settings > Staff > Roles or the in-game staff menu. They apply in the panel and in game; Bukkit permission plugins do not grant them. A permission grants everything under it (`punishment.modify` includes `punishment.modify.pardon`), except the two ticket note permissions.

- The owner always has role Super Admin and every permission, with no staff record.
- Roles are ranked: Super Admin 0, Admin 1, Moderator 2, Helper 3, custom roles after. Staff manage only roles and members ranked below their own, and grant only permissions they hold.
- Default roles are created once; later edits are yours.
- A role needs a name; its description is optional.

## Staff team

[![Video: Set up your staff team](https://img.youtube.com/vi/4F8k7IQJkG0/maxresdefault.jpg)](https://youtu.be/4F8k7IQJkG0)

1. Settings > Staff > Members > Invite staff: their email and a role. Without SMTP the invitation link is shown in the panel and printed to the console; it lasts 24 hours.
2. Settings > Staff > Roles: what each role may do (table below).
3. Settings > Account > Security: set up an authenticator app and save the recovery codes.
4. Add passkey in the same place (needs HTTPS or `localhost`).
5. Settings > Staff > Sign-in: Require two-factor authentication for all staff, then Save. Staff without an authenticator or passkey add one at their next sign-in.
6. Sign in with a passkey next time ([Email and Sign-in](Email-and-Sign-in)).

## Default roles

| Permission | Allows | Super Admin | Admin | Moderator | Helper |
|---|---|---|---|---|---|
| `admin.settings.view` | View settings (all `view.*` below) | yes | yes | | |
| `admin.settings.view.punishments` | View punishment types and thresholds | yes | yes | | |
| `admin.settings.view.content` | View knowledgebase, homepage cards, public site theme, media | yes | yes | | |
| `admin.settings.view.storage` | View Settings > System > Storage | yes | yes | | |
| `admin.settings.modify` | Change settings: webhooks, ticket forms, labels, quick responses, AI moderation (all `modify.*` below) | yes | | | |
| `admin.settings.modify.punishments` | Change punishment types and thresholds | yes | | | |
| `admin.settings.modify.content` | Change knowledgebase, homepage cards, public site theme, media | yes | | | |
| `admin.settings.modify.storage` | Change upload limits and backups, move uploads, retry a move | yes | | | |
| `admin.staff.manage` | Staff tab (both below) | yes | yes | | |
| `admin.staff.manage.members` | Invite, remove, and reassign staff | yes | yes | | |
| `admin.staff.manage.roles` | Create, edit, and delete roles, in the panel and in game | yes | yes | | |
| `admin.audit.view` | All three below | yes | yes | | |
| `admin.audit.view.dashboard` | Dashboard | yes | yes | | |
| `admin.audit.view.analytics` | Analytics | yes | yes | | |
| `admin.audit.view.logs` | Audit log | yes | yes | | |
| `admin.audit.rollback` | Roll back punishments and run bulk audit actions (rollback all, by date range, bulk pardon, bulk expiry); owner only, cannot be granted | yes | | | |
| `punishment.view` | Player profiles, punishments, linked accounts; `/inspect`, `/history`, `/notes`, `/alts` | yes | yes | yes | yes |
| `punishment.modify` | All `modify.*` below; edit player records; label replays | yes | yes | yes | |
| `punishment.modify.pardon` | Pardon; `/pardon`, `/unban`, `/unmute`; approve an appeal that pardons; with `duration`, shorten | yes | yes | yes | |
| `punishment.modify.duration` | Lengthen; shortening also needs `pardon` | yes | yes | yes | |
| `punishment.modify.note` | Notes on punishments | yes | yes | yes | |
| `punishment.modify.evidence` | Add evidence | yes | yes | yes | |
| `punishment.modify.options` | Toggle alt-blocking and stat wipe | yes | yes | yes | |
| `punishment.modify.tickets` | Link tickets to punishments | yes | yes | yes | |
| `punishment.apply.<ordinal>` | Issue that punishment type (one per type) | yes | yes | all but 5 | |
| `player.note` | Notes on player profiles | yes | yes | yes | yes |
| `player.warn` | `/warn` | yes | yes | yes | yes |
| `replay.share` | Create and revoke public replay links ([Replays](Replays#share-links)); also needs the player view rule | yes | yes | | |
| `ticket.view.all` | View tickets and appeals, without internal notes; receive ticket and report alerts in game | yes | yes | yes | yes |
| `ticket.view.all.notes` | View internal staff notes on tickets (granted on its own) | yes | yes | yes | yes |
| `ticket.reply.all` | Reply to tickets, create tickets | yes | yes | yes | yes |
| `ticket.reply.all.notes` | Add internal staff notes to tickets (granted on its own) | yes | yes | yes | yes |
| `appeal.modify` | Reply to and decide appeals | yes | yes | yes | yes |
| `ticket.close.all` | Close, reopen, and lock tickets; resolve and dismiss reports in game | yes | yes | yes | |
| `ticket.manage` | Both below | yes | yes | yes | |
| `ticket.manage.tags` | Add and remove ticket tags | yes | yes | yes | |
| `ticket.manage.hide` | Hide tickets | yes | yes | yes | |
| `staff.chat.toggle` | `/chat toggle` | yes | yes | | |
| `staff.chat.clear` | `/chat clear` | yes | yes | | |
| `staff.chat.slow` | `/chat slow` | yes | yes | | |
| `staff.modactions` | `/staffmode`, `/vanish`, `/freeze`, `/target`, `/replay` | yes | yes | yes | |
| `staff.intercept` | `/interceptnetworkchat` | yes | yes | | |
| `staff.chatlogs` | `/chatlogs` | yes | yes | yes | |
| `staff.commandlogs` | `/commandlogs` | yes | yes | yes | |

Owner only, whatever the role: Settings > General > Panel (including server icons), the API key and server keys, sign-in settings, email, player addresses, the database, updates, status, data and replay retention changes, the S3 connection and its test, removing old storage copies, the AI connection, the public site custom CSS, staff security resets, raw audit tables, setup, and imports. `/modl reload` and the in-game Reload button need the owner or the console.

Notes:
- Viewing players needs `punishment.view`, `punishment.modify`, or any `punishment.apply.<ordinal>`. The same rule opens replays; a replay is public only through a share link, which `replay.share` creates and revokes.
- Replying to an appeal also works with `ticket.reply.all`.
- Each ticket change needs its own permission: replying, assigning, and editing details `ticket.reply.all`, status and lock `ticket.close.all`, visibility `ticket.manage.hide`, tags `ticket.manage.tags`, internal notes `ticket.reply.all.notes`; bulk ticket changes need `ticket.close.all`.
- Shortening a punishment (including an appeal reduction, and switching alt-blocking off while it holds automatic linked bans) needs `punishment.modify.duration` and `punishment.modify.pardon`. The console may always shorten.
- In game, punishment, pardon, and warning notices reach only staff who can view players; ticket and report alerts only staff with `ticket.view.all`.

## Punishment types

`punishment.apply.<ordinal>` uses the type's ordinal, which never changes when a type is renamed. Defaults:

| Ordinal | Type | Kind | Command |
|---|---|---|---|
| 0 | Kick | fixed, not appealable | `/kick` |
| 1 | Manual Mute | fixed | `/mute` |
| 2 | Manual Ban | fixed | `/ban` |
| 3 | Security Ban | fixed | |
| 4 | Linked Ban | fixed, applied automatically for ban evasion | |
| 5 | Blacklist | fixed, not appealable | `/blacklist` |
| 6 | Chat Abuse | social, mute | `/punish` |
| 7 | Anti-Social | social, mute | `/punish` |
| 8 | Targeting | social, ban | `/punish` |
| 9 | Bad Content | social, ban | `/punish` |
| 10 | Bad Username | social, ban until username change | `/punish` |
| 11 | Bad Skin | social, ban until skin change | `/punish` |
| 12 | Team Abuse | gameplay, ban | `/punish` |
| 13 | Game Abuse | gameplay, ban | `/punish` |
| 14 | Cheating | gameplay, ban | `/punish` |
| 15 | Game Trading | gameplay, ban | `/punish` |
| 16 | Account Abuse | gameplay, ban | `/punish` |
| 17 | Systems Abuse | gameplay, ban | `/punish` |

In Settings > Moderation > Punishment types, types 6 and up can be renamed, edited, and deleted, and types 1 to 4 configured but not renamed or deleted; Kick and Blacklist cannot be changed. A new type gets the ordinal after the highest ever used; deleted ordinals are not reused.

A ban until skin change needs the player's Mojang skin: it is refused for players without one (offline mode, Floodgate) and ignores skins set by plugins such as SkinsRestorer. On an offline-mode server a new name is a new player with a new UUID, so a ban until username change never lifts on the old account and does not cover the new one.
