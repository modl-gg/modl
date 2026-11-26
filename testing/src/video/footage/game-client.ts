import { existsSync } from "node:fs";
import { join } from "node:path";
import { runProcess } from "../../lib/process";
import { ClientLog } from "./client-log";

const PRISM_INSTANCE = /-Djava\.library\.path=(.+?\/instances\/[^/]+)\/natives(?:\s|$)/;
const JAVA_COMMAND = /^\S.*?\/java\s+-/;

export interface GameClient {
  readonly pid: number;
  readonly gameDir: string;
  readonly username: string;
  readonly log: ClientLog;
}

const REAL_CLIENT_HINT = "start the Minecraft client from Prism Launcher and keep it open (see Wiki videos in the harness README)";

interface ClientProcess {
  pid: number;
  gameDir: string;
}

function clientProcesses(listing: string): ClientProcess[] {
  return listing.split("\n").flatMap((line) => {
    const match = /^\s*(\d+)\s+(.*)$/.exec(line);
    const pid = match?.[1];
    const args = match?.[2];
    if (pid === undefined || args === undefined || !JAVA_COMMAND.test(args)) return [];
    const instance = PRISM_INSTANCE.exec(args)?.[1];
    return instance === undefined ? [] : [{ pid: Number(pid), gameDir: join(instance, "minecraft") }];
  });
}

export async function findGameClient(): Promise<GameClient> {
  const listing = await runProcess("ps", ["-axww", "-o", "pid=,args="]);
  const found = clientProcesses(listing.stdout).filter((candidate) => existsSync(join(candidate.gameDir, "logs", "latest.log")));
  const client = found[0];
  if (client === undefined) throw new Error(`no Minecraft client is running; ${REAL_CLIENT_HINT}`);
  if (found.length > 1) throw new Error(`${String(found.length)} Minecraft clients are running (${found.map((candidate) => String(candidate.pid)).join(", ")}); keep only one open`);
  const log = new ClientLog(join(client.gameDir, "logs", "latest.log"));
  return { ...client, username: log.username(), log };
}
