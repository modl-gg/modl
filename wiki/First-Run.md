# First Run

Claim a fresh panel and finish the setup wizard.

## Claim

1. Open the `/setup` address from the console: `http://<host>:8080/setup`, with the address players use and the panel port. With the [built-in Cloudflare Tunnel](Panel-Port-and-Tunnels#built-in-cloudflare-tunnel), it is `https://<your hostname>/setup`.
2. Enter the setup code and your email.
3. You are signed in as the owner (role Super Admin). The address you opened becomes the panel URL for in-game and email links, unless `public-url` or `MODL_PUBLIC_URL` is set.

A code is 8 characters, works once, and lasts 1 hour. Until used it is also in `setup-code.txt` (`plugins/modl/`, `data/`, or `/data/`). Restart the server that hosts the panel for a new code. After 5 wrong tries from one address that address is locked out; after 50 the code is locked for everyone.

No console access: set `embedded.admin-email` (or `MODL_ADMIN_EMAIL`) and restart. modl then writes an owner sign-in code to `setup-code.txt` that signs in only that email at `/setup`.

## Wizard

The setup code is step 1 of 13. Steps run in order and none can be skipped; optional features need an explicit choice, with nothing preselected. The last step, Finish, ends setup and opens the panel; after that `/setup` leads to the panel when you are signed in, else to the homepage. Everything can be changed later in Settings.

| Step | Does |
|---|---|
| Sign-in method | Email code or password; choosing password sets it |
| Import from modl.gg | Import a modl.gg export, or start fresh ([Importing](Importing)); other plugins are imported after setup ([Migrating from Other Plugins](Migrating-from-Other-Plugins)) |
| Server identity | Server name and icons |
| Panel address | Panel URL. Domain on Cloudflare? Use the [built-in Cloudflare Tunnel](Panel-Port-and-Tunnels#built-in-cloudflare-tunnel) (no open port); otherwise [HTTPS and Reverse Proxy](HTTPS-and-Reverse-Proxy) |
| Email | SMTP, or no email delivery ([Email and Sign-in](Email-and-Sign-in)) |
| Minecraft servers | Keys and `connection.yml` for your servers; on a proxy, its servers are listed to create their keys |
| Minecraft account | Link your account for in-game staff tools, or not |
| Staff | Invite staff ([Permissions](Permissions)), or no staff yet |
| Player addresses | IP lookups for alt detection: no lookups, proxycheck.io without a key, or a provider with a key ([Configuration](Configuration#player-addresses)) |
| AI moderation | Connect an AI provider ([AI Moderation](AI-Moderation)), or no AI moderation |
| Replays and anticheat | Replays on or off (on: record every player, or not) and the anticheat integration on or off ([Replays](Replays)); servers that already connected list the anticheats they found |
| Finish | Counts the default punishment types, ticket forms, knowledgebase categories, and homepage cards, and lists where to go next |

The default knowledgebase has the categories Rules, Guides, and News with four starter articles (Server rules, How to appeal a punishment, How to report a player, Welcome). Articles are Markdown; edit them in Settings > Public site > Knowledgebase. An import from modl.gg that brings a knowledgebase replaces them.

If the browser closes before the sign-in method is confirmed, restart for an owner sign-in code.

## Link your Minecraft account

https://github.com/user-attachments/assets/079c65cd-9dd9-4781-9b2b-e2874f04b897

In-game staff tools need a linked account.

1. Panel > Settings > Account > Profile > Link account.
2. Run `/modl link <code>` in game within 10 minutes.
3. Later, `/modl panel` gives a one-time sign-in link (2 minutes). It is refused on offline-mode servers without proxy forwarding unless `panel_login.allow_offline_mode: true` ([Plugin Files](Plugin-Files)).

## Invite staff

Settings > Staff > Members. An invitation lasts 24 hours. Without SMTP the link is shown in the panel and printed to the console; send it only to that person.

Next: [Commands](Commands), [Permissions](Permissions).
