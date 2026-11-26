# HTTPS and Reverse Proxy

Use a domain with HTTPS for any panel reached over the internet: passkeys need HTTPS (or `localhost`), and over plain HTTP keys and passwords travel in clear.

## Steps

1. Point a DNS record such as `panel.example.com` at the machine.
2. Put a reverse proxy in front of the panel port (examples below).
3. Tell modl its address and to trust the proxy:

   | Setup | Set |
   |---|---|
   | Plugin (`connection.yml`) | `embedded.public-url: "https://panel.example.com"` and `embedded.trust-proxy-headers: true` |
   | Docker or jar (`.env`) | `MODL_PUBLIC_URL=https://panel.example.com` and `MODL_TRUST_PROXY_HEADERS=true` |

4. Keep the panel port closed to the internet: `embedded.bind-address: "127.0.0.1"` or `MODL_BIND_ADDRESS=127.0.0.1` when the proxy is on the same machine; for Docker change the port mapping in `docker-compose.yml` to `"127.0.0.1:${MODL_PORT:-8080}:8080"`.
5. Restart modl. Update `remote.url` on your servers to the new address.

With `trust-proxy-headers` on, modl trusts forwarded headers from loopback, private, link-local, and carrier-grade NAT addresses (`trusted-proxy: private`, the default), so run the proxy on the same machine or private network. `embedded.trusted-proxy: "loopback"` or `MODL_TRUSTED_PROXY=loopback` trusts only the same machine (not with Docker). modl does not send `Strict-Transport-Security`; add it in the proxy if you want it.

Domain on Cloudflare? Use the [built-in Cloudflare Tunnel](Panel-Port-and-Tunnels#built-in-cloudflare-tunnel) (no open port). No domain or no open port: use an HTTPS tunnel instead ([Panel Port and Tunnels](Panel-Port-and-Tunnels)).

## Caddy

Caddy gets the certificate and passes websockets by itself.

```
panel.example.com {
    reverse_proxy 127.0.0.1:8080
    request_body {
        max_size 500MB
    }
}
```

## nginx

```nginx
server {
    listen 443 ssl;
    server_name panel.example.com;
    ssl_certificate     /etc/letsencrypt/live/panel.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/panel.example.com/privkey.pem;

    client_max_body_size 500m;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_read_timeout 120s;
    }
}
```

## Requirements for any proxy or CDN

- Pass cookies and all request headers unchanged, including `X-Modl-Session-Key` and `X-API-Key`.
- Allow websocket upgrades on `/v1/realtime/ws`. Without them the panel misses live updates and servers behind a proxy keep their staff tools off.
- Accept request bodies up to `MODL_IMPORT_MAX_UPLOAD_MB` (500 MB) if you import from another plugin through it.
- Changing the domain later invalidates passkeys registered on the old one; staff add them again.
