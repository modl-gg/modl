# modl wiki

modl is a self-hosted moderation and support panel for Minecraft. One panel stores everything; the modl plugin enforces it on your servers. Downloads are on the [releases page](https://github.com/modl-gg/modl/releases).

| File | Use |
|---|---|
| `modl-<version>.jar` | The plugin for Paper, Folia, Spigot, Velocity and BungeeCord. It can run the panel inside your server or proxy. |
| `modl-embedded-<version>.zip` | The panel for that plugin. The plugin downloads it on first start; without GitHub access, put it in `plugins/modl/server/`. |
| `modl-server-<version>.jar` | The panel alone, for running it outside Minecraft. |
| `ghcr.io/modl-gg/modl` | The panel as a Docker image. |

## Pick a setup

Check [Requirements](Requirements) first.

| You run | Follow |
|---|---|
| One Paper, Folia or Spigot server | [Single Server](Single-Server) |
| A Velocity or BungeeCord network | [Proxy Network](Proxy-Network) |
| The panel in Docker or as a jar; several proxies; servers on old Java; a host without 2 GB or a free port | [Separate Panel](Separate-Panel), then the page for your servers |

On a game host or without a free port, read [Panel Port and Tunnels](Panel-Port-and-Tunnels). Every setup ends with [First Run](First-Run).

The plugin mode is set in `plugins/modl/connection.yml`:

| Mode | Where | What it does |
|---|---|---|
| `embedded` | one server or one proxy | Runs the panel inside that server. |
| `remote` | any server or proxy | Uses a panel hosted elsewhere. |
| `behind-proxy` | Paper, Folia or Spigot servers behind a proxy | Connects to the panel with its own server key; the proxy enforces bans and mutes. |
