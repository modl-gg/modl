# Local development

From the repository root, in two terminals:

```bash
dev/server.sh
dev/panel.sh
```

- `dev/server.sh`: `./gradlew :backend:bootRun -Pmodl.skipPanel=true` on port 18080, SQLite and uploads in `dev/.data/`. Extra arguments go to Gradle.
- `dev/panel.sh`: Vite on http://localhost:3000, proxying `/v1` (HTTP and websocket) to the backend. Runs `npm ci` when `node_modules` is missing or older than `package-lock.json`.

Open http://localhost:3000. The backend log prints the setup code and the `/setup` address; the code is also in `dev/.data/setup-code.txt`. Login codes go to the backend log until email is set up.

| Variable | Default | Script |
|---|---|---|
| `MODL_PORT` | `18080` | `server.sh` |
| `MODL_DATA_DIR` | `dev/.data` | `server.sh` |
| `MODL_PUBLIC_URL` | `http://localhost:3000` | `server.sh` |
| `MODL_DATABASE_URL`, `MODL_DATABASE_USERNAME`, `MODL_DATABASE_PASSWORD` | unset (SQLite) | `server.sh` |
| `MODL_DEV_BACKEND_PORT` | `18080` | `panel.sh`; must match the backend port |

`bootRun` runs in `backend/`, and a `.env` there overrides the environment, so remove any `backend/.env`.

Reset with `rm -rf dev/.data`. A `dev/.data` from before the SQL storage, or before uploads had `.meta` files, needs this reset.
