# Replays

Paper, Folia, and Spigot servers record what a player's client received and upload a short replay when it is captured. Staff watch replays in the panel. Proxies record nothing; behind a proxy every backend server records its own players.

## Settings

Settings > Moderation > Replays (view `admin.settings.view`, change `admin.settings.modify`; changes are audited). Every connected server applies a change within a second, without a restart. Until a server has received the settings it records nothing. The setup wizard asks for the first choice.

| Setting | Default | Meaning |
|---|---|---|
| Record replays | Off | Needs PacketEvents on the server. Off stops and discards every recording. |
| Record every player | On | Record each player from login while replays are on. Off ends the recordings that nothing else holds (an anticheat flag or `/replay record`). |
| Start recording at an anticheat flag | On | Start recording a player at their first anticheat flag; needs replays and the anticheat integration. |
| Anticheat integration | Off | Hook GrimAC, Vulcan, and Polar: file reports when a player passes the violation threshold, with a replay when one is recorded. |
| Name in reports | `Anti-cheat` | Anticheat name in auto reports (1 to 32 characters). |
| History window | 60 s | Seconds in each replay (10 to 300). |
| Radius | 64 | Blocks around the player in each replay (16 to 128); a change applies to running recordings too. |
| Memory per server | 64 MB | Memory all recordings of one server share (16 to 1024), at most a quarter of the server's maximum heap. When it is full, every recording gives up the same share: chunks outside the radius first, then its oldest history. A new recording starts only while each recording keeps at least 2 MB. When none is left, a recording that staff asked for (`/replay record`, also on a player already recorded) or that an anticheat flag asked for takes the place of a recording that was asked for less: the oldest Record every player recording first, then the oldest flag recording. Otherwise the player is not recorded and the console says once that the budget is full. |

The page has the sections Recording, Anticheat, and Retention. It lists each connected server that reports its features (proxies and older plugins do not) with a Replays mark when it can record, an Anticheat mark when its hooks run, and the anticheats it found. Retention (Delete old replays automatically, Delete after) is the same setting as in Settings > System > Data retention and can be changed in either place by the owner. A replay with an open share link, or attached to an open ticket or to the ticket of a punishment in force or not started, is kept until the link is revoked or expires and both end. Report thresholds and the cooldown stay in `server-features.yml` ([Plugin Files](Plugin-Files#server-featuresyml)).

## Recording

| Topic | Behavior |
|---|---|
| What is recorded | What the recorded player's client received within the radius: blocks with light, players (name, skin), mobs and items, equipment, block changes, chat, and the player's own health, food, armor, XP, effects, and inventory. Vanished staff stay hidden. Keyframes list the recorded player and the players near them, so a full tab list never makes a replay too large. |
| When | From login with Record every player, from the first anticheat flag, or on demand with `/replay record <player>` until `/replay stop <player>` or logout. A recording runs while any of its reasons holds it: Record every player, an anticheat flag, or `/replay record`. `/replay stop` removes only the `/replay record` reason and turning off Record every player removes only that reason, so a recording that a flag or `/replay record` still holds goes on. `/replay status [player]` shows whether a player is recorded, why, for how long, and the memory used. A recording that starts after login takes the players and entities the client tracks from the server, without re-sending them; the blocks fill in as the client receives chunks, except for `/replay record`, which re-sends the chunks within the radius to that player. Behind a proxy the commands run on the proxy and reach the player's server. A capture (`/replay capture`, a cheating report, an anticheat report) uploads the last history window and recording goes on. |
| Server cost | Nothing runs for players who are not recorded. Recorded packets are copied on the network thread and compressed on a background thread; nothing runs on the main thread except a short setup when recording starts after login. |
| Local files | A replay is written to `plugins/modl/replays/` (readable only by the server's user on Linux and macOS) and deleted once its upload succeeded or failed; a capture while the server is not connected to the panel is refused. Leftovers are deleted at startup and every 5 minutes: files older than a day, unfinished files older than an hour, and, while the folder holds more than 256 MB, the oldest files older than an hour. |
| Size | A capture always fits the upload check below: when it would not fit, the replay keeps the newest half of its history window (down to 5 seconds), a smaller radius (down to 16 blocks), or both, and a capture that does not fit even then is refused. |
| Upload check | The panel checks every uploaded replay (at most 10 MB, 256 MB unpacked, and bounded sections, events, keyframes, block states, strings, and block entities) before it keeps it; a file that fails the check is deleted. While the panel is checking other replays or cannot read the storage, the plugin retries the upload confirmation for a few minutes. |
| Versions | Paper, Folia, and Spigot 1.8.8 to the latest release; block, entity, and item names are stored version-neutral. |

## Viewing

Staff open a replay over its ticket or from the player profile; viewing needs the player view rule ([Permissions](Permissions)). Replay files are served through the panel (`/v1/panel/replays/<id>/file`) and share links, and their storage keys are never shown. Local storage refuses the replay folder on its storage URLs, and modl never builds a storage URL for a replay. On S3 the `modl_instance/replays/` prefix must not be public-read: keep the bucket private, or exclude that prefix from any public-read policy. The viewer loads the textures of the recorded version from Mojang's servers into the browser; modl's server never stores Mojang files.

## Share links

Share replay in the replay window header creates a link for people without a panel account (`replay.share` plus the player view rule; new installs grant it to Super Admin and Admin). Choose Valid for (1 day, 7 days, 30 days, or Never) and Create link; the link is shown once with a copy button.

| Topic | Behavior |
|---|---|
| Link | `<panel URL>/replay/<token>`; the token is shown once and only its hash is stored. Anyone with the link can watch the replay. |
| Shown | The server icon, the player name, the date, and the Minecraft version. No ticket, staff, IP addresses, or replay id. |
| Revoke | The share dialog lists each link with its creator, creation, expiry, views, and last view; the newest closed links stay listed. Revoking (after a confirmation) ends a link at once. A deleted replay ends all its links. While a link is open, retention keeps its replay. |
| Views | One view per opening of the link per network (an IP address, an IPv6 /64) every 10 minutes, written once a minute; loading or seeking in the file does not count. |
| Limits | 25 open links per replay (the 25 newest closed links stay listed); 30 new links per hour per staff member. Expired, revoked, and unknown links all answer 404 `REPLAY_SHARE_NOT_FOUND`. |
| Audit | Creating and revoking a link are audit log entries. |
