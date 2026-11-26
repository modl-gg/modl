import type { BootContext, BootStep } from "./types";

export interface OwnerApiKey {
  apiKey: string;
  step: BootStep;
}

export async function ownerSession(context: BootContext): Promise<BootStep> {
  const started = Date.now();
  const session = await context.instance.ensureOwnerSession();
  return { step: "owner session", seconds: (Date.now() - started) / 1000, detail: { ownerSession: session.path } };
}

export async function ownerApiKey(context: BootContext): Promise<OwnerApiKey> {
  const started = Date.now();
  const session = await context.instance.ensureOwnerSession();
  const apiKey = await context.instance.revealApiKey();
  return { apiKey, step: { step: "owner session and API key", seconds: (Date.now() - started) / 1000, detail: { ownerSession: session.path } } };
}
