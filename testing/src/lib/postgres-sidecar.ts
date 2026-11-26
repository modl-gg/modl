import { pullIfMissing, removeSidecar, runDocker, startSidecar } from "./docker";
import { randomSecret, sleep } from "./util";

const CONTAINER = "modl-testing-postgres";
const IMAGE = "postgres:17-alpine";
const ALIAS = "modl-postgres";
const PORT = 5432;
const DATABASE = "modl";
const ROLE = "modl";
const DATA_MOUNT = "/var/lib/postgresql/data";
const READY_TIMEOUT_MS = 90_000;
const PULL_TIMEOUT_MS = 600_000;
const MEMORY_LIMIT = "768m";
const DATA_SIZE = "256m";

export interface PostgresConnection {
  host: string;
  port: number;
  database: string;
  username: string;
  password: string;
}

async function psql(sql: string, database = "postgres"): Promise<void> {
  await runDocker(["exec", CONTAINER, "psql", "-v", "ON_ERROR_STOP=1", "-U", "postgres", "-d", database, "-c", sql], { timeoutMs: 30_000 });
}

async function awaitReady(): Promise<void> {
  const deadline = Date.now() + READY_TIMEOUT_MS;
  while (Date.now() < deadline) {
    const probe = await runDocker(["exec", CONTAINER, "pg_isready", "-h", "127.0.0.1", "-p", String(PORT), "-U", "postgres"], {
      allowFailure: true,
      timeoutMs: 10_000,
    });
    if (probe.code === 0) return;
    await sleep(1000);
  }
  throw new Error(`${CONTAINER} did not accept connections within ${READY_TIMEOUT_MS}ms`);
}

export class PostgresSidecar {
  readonly connection: PostgresConnection;

  private constructor(connection: PostgresConnection) {
    this.connection = connection;
  }

  static async pull(): Promise<void> {
    await pullIfMissing(IMAGE, PULL_TIMEOUT_MS);
  }

  static async start(project: string): Promise<PostgresSidecar> {
    const password = randomSecret(16);
    await startSidecar({
      name: CONTAINER,
      image: IMAGE,
      network: `${project}_default`,
      alias: ALIAS,
      user: "postgres",
      volumes: [],
      tmpfs: [`${DATA_MOUNT}:rw,mode=1777,size=${DATA_SIZE}`, "/var/run/postgresql:rw,mode=1777,size=1m", "/tmp:rw,mode=1777,size=64m"],
      memory: MEMORY_LIMIT,
      environment: { POSTGRES_PASSWORD: randomSecret(16), PGDATA: `${DATA_MOUNT}/pgdata` },
      command: ["postgres"],
    });
    await awaitReady();
    await psql(`CREATE ROLE ${ROLE} LOGIN PASSWORD '${password}'`);
    await psql(`CREATE DATABASE ${DATABASE}`);
    await psql(`CREATE SCHEMA ${ROLE} AUTHORIZATION ${ROLE}`, DATABASE);
    await psql(`ALTER ROLE ${ROLE} IN DATABASE ${DATABASE} SET search_path = ${ROLE}`, DATABASE);
    return new PostgresSidecar({ host: ALIAS, port: PORT, database: DATABASE, username: ROLE, password });
  }

  static async stop(): Promise<void> {
    await removeSidecar(CONTAINER);
  }

  async modlTables(): Promise<number> {
    const result = await runDocker(
      ["exec", CONTAINER, "psql", "-U", "postgres", "-d", DATABASE, "-tAc", `SELECT count(*) FROM pg_tables WHERE schemaname = '${ROLE}' AND tablename LIKE 'modl\\_%'`],
      { timeoutMs: 30_000 },
    );
    return Number(result.stdout.trim());
  }
}
