# Requirements

## Platforms

| Platform | Versions | Modes | Notes |
|---|---|---|---|
| Paper | 1.8.8 to latest | all | Main target. |
| Folia | as Paper | all | Folia has no `/reload`; restart the server instead. |
| Spigot | 1.8.8 to latest | all | Paper is recommended ([signed chat](Proxy-Network#signed-chat-and-mutes)). |
| Paper and Spigot forks (Purpur and similar) | as their base | all | Not tested separately. |
| Velocity | 3.x and 4.x | `embedded`, `remote` | Velocity 4 needs Java 25. |
| BungeeCord | current builds | `embedded`, `remote` | Forks such as Waterfall are not tested. |
| Fabric, Forge, NeoForge, Sponge, Bedrock-only servers | | | Not supported. |

Test targets: Paper 26.1.2, 1.21.11, and 1.8.8; Folia 26.1.2; Velocity 4.3.1 and 3.5.1; BungeeCord build 2111.

## Software

| Need | Where |
|---|---|
| Java 21 or newer | Every server or proxy that runs the panel (`embedded`), and for `modl-server.jar`. Servers in `remote` or `behind-proxy` run on the Java their Minecraft version needs (8 or newer). |
| At least 2 GB of heap (`-Xmx2G`) | Every server or proxy that runs the panel. modl warns at start when it has less. |
| [PacketEvents](https://modrinth.com/plugin/packetevents) 2.14.0 or newer | Required on every Paper, Folia, and Spigot server; without it modl stays off and players cannot join. Optional on proxies, where it enables in-game menus. |
| A local disk for `plugins/modl/data` | The built-in SQLite database does not work reliably on NFS or SMB. |
| Docker with Compose 2.24 or newer | Only for the Docker setup. |
| Chrome or Edge 111+, Firefox 128+, Safari 16.4+ | The panel and the public pages. |

Paper 1.8 to 1.16 cannot run on Java 21, so those servers use `remote` or `behind-proxy`.

Tests run on Linux. Windows hosts are not tested.

## Network

| Port | Direction | Notes |
|---|---|---|
| 8080 TCP (default) | players' browsers and your servers to the panel | Change with `embedded.port` or `MODL_PORT`. It must differ from the Minecraft and RCON ports. On game hosts, add an allocation ([Panel Port and Tunnels](Panel-Port-and-Tunnels)). With the built-in Cloudflare Tunnel it stays closed. |
| 7844 UDP or TCP, outbound | the panel to Cloudflare | Only with the [built-in Cloudflare Tunnel](Panel-Port-and-Tunnels#built-in-cloudflare-tunnel). |
| Your Minecraft port | players to the server or proxy | Unchanged. |

No other inbound port is needed; backend servers of a network use the same HTTP port.

Outbound HTTPS:

| Host | Why | Can be turned off |
|---|---|---|
| `github.com` (redirects to `release-assets.githubusercontent.com`) | `modl-embedded-<version>.zip`, about 12 MB, on the first start of a server that runs the panel | Yes: put the file in `plugins/modl/server/`, or set a mirror (`embedded.download-url`) |
| `repo1.maven.org` | Panel libraries, about 100 MB on the first start of a server that runs the panel; plugin libraries on every server | No; copying `plugins/modl/server/` and `plugins/modl/lib/` from a server with the same modl version and platform avoids the downloads. The Docker image and `modl-server.jar` need none. |
| `api.mojang.com`, `sessionserver.mojang.com` | Player names and skins | No |
| The IP lookup provider you pick (`proxycheck.io`, `vpnapi.io`, IPHub, or `ipapi.is`) | Country and VPN detection of new player IPs, sent by the panel, never by the plugin | Off until you pick one in setup or Settings > Moderation > Player addresses; the Test button there sends only `1.1.1.1` |
| `api.github.com`, `github.com` | Update checks, twice a day | Panel > Settings > System > Updates, `MODL_UPDATE_CHECK=false`, or `update_checker.enabled: false` in the plugin's `config.yml` |
| Your SMTP, AI provider, S3, and Discord hosts | Only when configured | |
