# modl

Self-hosted moderation and support panel for Minecraft servers: punishments, reports, tickets, appeals, a knowledgebase, alt detection, and replays. Staff work in a web panel; the modl plugin enforces everything on Paper, Folia, Spigot, Velocity, and BungeeCord.

Documentation: the [wiki](https://github.com/modl-gg/modl/wiki).

## Downloads

From the [releases page](https://github.com/modl-gg/modl/releases):

- `modl-3.0.0.jar`: the plugin. It can run the panel inside your server or proxy.
- `modl-embedded-3.0.0.zip`: the panel for that plugin. The plugin downloads it on first start; without GitHub access, put it in `plugins/modl/server/` yourself.
- `modl-server-3.0.0.jar`: the panel as a standalone server.
- `ghcr.io/modl-gg/modl:3.0`: the panel as a Docker image, following the 3.0 patch releases.

## Quick start

Needs Java 21 and 2 GB of heap where the panel runs, and [PacketEvents](https://modrinth.com/plugin/packetevents) on every Paper, Folia, and Spigot server ([Requirements](https://github.com/modl-gg/modl/wiki/Requirements)).

- One server: put PacketEvents and `modl-3.0.0.jar` in `plugins/`, give the panel its own open port (8080 by default), start the server, and open the `/setup` address from the console ([Single Server](https://github.com/modl-gg/modl/wiki/Single-Server)).
- A network: install the plugin on the proxy, which runs the panel, then give each backend server its own key ([Proxy Network](https://github.com/modl-gg/modl/wiki/Proxy-Network)).
- Docker or a standalone jar: [Separate Panel](https://github.com/modl-gg/modl/wiki/Separate-Panel).

## Security

- The instance API key and every "Standalone or proxy" server key are full trust: whoever holds one can act as the console or as any staff member. Keep them on your own servers and regenerate or revoke a leaked key. Servers behind a proxy use their own "Behind a proxy" key, never these.
- Use HTTPS for a panel reached over the internet ([HTTPS and Reverse Proxy](https://github.com/modl-gg/modl/wiki/HTTPS-and-Reverse-Proxy)).
- Run the same MAJOR.MINOR release on the panel and every plugin ([Updating](https://github.com/modl-gg/modl/wiki/Updating)). Releases are signed, and a Docker update pins the image digest the signed release names.
- Player IPs leave your server only after the owner picks an IP lookup provider in setup or Panel > Settings > Moderation > Player addresses.
- Upload storage has no total limit by default; on a host with a disk quota, set one in Panel > Settings > System > Storage.

## Building from source

Needs JDK 21 (Gradle uses a JDK 21 toolchain and does not download one) and Node.js 24 or newer.

```bash
npm ci
./gradlew dist
```

This writes `dist/modl-3.0.0.jar`, `dist/modl-embedded-3.0.0.zip`, and `dist/modl-server-3.0.0.jar`. The panel's source code link points to the commit it was built from (`MODL_SOURCE_COMMIT`, else the git HEAD; Docker builds have no git, so pass `--build-arg MODL_SOURCE_COMMIT=<sha>`, or set `MODL_SOURCE_COMMIT` for the compose build). A fork sets `MODL_SOURCE_REPOSITORY` to its repository URL the same way (default `https://github.com/modl-gg/modl`; GitHub Actions builds pass their own repository). A plugin jar you built accepts only the zip from the same build: put it in `plugins/modl/server/` before the first start. Docker image: `docker build -t modl-selfhost .`, or `docker compose -f docker-compose.yml -f docker-compose.build.yml up -d --build`.

## License

[AGPL-3.0](LICENSE)
