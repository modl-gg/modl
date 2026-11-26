import { BufferAttribute, BufferGeometry, Group, Mesh, type Material, type Vector3 } from 'three';
import type { LayerMesh } from './world/MeshBuilder.ts';
import type { SectionMesh } from './world/SectionMesher.ts';
import { type WorldLayerName, WorldMaterials } from './WorldMaterials.ts';

interface SectionEntry {
  readonly x: number;
  readonly y: number;
  readonly z: number;
  readonly meshes: Partial<Record<WorldLayerName, Mesh>>;
  readonly translucentCenters: Float32Array | null;
  readonly sortedFrom: Float64Array;
  sorted: boolean;
}

const LAYERS: readonly WorldLayerName[] = ['solid', 'cutout', 'translucent'];
const SORT_DISTANCE = 48;
const SORT_MOVE = 1;

function geometryOf(layer: LayerMesh): BufferGeometry {
  const geometry = new BufferGeometry();
  geometry.setAttribute('position', new BufferAttribute(layer.positions, 3));
  geometry.setAttribute('uv', new BufferAttribute(layer.uvs, 2));
  geometry.setAttribute('color', new BufferAttribute(layer.colors, 4, true));
  geometry.setAttribute('light', new BufferAttribute(layer.lights, 4, false));
  geometry.setIndex(new BufferAttribute(layer.indices, 1));
  geometry.computeBoundingSphere();
  return geometry;
}

function quadCenters(positions: Float32Array, quads: number): Float32Array {
  const centers = new Float32Array(quads * 3);
  for (let quad = 0; quad < quads; quad++) {
    for (let axis = 0; axis < 3; axis++) {
      let sum = 0;
      for (let vertex = 0; vertex < 4; vertex++) sum += positions[(quad * 4 + vertex) * 3 + axis] ?? 0;
      centers[quad * 3 + axis] = sum / 4;
    }
  }
  return centers;
}

export class WorldRenderer {
  readonly group = new Group();
  readonly materials = new WorldMaterials();
  private readonly sections = new Map<number, SectionEntry>();
  private order = new Uint32Array(0);
  private distances = new Float32Array(0);

  setSection(key: number, x: number, y: number, z: number, mesh: SectionMesh): void {
    this.removeSection(key);
    const meshes: Partial<Record<WorldLayerName, Mesh>> = {};
    let translucentCenters: Float32Array | null = null;
    for (const layer of LAYERS) {
      const data = mesh[layer];
      if (data.quadCount === 0) continue;
      const material: Material = this.materials[layer];
      const object = new Mesh(geometryOf(data), material);
      object.position.set(x * 16, y * 16, z * 16);
      object.matrixAutoUpdate = false;
      object.updateMatrix();
      object.renderOrder = layer === 'translucent' ? 2 : layer === 'cutout' ? 1 : 0;
      this.group.add(object);
      meshes[layer] = object;
      if (layer === 'translucent') translucentCenters = quadCenters(data.positions, data.quadCount);
    }
    this.sections.set(key, { x, y, z, meshes, translucentCenters, sortedFrom: new Float64Array(3), sorted: false });
  }

  removeSection(key: number): void {
    const entry = this.sections.get(key);
    if (entry === undefined) return;
    for (const mesh of Object.values(entry.meshes)) {
      this.group.remove(mesh);
      mesh.geometry.dispose();
    }
    this.sections.delete(key);
  }

  sortTranslucent(camera: Vector3): void {
    for (const entry of this.sections.values()) {
      const mesh = entry.meshes.translucent;
      const centers = entry.translucentCenters;
      if (mesh === undefined || centers === null) continue;
      const localX = camera.x - entry.x * 16;
      const localY = camera.y - entry.y * 16;
      const localZ = camera.z - entry.z * 16;
      const dx = localX - 8;
      const dy = localY - 8;
      const dz = localZ - 8;
      if (dx * dx + dy * dy + dz * dz > SORT_DISTANCE * SORT_DISTANCE) continue;
      const last = entry.sortedFrom;
      if (entry.sorted && Math.abs((last[0] ?? 0) - localX) < SORT_MOVE && Math.abs((last[1] ?? 0) - localY) < SORT_MOVE && Math.abs((last[2] ?? 0) - localZ) < SORT_MOVE) continue;
      last[0] = localX;
      last[1] = localY;
      last[2] = localZ;
      entry.sorted = true;
      this.sortSection(mesh, centers, localX, localY, localZ);
    }
  }

  private reserve(quads: number): void {
    if (this.order.length >= quads) return;
    this.order = new Uint32Array(quads);
    this.distances = new Float32Array(quads);
  }

  private sortSection(mesh: Mesh, centers: Float32Array, x: number, y: number, z: number): void {
    const index = mesh.geometry.getIndex();
    if (index === null) return;
    const quads = centers.length / 3;
    this.reserve(quads);
    const distances = this.distances;
    const order = this.order.subarray(0, quads);
    for (let quad = 0; quad < quads; quad++) {
      const dx = (centers[quad * 3] ?? 0) - x;
      const dy = (centers[quad * 3 + 1] ?? 0) - y;
      const dz = (centers[quad * 3 + 2] ?? 0) - z;
      distances[quad] = dx * dx + dy * dy + dz * dz;
      order[quad] = quad;
    }
    order.sort((left, right) => (distances[right] ?? 0) - (distances[left] ?? 0));
    const array = index.array;
    for (let position = 0; position < quads; position++) {
      const vertex = (order[position] ?? 0) * 4;
      const offset = position * 6;
      array[offset] = vertex;
      array[offset + 1] = vertex + 1;
      array[offset + 2] = vertex + 2;
      array[offset + 3] = vertex;
      array[offset + 4] = vertex + 2;
      array[offset + 5] = vertex + 3;
    }
    index.needsUpdate = true;
  }

  limitToSquare(cameraX: number, cameraZ: number, chunks: number | null): void {
    const chunkX = Math.floor(cameraX / 16);
    const chunkZ = Math.floor(cameraZ / 16);
    for (const entry of this.sections.values()) {
      const visible = chunks === null || (Math.abs(entry.x - chunkX) <= chunks && Math.abs(entry.z - chunkZ) <= chunks);
      for (const mesh of Object.values(entry.meshes)) mesh.visible = visible;
    }
  }

  dispose(): void {
    for (const key of [...this.sections.keys()]) this.removeSection(key);
    this.materials.dispose();
  }
}
