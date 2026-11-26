import { BufferGeometry, Mesh, type Object3D } from 'three';

export function disposeGeometries(root: Object3D): void {
  root.traverse((object) => {
    if (object instanceof Mesh && object.geometry instanceof BufferGeometry) object.geometry.dispose();
  });
}
