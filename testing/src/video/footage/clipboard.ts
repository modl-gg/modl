import { spawnSync } from "node:child_process";
import { cancellableDelay } from "../../lib/cancellation";
import { runProcess } from "../../lib/process";

const COPY_TIMEOUT_MS = 3000;
const COPY_POLL_MS = 100;

async function readClipboard(): Promise<string> {
  return (await runProcess("pbpaste", [])).stdout;
}

function writeClipboard(text: string): void {
  const result = spawnSync("pbcopy", [], { input: text });
  if (result.status !== 0) throw new Error("pbcopy failed; the clipboard may still hold the copied link");
}

export async function copiedText(copy: () => Promise<void>): Promise<string> {
  const saved = await readClipboard();
  writeClipboard("");
  try {
    await copy();
    const deadline = Date.now() + COPY_TIMEOUT_MS;
    for (;;) {
      const copied = await readClipboard();
      if (copied.length > 0) return copied;
      if (Date.now() > deadline) throw new Error("nothing was copied to the clipboard");
      await cancellableDelay(COPY_POLL_MS);
    }
  } finally {
    writeClipboard(saved);
  }
}
