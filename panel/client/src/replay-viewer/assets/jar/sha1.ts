const BLOCK_BYTES = 64;
const LENGTH_BYTES = 8;
const ROUND_WORDS = 80;

function rotateLeft(value: number, bits: number): number {
  return (value << bits) | (value >>> (32 - bits));
}

function padded(bytes: Uint8Array): Uint8Array {
  const total = Math.ceil((bytes.length + 1 + LENGTH_BYTES) / BLOCK_BYTES) * BLOCK_BYTES;
  const message = new Uint8Array(total);
  message.set(bytes);
  message[bytes.length] = 0x80;
  const view = new DataView(message.buffer);
  const bitLength = bytes.length * 8;
  view.setUint32(total - 8, Math.floor(bitLength / 0x1_0000_0000));
  view.setUint32(total - 4, bitLength >>> 0);
  return message;
}

function roundValue(round: number, b: number, c: number, d: number): number {
  if (round < 20) return ((b & c) | (~b & d)) + 0x5a827999;
  if (round < 40) return (b ^ c ^ d) + 0x6ed9eba1;
  if (round < 60) return ((b & c) | (b & d) | (c & d)) + 0x8f1bbcdc;
  return (b ^ c ^ d) + 0xca62c1d6;
}

export function sha1(bytes: Uint8Array): Uint8Array {
  const message = padded(bytes);
  const view = new DataView(message.buffer);
  const words = new Uint32Array(ROUND_WORDS);
  const state = new Uint32Array([0x67452301, 0xefcdab89, 0x98badcfe, 0x10325476, 0xc3d2e1f0]);
  for (let offset = 0; offset < message.length; offset += BLOCK_BYTES) {
    for (let index = 0; index < 16; index++) words[index] = view.getUint32(offset + index * 4);
    for (let index = 16; index < ROUND_WORDS; index++) {
      words[index] = rotateLeft((words[index - 3] ?? 0) ^ (words[index - 8] ?? 0) ^ (words[index - 14] ?? 0) ^ (words[index - 16] ?? 0), 1);
    }
    let a = state[0] ?? 0;
    let b = state[1] ?? 0;
    let c = state[2] ?? 0;
    let d = state[3] ?? 0;
    let e = state[4] ?? 0;
    for (let round = 0; round < ROUND_WORDS; round++) {
      const next = (rotateLeft(a, 5) + roundValue(round, b, c, d) + e + (words[round] ?? 0)) >>> 0;
      e = d;
      d = c;
      c = rotateLeft(b, 30) >>> 0;
      b = a;
      a = next;
    }
    state[0] = (state[0] ?? 0) + a;
    state[1] = (state[1] ?? 0) + b;
    state[2] = (state[2] ?? 0) + c;
    state[3] = (state[3] ?? 0) + d;
    state[4] = (state[4] ?? 0) + e;
  }
  const digest = new Uint8Array(20);
  const out = new DataView(digest.buffer);
  state.forEach((word, index) => { out.setUint32(index * 4, word); });
  return digest;
}

export function sha1Hex(bytes: Uint8Array): string {
  return Array.from(sha1(bytes), (byte) => byte.toString(16).padStart(2, '0')).join('');
}
