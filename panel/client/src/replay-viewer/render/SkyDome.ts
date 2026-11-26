import { BackSide, GLSL3, Mesh, RawShaderMaterial, SphereGeometry, type Vector3 } from 'three';

const VERTEX = `
precision highp float;
uniform mat4 modelViewMatrix;
uniform mat4 projectionMatrix;
in vec3 position;
out float vHeight;
void main() {
  vHeight = normalize(position).y;
  gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
}
`;

const FRAGMENT = `
precision highp float;
uniform vec3 uTop;
uniform vec3 uHorizon;
in float vHeight;
out vec4 fragColor;
void main() {
  fragColor = vec4(mix(uHorizon, uTop, smoothstep(0.0, 0.5, vHeight)), 1.0);
}
`;

function rgb(color: number, target: number[]): void {
  target[0] = ((color >> 16) & 255) / 255;
  target[1] = ((color >> 8) & 255) / 255;
  target[2] = (color & 255) / 255;
}

export class SkyDome {
  readonly mesh: Mesh;
  private readonly geometry = new SphereGeometry(500, 24, 12);
  private readonly material: RawShaderMaterial;
  private readonly top = [0, 0, 0];
  private readonly horizon = [0, 0, 0];

  constructor() {
    this.material = new RawShaderMaterial({
      glslVersion: GLSL3,
      vertexShader: VERTEX,
      fragmentShader: FRAGMENT,
      uniforms: { uTop: { value: this.top }, uHorizon: { value: this.horizon } },
      side: BackSide,
      depthWrite: false,
      depthTest: false,
    });
    this.mesh = new Mesh(this.geometry, this.material);
    this.mesh.frustumCulled = false;
    this.mesh.renderOrder = -1;
  }

  update(camera: Vector3, top: number, horizon: number): void {
    this.mesh.position.copy(camera);
    rgb(top, this.top);
    rgb(horizon, this.horizon);
  }

  dispose(): void {
    this.geometry.dispose();
    this.material.dispose();
  }
}
