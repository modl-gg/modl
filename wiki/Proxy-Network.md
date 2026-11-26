# Proxy Network

[![Video: Connect a Velocity network](https://img.youtube.com/vi/oBUhASI2hiU/maxresdefault.jpg)](https://youtu.be/oBUhASI2hiU)

A Velocity or BungeeCord proxy with Paper, Folia, or Spigot servers behind it.

- The proxy runs the modl core, `embedded` or `remote` ([Separate Panel](Separate-Panel)): bans, mutes, kicks, commands, and menus.
- Each backend server runs `behind-proxy` with its own server key: staff mode, freeze, anticheat reports, replays, stat wipes, and signed-chat mute enforcement. It needs no extra port.

## 1. Forwarding

| Proxy | Proxy setting | Each backend server |
|---|---|---|
| Velocity | `player-info-forwarding-mode = "modern"` in `velocity.toml`; names under `[servers]` | `proxies.velocity.enabled: true` and the secret in `config/paper-global.yml` (`settings.velocity-support` in `paper.yml` on older Paper), `online-mode=false` in `server.properties` |
| BungeeCord | `ip_forward: true` in `config.yml`; names under `servers:` | `settings.bungeecord: true` in `spigot.yml`, `online-mode=false` |

Firewall the backend servers so only the proxy can connect; a player who joins a backend directly skips the proxy's ban checks.

A tunnel or DDoS protection in front of the proxy needs real-IP forwarding (PROXY protocol); otherwise modl prints `Players are connecting from private addresses, so their real IPs are not being forwarded` and does not link accounts by those addresses.

Velocity modern forwarding needs Paper 1.13 or newer. Paper 1.8 to 1.12 networks use BungeeCord forwarding.

## 2. The proxy

1. Put `modl-<version>.jar` in the proxy's `plugins/` (and PacketEvents for in-game menus).
2. Use Java 21 or newer (25 for Velocity 4) and at least 2 GB of heap.
3. Give the panel its address:

   | Panel address | Do |
   |---|---|
   | Domain on Cloudflare? Use the [built-in Cloudflare Tunnel](Panel-Port-and-Tunnels#built-in-cloudflare-tunnel) (no open port) | Set `embedded.tunnel` and `embedded.public-url` on the proxy; backend servers use that `https://` address as `remote.url` |
   | Open port | Allocate and open port 8080 for the panel |
4. Start the proxy and finish [First Run](First-Run) with the setup code from the proxy console.

For a separately hosted panel, set `mode: remote` on the proxy with a "Standalone or proxy" key ([Separate Panel](Separate-Panel#connect-servers)).

## 3. Each backend server

1. In the panel open Settings > General > Minecraft servers (or the Servers step of the setup wizard). While the proxy is connected, every server in its config is listed with its address; Create key on its row creates its key.
2. A server the proxy does not list: Minecraft servers > Add server. Name: exactly this server's name in the proxy config (`lobby`, `survival`). Where: Behind a proxy. Create the key.
3. Download `connection.yml` (the key is shown once). It looks like:
   ```yaml
   mode: behind-proxy

   remote:
     url: "https://panel.example.com"
     api-key: "<server key>"
   ```
4. Put PacketEvents and `modl-<version>.jar` in the server's `plugins/`, and save the file as `plugins/modl/connection.yml`. A server with forwarding on that starts without it creates the file with `mode: behind-proxy` and an empty key, lets players join and waits; paste the key into `remote.api-key` and run `/modlserver reload`.
5. In `plugins/modl/server-features.yml` set `server-name` to the same name. Start the server.
6. The console prints `[network] Connected to the modl network at https://panel.example.com as survival.` and the server shows as Behind a proxy and Connected under Settings > General > Minecraft servers.

`remote.url` must be reachable from the backend server. With the built-in Cloudflare Tunnel, use its `https://` address. With an embedded panel on the proxy and no domain, use the proxy's private address, such as `http://10.0.0.5:8080`. Use HTTPS when the traffic leaves your private network. A reverse proxy in front of the panel must pass websocket upgrades on `/v1/realtime/ws`.

While the panel is unreachable or the key is rejected, players can still join a backend server; its staff tools stay off until it connects.

## Options

- `/proxycmd <command>` lets a backend server's console run a modl command (only modl commands) on the proxy. It is off for each key until you allow it in the key list.
- Revoking a key disconnects that server at once.
- Give every server its own key. Two servers with the same key or name displace each other: each prints `Another server connected to the modl network` and reconnects after 60 seconds.
- More than one proxy: host the panel apart and run every proxy in `remote` with its own "Standalone or proxy" key.
- A backend server's messages follow `locale` in its own `plugins/modl/config.yml` (add `locale: de_DE`; default `en_US`) and its `locale/<locale>.yml`; `/modlserver reload` applies a change.

## Signed chat and mutes

Clients 1.19.1 and newer sign chat, so a proxy cannot cancel it. modl on each backend server drops the messages the proxy denied (through its built-in SignedVelocity listeners on Paper 1.19.1+ with Velocity modern forwarding, otherwise through PacketEvents). A backend server without modl lets muted players on new clients chat.
