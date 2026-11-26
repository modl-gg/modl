import test from 'node:test';
import assert from 'node:assert/strict';
import { BufferGeometry, Mesh, Vector3 } from 'three';
import { MeshBuilder } from './world/MeshBuilder.ts';
import type { SectionMesh } from './world/SectionMesher.ts';
import { WorldRenderer } from './WorldRenderer.ts';

function section(translucentQuads: number): SectionMesh {
  const solid = new MeshBuilder();
  const translucent = new MeshBuilder();
  const corner = (x: number, z: number): { x: number; y: number; z: number; u: number; v: number; r: number; g: number; b: number; sky: number; block: number; ao: number } => ({ x, y: 0, z, u: 0, v: 0, r: 1, g: 1, b: 1, sky: 240, block: 0, ao: 1 });
  solid.addQuad([corner(0, 0), corner(0, 1), corner(1, 1), corner(1, 0)], 0);
  for (let quad = 0; quad < translucentQuads; quad++) translucent.addQuad([corner(quad, 0), corner(quad, 1), corner(quad + 1, 1), corner(quad + 1, 0)], 0);
  return { solid: solid.build(), cutout: new MeshBuilder().build(), translucent: translucent.build() };
}

void test('replacing and disposing sections releases every geometry and material', () => {
  const renderer = new WorldRenderer();
  const disposed: string[] = [];
  renderer.setSection(1, 0, 0, 0, section(2));
  const first = renderer.group.children.flatMap((child) => (child instanceof Mesh && child.geometry instanceof BufferGeometry ? [child.geometry] : []));
  for (const geometry of first) geometry.addEventListener('dispose', () => { disposed.push('first'); });
  renderer.setSection(1, 0, 0, 0, section(0));
  assert.equal(disposed.length, first.length);
  assert.equal(renderer.group.children.length, 1);
  for (const material of [renderer.materials.solid, renderer.materials.cutout, renderer.materials.translucent]) {
    material.addEventListener('dispose', () => { disposed.push('material'); });
  }
  renderer.dispose();
  assert.equal(renderer.group.children.length, 0);
  assert.equal(disposed.filter((entry) => entry === 'material').length, 3);
});

void test('translucent quads are drawn back to front from the camera', () => {
  const renderer = new WorldRenderer();
  renderer.setSection(2, 0, 0, 0, section(3));
  renderer.sortTranslucent(new Vector3(0, 1, 0.5));
  const translucent = renderer.group.children.find((child) => child.renderOrder === 2);
  assert.ok(translucent instanceof Mesh);
  const index = translucent.geometry instanceof BufferGeometry ? translucent.geometry.getIndex() : null;
  assert.deepEqual(Array.from(index?.array ?? []).filter((_, position) => position % 6 === 0), [8, 4, 0]);
  renderer.dispose();
});
