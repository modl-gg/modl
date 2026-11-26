import type { CommandResult } from "./process";
import { pullIfMissing, runOneShot } from "./docker";
import { PANEL_JAVA_VERSION, temurinJdk } from "./java-runtime";
import { hostUser } from "./settings";

const JDK_IMAGE = temurinJdk(PANEL_JAVA_VERSION);
const PULL_TIMEOUT_MS = 600_000;

export interface JdkContainerRun {
  workdir: string;
  volumes: string[];
  command: string[];
  timeoutMs: number;
}

let imageReady: Promise<void> | null = null;

export async function ensureJdkImage(): Promise<void> {
  imageReady ??= pullIfMissing(JDK_IMAGE, PULL_TIMEOUT_MS).catch((error: unknown) => {
    imageReady = null;
    throw error;
  });
  await imageReady;
}

export async function runInJdkContainer(run: JdkContainerRun): Promise<CommandResult> {
  await ensureJdkImage();
  return runOneShot({ image: JDK_IMAGE, user: hostUser(), ...run });
}
