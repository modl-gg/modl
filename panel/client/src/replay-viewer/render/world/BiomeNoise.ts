const MULTIPLIER = 0x5deece66dn;
const ADDEND = 0xbn;
const MASK = (1n << 48n) - 1n;
const F2 = 0.5 * (Math.sqrt(3) - 1);
const G2 = (3 - Math.sqrt(3)) / 6;
const GRADIENTS: readonly (readonly [number, number, number])[] = [
  [1, 1, 0], [-1, 1, 0], [1, -1, 0], [-1, -1, 0],
  [1, 0, 1], [-1, 0, 1], [1, 0, -1], [-1, 0, -1],
  [0, 1, 1], [0, -1, 1], [0, 1, -1], [0, -1, -1],
  [1, 1, 0], [0, -1, 1], [-1, 1, 0], [0, -1, -1],
];

class LegacyRandom {
  private seed: bigint;

  constructor(seed: bigint) {
    this.seed = (seed ^ MULTIPLIER) & MASK;
  }

  next(bits: number): number {
    this.seed = (this.seed * MULTIPLIER + ADDEND) & MASK;
    return Number(BigInt.asIntN(32, this.seed >> BigInt(48 - bits)));
  }

  nextInt(bound: number): number {
    if ((bound & -bound) === bound) return Number((BigInt(bound) * BigInt(this.next(31))) >> 31n);
    let bits: number;
    let value: number;
    do {
      bits = this.next(31);
      value = bits % bound;
    } while (bits - value + (bound - 1) < 0 || bits - value + (bound - 1) > 0x7fffffff);
    return value;
  }

  nextDouble(): number {
    return (this.next(26) * 2 ** 27 + this.next(27)) * 2 ** -53;
  }
}

function corner(index: number, x: number, y: number): number {
  let t = 0.5 - x * x - y * y;
  if (t < 0) return 0;
  t *= t;
  const gradient = GRADIENTS[index] ?? [0, 0, 0];
  return t * t * (gradient[0] * x + gradient[1] * y);
}

export class SimplexNoise {
  private readonly permutation = new Int32Array(512);

  constructor(seed: bigint) {
    const random = new LegacyRandom(seed);
    random.nextDouble();
    random.nextDouble();
    random.nextDouble();
    const table = new Int32Array(256);
    for (let index = 0; index < 256; index++) table[index] = index;
    for (let index = 0; index < 256; index++) {
      const swap = random.nextInt(256 - index);
      const value = table[index] ?? 0;
      table[index] = table[index + swap] ?? 0;
      table[index + swap] = value;
    }
    for (let index = 0; index < 512; index++) this.permutation[index] = table[index & 255] ?? 0;
  }

  private p(index: number): number {
    return (this.permutation[index & 255] ?? 0) & 255;
  }

  value(x: number, y: number): number {
    const skew = (x + y) * F2;
    const i = Math.floor(x + skew);
    const j = Math.floor(y + skew);
    const unskew = (i + j) * G2;
    const x0 = x - (i - unskew);
    const y0 = y - (j - unskew);
    const k = x0 > y0 ? 1 : 0;
    const l = x0 > y0 ? 0 : 1;
    const x1 = x0 - k + G2;
    const y1 = y0 - l + G2;
    const x2 = x0 - 1 + 2 * G2;
    const y2 = y0 - 1 + 2 * G2;
    const ii = i & 255;
    const jj = j & 255;
    const g0 = this.p(ii + this.p(jj)) % 12;
    const g1 = this.p(ii + k + this.p(jj + l)) % 12;
    const g2 = this.p(ii + 1 + this.p(jj + 1)) % 12;
    return 70 * (corner(g0, x0, y0) + corner(g1, x1, y1) + corner(g2, x2, y2));
  }
}

let biomeInfoNoise: SimplexNoise | null = null;

export function swampGrassColor(x: number, z: number): number {
  biomeInfoNoise ??= new SimplexNoise(2345n);
  return biomeInfoNoise.value(x * 0.0225, z * 0.0225) < -0.1 ? 0x4c763c : 0x6a7039;
}
