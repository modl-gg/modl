import { pullIfMissing, removeSidecar, runDocker, startSidecar } from "./docker";
import { randomSecret, sleep } from "./util";

const CONTAINER = "modl-testing-mariadb";
const IMAGE = "mariadb:11.4";
const ALIAS = "modl-mariadb";
const PORT = 3306;
const DATA_MOUNT = "/var/lib/mysql";
const READY_TIMEOUT_MS = 120_000;
const PULL_TIMEOUT_MS = 600_000;
const MEMORY_LIMIT = "768m";
const DATA_SIZE = "256m";

export interface MariaDbConnection {
  host: string;
  port: number;
  database: string;
  username: string;
  password: string;
}

async function awaitReady(connection: MariaDbConnection): Promise<void> {
  const deadline = Date.now() + READY_TIMEOUT_MS;
  while (Date.now() < deadline) {
    const probe = await runDocker(
      ["exec", CONTAINER, "mariadb", "-h", "127.0.0.1", "-P", String(PORT), "-u", connection.username, `-p${connection.password}`,
        "-e", "SELECT 1", connection.database],
      { allowFailure: true, timeoutMs: 10_000 },
    );
    if (probe.code === 0) return;
    await sleep(1000);
  }
  throw new Error(`${CONTAINER} did not accept connections within ${READY_TIMEOUT_MS.toString()}ms`);
}

export class MariaDbSidecar {
  readonly connection: MariaDbConnection;

  private constructor(connection: MariaDbConnection) {
    this.connection = connection;
  }

  static async pull(): Promise<void> {
    await pullIfMissing(IMAGE, PULL_TIMEOUT_MS);
  }

  static async start(project: string, database: string, username: string): Promise<MariaDbSidecar> {
    const connection: MariaDbConnection = { host: ALIAS, port: PORT, database, username, password: randomSecret(16) };
    await startSidecar({
      name: CONTAINER,
      image: IMAGE,
      network: `${project}_default`,
      alias: ALIAS,
      user: "mysql",
      volumes: [],
      tmpfs: [`${DATA_MOUNT}:rw,mode=1777,size=${DATA_SIZE}`, "/run/mysqld:rw,mode=1777,size=1m", "/tmp:rw,mode=1777,size=64m"],
      memory: MEMORY_LIMIT,
      environment: {
        MARIADB_ROOT_PASSWORD: randomSecret(16),
        MARIADB_DATABASE: database,
        MARIADB_USER: username,
        MARIADB_PASSWORD: connection.password,
      },
      command: ["mariadbd"],
    });
    await awaitReady(connection);
    return new MariaDbSidecar(connection);
  }

  static async stop(): Promise<void> {
    await removeSidecar(CONTAINER);
  }
}
