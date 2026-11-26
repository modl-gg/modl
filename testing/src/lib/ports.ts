import { connect } from "node:net";

const PROBE_TIMEOUT_MS = 600;

export async function portInUse(port: number): Promise<boolean> {
  return new Promise((resolve) => {
    const socket = connect({ host: "127.0.0.1", port });
    socket.setTimeout(PROBE_TIMEOUT_MS);
    socket.once("connect", () => {
      socket.destroy();
      resolve(true);
    });
    socket.once("timeout", () => {
      socket.destroy();
      resolve(false);
    });
    socket.once("error", () => resolve(false));
  });
}

export async function busyPorts(ports: readonly number[]): Promise<number[]> {
  const checks = await Promise.all(ports.map(async (port) => ((await portInUse(port)) ? port : null)));
  return checks.filter((port): port is number => port !== null);
}

const SEARCH_SPAN = 40;

export async function nearestFreePort(preferred: number, reserved: ReadonlySet<number>): Promise<number> {
  for (let distance = 0; distance <= SEARCH_SPAN; distance += 1) {
    for (const candidate of distance === 0 ? [preferred] : [preferred + distance, preferred - distance]) {
      if (candidate <= 1024 || candidate > 65535 || reserved.has(candidate)) continue;
      if (!(await portInUse(candidate))) return candidate;
    }
  }
  throw new Error(`no free port within ${SEARCH_SPAN} of ${preferred}`);
}
