import { createHmac } from "node:crypto";
import { sleep } from "./util";

const BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
const PERIOD_SECONDS = 30;
const DIGITS = 6;
const MODULUS = 10 ** DIGITS;
const BITS_PER_CHARACTER = 5;
const BITS_PER_BYTE = 8;

export function decodeBase32(text: string): Buffer {
  const bytes: number[] = [];
  let buffer = 0;
  let bits = 0;
  for (const character of text.replace(/=+$/, "").toUpperCase()) {
    const value = BASE32_ALPHABET.indexOf(character);
    if (value < 0) throw new Error(`not a base32 character: ${character}`);
    buffer = (buffer << BITS_PER_CHARACTER) | value;
    bits += BITS_PER_CHARACTER;
    if (bits >= BITS_PER_BYTE) {
      bits -= BITS_PER_BYTE;
      bytes.push((buffer >> bits) & 0xff);
    }
  }
  return Buffer.from(bytes);
}

export function stepAt(epochMillis: number): number {
  return Math.floor(epochMillis / 1000 / PERIOD_SECONDS);
}

export function totpCode(secret: string, step: number): string {
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(step));
  const hash = createHmac("sha1", decodeBase32(secret)).update(counter).digest();
  const offset = (hash.at(-1) ?? 0) & 0x0f;
  return String((hash.readUInt32BE(offset) & 0x7fffffff) % MODULUS).padStart(DIGITS, "0");
}

export class TotpDevice {
  readonly secret: string;
  private lastUsedStep = -1;

  constructor(secret: string) {
    this.secret = secret;
  }

  async nextCode(): Promise<string> {
    let step = stepAt(Date.now());
    while (step <= this.lastUsedStep) {
      await sleep((step + 1) * PERIOD_SECONDS * 1000 - Date.now() + 250);
      step = stepAt(Date.now());
    }
    this.lastUsedStep = step;
    return totpCode(this.secret, step);
  }
}
