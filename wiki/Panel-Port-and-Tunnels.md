# Panel Port and Tunnels

The built-in panel needs its own port. It cannot share the port players join on or the RCON port; modl refuses that at start (`embedded.port is 25565, the port players join on`).

| Your situation | Use |
|---|---|
| Domain on Cloudflare | [Built-in Cloudflare Tunnel](#built-in-cloudflare-tunnel): no open port, HTTPS included |
| Game host that can add an allocation | [An open port](#game-hosts-pterodactyl-pelican-and-similar) |
| Other tunnel providers | [Other tunnels](#other-tunnels) |

## Built-in Cloudflare Tunnel

modl downloads, runs, and restarts `cloudflared` itself. The panel then listens on `127.0.0.1` only, so no port is opened or forwarded.

1. In the Cloudflare dashboard open Zero Trust > Networks > Tunnels and create a tunnel of type Cloudflared.
2. Copy the token from the install command it shows (the long text after `--token`).
3. Add a public hostname to the tunnel, such as `panel.example.com`, with service `http://localhost:8080` (the panel port; with Docker always `8080`).
4. Set these and restart:

| Setting | Plugin (`connection.yml`) | Docker or jar (`.env`) |
|---|---|---|
| Tunnel | `embedded.tunnel.provider: "cloudflare"` | `MODL_TUNNEL=cloudflare` |
| Token | `embedded.tunnel.token: "<token>"` | `MODL_TUNNEL_TOKEN=<token>` |
| Panel address | `embedded.public-url: "https://panel.example.com"` | `MODL_PUBLIC_URL=https://panel.example.com` |

The panel address must be the `https://` hostname from step 3; modl refuses to start otherwise. The console prints `Cloudflare Tunnel connected: https://panel.example.com` once the tunnel is up.

| | |
|---|---|
| Outbound access | Port 7844 to Cloudflare (UDP, or TCP as a fallback), and `github.com` for the first download |
| Without GitHub access | Put `cloudflared-linux-amd64` (or `-arm64`, `windows-amd64.exe`) from the GitHub release the download notice names into `plugins/modl/data/tunnel/` (Docker `/data/tunnel/`, jar `data/tunnel/`); modl checks its SHA-256 |
| Network defaults while on | `bind-address` `127.0.0.1`, `trust-proxy-headers` on, `trusted-proxy` `loopback`. Plugin: a `bind-address` other than `0.0.0.0` wins, the other two are fixed. Docker and jar: any `MODL_BIND_ADDRESS`, `MODL_TRUST_PROXY_HEADERS`, or `MODL_TRUSTED_PROXY` you set wins |
| Docker | The panel listens on `127.0.0.1` inside the container, so the published port no longer answers |
| Proxy networks | Backend servers use the `https://` address as `remote.url` |
| Platforms | Linux amd64 and arm64, Windows amd64 |

The token stays in the configuration: it is never shown in the panel, in `/modl status`, or in logs.

## Game hosts (Pterodactyl, Pelican, and similar)

1. In the host panel, open the server's Network (or Allocations) tab and add an allocation, for example `25601`.
2. In `plugins/modl/connection.yml` set `embedded.port: 25601` and restart.
3. Open `http://<server address>:25601/setup`.

If the host gives no extra ports, use the [built-in Cloudflare Tunnel](#built-in-cloudflare-tunnel), another tunnel (below), or run the panel separately ([Separate Panel](Separate-Panel)).

`The built-in panel is listening on port ...` only means the panel runs; on a game host modl adds a line naming the allocation to add. After setup, modl opens its own Panel URL once at start (with the built-in tunnel, once it connects) and prints `This server could not open the panel at ...` when that fails. Networks that cannot reach their own public address show this even when the page works.

## Other tunnels

A tunnel needs no inbound port. Run the agent where it can reach the panel port: on the same machine, or in a sidecar container on game hosts that allow one.

Settings for every tunnel:

| Setting | Plugin (`connection.yml`) | Docker or jar (`.env`) |
|---|---|---|
| Panel address | `embedded.public-url: "https://panel.example.com"` | `MODL_PUBLIC_URL=https://panel.example.com` |
| Use the visitor's real IP | `embedded.trust-proxy-headers: true` | `MODL_TRUST_PROXY_HEADERS=true` |
| Trust only the local agent | `embedded.trusted-proxy: "loopback"` | `MODL_TRUSTED_PROXY=loopback` |
| Keep the port private | `embedded.bind-address: "127.0.0.1"` | jar: `MODL_BIND_ADDRESS=127.0.0.1`; Docker: port mapping `"127.0.0.1:${MODL_PORT:-8080}:8080"` in `docker-compose.yml` |

Use `loopback` only when the agent runs on the same machine as the plugin or jar. With Docker, or the agent in another container, keep `private`. Without trusted proxy headers every visitor shares the agent's address, so rate limits and setup-code attempts count for all of them together.

| Tunnel | Command (panel on port 8080) | Real client IP |
|---|---|---|
| Cloudflare Tunnel, run yourself | `cloudflared tunnel run --token <token>` | yes, `X-Forwarded-For` |
| ngrok | `ngrok http 8080 --url panel.example.ngrok.app` | yes, `X-Forwarded-For` |
| Tailscale Funnel | `tailscale funnel 8080` | yes, `X-Forwarded-For` |
| playit.gg (TCP tunnel) | create a TCP tunnel to port 8080 | no; leave proxy headers off |

All four pass websockets. A TCP tunnel such as playit.gg gives a plain `http://` address, so passwords and keys travel unencrypted; prefer an HTTPS tunnel.

## Check

Panel > Settings > System > Status shows the Panel URL, the port, whether proxy headers are trusted, whether the panel answered at its own URL, and the Cloudflare Tunnel (off, connecting, connected, or not connected). `/modl status` in the server console prints the same report.
