import { renameSync } from "node:fs";
import { removePath } from "../../lib/util";

export async function writeAtomically(target: string, produce: (staged: string) => void | Promise<void>, extension = ""): Promise<void> {
  const staged = `${target}.partial${extension}`;
  removePath(staged);
  try {
    await produce(staged);
    renameSync(staged, target);
  } finally {
    removePath(staged);
  }
}
