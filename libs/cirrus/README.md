# Cirrus

Fork of [Simplix-Softworks/Cirrus](https://github.com/Simplix-Softworks/Cirrus). The upstream commit it was forked from is not recorded.

Local changes:

- Velocity and BungeeCord menus use [PacketEvents](https://github.com/retrooper/packetevents) instead of Protocolize. PacketEvents is not bundled; the server must have it.
- No Fabric module.
- Clients 1.8.8 and newer.

| Module | Platform |
|---|---|
| `cirrus-api` | platform-independent API |
| `cirrus-common` | shared implementation |
| `cirrus-spigot` | Spigot, Paper, Folia |
| `cirrus-bungeecord` | BungeeCord |
| `cirrus-velocity` | Velocity |

The root build includes it as `:libs:cirrus:cirrus-*` and the platform modules shade it into the plugin. Build alone: `./gradlew -p libs/cirrus build`.

License: AGPL-3.0 as part of modl ([`LICENSE`](../../LICENSE)). Upstream is MIT; its notice is in [`LICENSE-UPSTREAM`](LICENSE-UPSTREAM).
