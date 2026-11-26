export const LIGHT_SCALE = 16;

export interface LayerMesh {
  readonly positions: Float32Array;
  readonly uvs: Float32Array;
  readonly colors: Uint8Array;
  readonly lights: Uint8Array;
  readonly indices: Uint32Array;
  readonly quadCount: number;
}

export interface QuadVertex {
  x: number;
  y: number;
  z: number;
  u: number;
  v: number;
  r: number;
  g: number;
  b: number;
  sky: number;
  block: number;
  ao: number;
}

function grow<T extends Float32Array | Uint8Array>(array: T, needed: number, create: (length: number) => T): T {
  if (needed <= array.length) return array;
  const next = create(Math.max(needed, array.length * 2));
  next.set(array);
  return next;
}

export class MeshBuilder {
  private positions = new Float32Array(4096 * 3);
  private uvs = new Float32Array(4096 * 2);
  private colors = new Uint8Array(4096 * 4);
  private lights = new Uint8Array(4096 * 4);
  private vertices = 0;

  get quadCount(): number {
    return this.vertices / 4;
  }

  reset(): void {
    this.vertices = 0;
  }

  addQuad(corners: readonly QuadVertex[], animationSlot: number): void {
    const base = this.vertices;
    const needed = base + 4;
    this.positions = grow(this.positions, needed * 3, (length) => new Float32Array(length));
    this.uvs = grow(this.uvs, needed * 2, (length) => new Float32Array(length));
    this.colors = grow(this.colors, needed * 4, (length) => new Uint8Array(length));
    this.lights = grow(this.lights, needed * 4, (length) => new Uint8Array(length));
    const ao0 = corners[0]?.ao ?? 1;
    const ao1 = corners[1]?.ao ?? 1;
    const ao2 = corners[2]?.ao ?? 1;
    const ao3 = corners[3]?.ao ?? 1;
    const rotate = ao0 + ao2 < ao1 + ao3;
    for (let index = 0; index < 4; index++) {
      const vertex = corners[rotate ? (index + 1) % 4 : index];
      if (vertex === undefined) continue;
      const at = base + index;
      this.positions[at * 3] = vertex.x;
      this.positions[at * 3 + 1] = vertex.y;
      this.positions[at * 3 + 2] = vertex.z;
      this.uvs[at * 2] = vertex.u;
      this.uvs[at * 2 + 1] = vertex.v;
      this.colors[at * 4] = Math.round(Math.min(1, vertex.r * vertex.ao) * 255);
      this.colors[at * 4 + 1] = Math.round(Math.min(1, vertex.g * vertex.ao) * 255);
      this.colors[at * 4 + 2] = Math.round(Math.min(1, vertex.b * vertex.ao) * 255);
      this.colors[at * 4 + 3] = 255;
      this.lights[at * 4] = vertex.sky;
      this.lights[at * 4 + 1] = vertex.block;
      this.lights[at * 4 + 2] = animationSlot & 0xff;
      this.lights[at * 4 + 3] = animationSlot >> 8;
    }
    this.vertices = needed;
  }

  build(): LayerMesh {
    const quads = this.vertices / 4;
    const indices = new Uint32Array(quads * 6);
    for (let quad = 0; quad < quads; quad++) {
      const vertex = quad * 4;
      indices.set([vertex, vertex + 1, vertex + 2, vertex, vertex + 2, vertex + 3], quad * 6);
    }
    return {
      positions: this.positions.slice(0, this.vertices * 3),
      uvs: this.uvs.slice(0, this.vertices * 2),
      colors: this.colors.slice(0, this.vertices * 4),
      lights: this.lights.slice(0, this.vertices * 4),
      indices,
      quadCount: quads,
    };
  }
}
