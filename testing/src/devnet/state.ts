import { existsSync } from "node:fs";
import { z } from "zod";
import { randomSecret, readJson, writeJson } from "../lib/util";
import { DEV_SERVER_NAMES, DEV_STATE_FILE, PREFERRED_PORTS, type DevServerName } from "./layout";

export interface DevPorts {
  join: number;
  botGate: number;
  panel: number;
  logs: number;
}

export interface DevSession {
  cookies: Record<string, string>;
  sessionKey: string | null;
  lastLoginCodeAt: number | null;
}

export interface InstalledRelease {
  modlVersion: string;
  pluginSha256: string;
  embeddedSha256: string;
  installedAt: string;
}

export interface DevState {
  createdAt: string;
  forwardingSecret: string;
  ports: DevPorts;
  ownerEmail: string | null;
  session: DevSession;
  installed: InstalledRelease | null;
  seeded: DevServerName[];
}

const devStateSchema: z.ZodType<DevState> = z.object({
  createdAt: z.string(),
  forwardingSecret: z.string(),
  ports: z.object({ join: z.number(), botGate: z.number(), panel: z.number(), logs: z.number() }),
  ownerEmail: z.string().nullable(),
  session: z.object({
    cookies: z.record(z.string(), z.string()),
    sessionKey: z.string().nullable(),
    lastLoginCodeAt: z.number().nullable(),
  }),
  installed: z
    .object({ modlVersion: z.string(), pluginSha256: z.string(), embeddedSha256: z.string(), installedAt: z.string() })
    .nullable(),
  seeded: z.array(z.enum(DEV_SERVER_NAMES)),
});

export function readDevState(): DevState | null {
  return existsSync(DEV_STATE_FILE) ? readJson(DEV_STATE_FILE, devStateSchema.nullable(), null) : null;
}

export function newDevState(): DevState {
  return {
    createdAt: new Date().toISOString(),
    forwardingSecret: randomSecret(),
    ports: { ...PREFERRED_PORTS },
    ownerEmail: null,
    session: { cookies: {}, sessionKey: null, lastLoginCodeAt: null },
    installed: null,
    seeded: [],
  };
}

export function saveDevState(state: DevState): void {
  writeJson(DEV_STATE_FILE, state);
}
