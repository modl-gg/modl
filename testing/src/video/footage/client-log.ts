import { closeSync, openSync, readFileSync, readSync, statSync } from "node:fs";
import { sleep } from "../../lib/util";

const CHAT_LINE = /\[CHAT\] (.*)$/;
const SETTING_USER = /Setting user: (\S+)/g;
const POLL_MS = 150;

export interface ChatLine {
  readonly text: string;
  readonly match: RegExpExecArray;
}

export class ClientLog {
  private readonly path: string;

  constructor(path: string) {
    this.path = path;
  }

  username(): string {
    const users = [...readFileSync(this.path, "utf8").matchAll(SETTING_USER)];
    const user = users.at(-1)?.[1];
    if (user === undefined) throw new Error(`${this.path} names no player ("Setting user: ..."); restart the client`);
    return user;
  }

  mark(): number {
    return statSync(this.path).size;
  }

  async waitForChat(pattern: RegExp, since: number, timeoutMs: number): Promise<ChatLine> {
    const deadline = Date.now() + timeoutMs;
    for (;;) {
      const found = this.chatSince(since).flatMap((text) => {
        const match = pattern.exec(text);
        return match ? [{ text, match }] : [];
      })[0];
      if (found) return found;
      if (Date.now() > deadline) throw new Error(`the Minecraft client showed no chat line matching ${pattern.toString()} within ${String(timeoutMs / 1000)} s`);
      await sleep(POLL_MS);
    }
  }

  latestChat(since: number): string | null {
    return this.chatSince(since).at(-1) ?? null;
  }

  private chatSince(offset: number): string[] {
    const size = statSync(this.path).size;
    if (size <= offset) return [];
    const buffer = Buffer.alloc(size - offset);
    const descriptor = openSync(this.path, "r");
    try {
      readSync(descriptor, buffer, 0, buffer.length, offset);
    } finally {
      closeSync(descriptor);
    }
    const lines = buffer.toString("utf8").split("\n");
    lines.pop();
    return lines.flatMap((line) => {
      const text = CHAT_LINE.exec(line.trimEnd())?.[1];
      return text === undefined ? [] : [text];
    });
  }
}
