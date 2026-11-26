# Single Server

[![Video: Install on one Paper server](https://img.youtube.com/vi/6q6vOZICUq4/maxresdefault.jpg)](https://youtu.be/6q6vOZICUq4)

One Paper, Folia, or Spigot server that runs the panel itself (`mode: embedded`). It needs Java 21, 2 GB of heap, PacketEvents, and one free port ([Requirements](Requirements)).

## Steps

1. Put PacketEvents and `modl-<version>.jar` in `plugins/`.
2. Give the panel its own port, 8080 by default and never the Minecraft port.

   | Panel address | Do |
   |---|---|
   | Domain on Cloudflare? Use the [built-in Cloudflare Tunnel](Panel-Port-and-Tunnels#built-in-cloudflare-tunnel) (no open port) | Set `embedded.tunnel` and `embedded.public-url`; no allocation needed |
   | Open port | On Pterodactyl and similar hosts, add an allocation and set it as `embedded.port` ([Panel Port and Tunnels](Panel-Port-and-Tunnels)) |
3. Start the server. The first start downloads the panel (`modl-embedded-<version>.zip`, about 12 MB, from GitHub) and about 100 MB of libraries (Maven Central), then prints:
   ```
   [modl] Setup code: ABCD-EFGH. Open http://<this server's address>:8080/setup and enter it (valid 1 hour).
   [modl] The built-in panel is listening on port 8080. It opens only if your host forwards port 8080 to this server; ...
   [modl] modl is running (Spigot).
   ```
   Until the last line appears, joining players are asked to try again in a moment.
4. Continue with [First Run](First-Run).

Without GitHub access, put `modl-embedded-<version>.zip` from the same release in `plugins/modl/server/` before the first start.

## Other port or address

Edit `plugins/modl/connection.yml` and restart:

```yaml
mode: embedded
embedded:
  port: 8080
  bind-address: "0.0.0.0"
  public-url: ""
```

- `port`: the panel port.
- `bind-address`: `127.0.0.1` when a reverse proxy on the same machine is the only way in.
- `public-url`: the address players and staff open, such as `https://panel.example.com`. Empty uses the address you finish setup from. Setting it locks the field in the panel.

Domain on Cloudflare? Use the [built-in Cloudflare Tunnel](Panel-Port-and-Tunnels#built-in-cloudflare-tunnel) (no open port).

Domain and HTTPS: [HTTPS and Reverse Proxy](HTTPS-and-Reverse-Proxy). More servers later: [Proxy Network](Proxy-Network), or `mode: remote` ([Separate Panel](Separate-Panel#connect-servers)).

## Where things are

| Path | Holds |
|---|---|
| `plugins/modl/connection.yml` | Mode and panel settings ([Configuration](Configuration)) |
| `plugins/modl/data/` | Database, uploads, backups ([Database and Storage](Database-and-Storage)) |
| `plugins/modl/server/logs/server.log` | Full panel log |

All files: [Plugin Files](Plugin-Files).
