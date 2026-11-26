import { DoubleSide, FrontSide, GLSL3, RawShaderMaterial, type Texture } from 'three';
import { LIGHTMAP_GLSL } from '../Lighting.ts';
import type { SharedUniforms } from '../WorldMaterials.ts';

const VERTEX = `
precision highp float;
uniform mat4 modelMatrix;
uniform mat4 modelViewMatrix;
uniform mat4 projectionMatrix;
uniform vec3 cameraPosition;
uniform vec2 uLight;
uniform float uEmissive;
uniform float uFogNear;
uniform float uDepthFog;
uniform float uFogFar;
in vec3 position;
in vec3 normal;
in vec2 uv;
out vec2 vUv;
out vec3 vLight;
out float vShade;
out float vFog;
${LIGHTMAP_GLSL}
void main() {
  vec4 viewPosition = modelViewMatrix * vec4(position, 1.0);
  gl_Position = projectionMatrix * viewPosition;
  vUv = uv;
  vec3 worldNormal = normalize(mat3(modelMatrix) * normal);
  vec3 light0 = normalize(vec3(0.2, 1.0, -0.7));
  vec3 light1 = normalize(vec3(-0.2, 1.0, 0.7));
  float diffuse = max(0.0, dot(light0, worldNormal)) * 0.6 + max(0.0, dot(light1, worldNormal)) * 0.6 + 0.4;
  vShade = uEmissive > 0.5 ? 1.0 : min(1.0, diffuse);
  vLight = uEmissive > 0.5 ? vec3(1.0) : lightmap(uLight.x, uLight.y);
  vec3 worldPosition = (modelMatrix * vec4(position, 1.0)).xyz - cameraPosition;
  float distance = uDepthFog > 0.5 ? -viewPosition.z : max(length(worldPosition.xz), abs(worldPosition.y));
  vFog = clamp((distance - uFogNear) / max(1.0, uFogFar - uFogNear), 0.0, 1.0);
}
`;

const FRAGMENT = `
precision highp float;
uniform sampler2D uTexture;
uniform vec3 uTint;
uniform vec4 uOverlay;
uniform float uCutoff;
uniform float uAlpha;
uniform vec3 uFogColor;
in vec2 vUv;
in vec3 vLight;
in float vShade;
in float vFog;
out vec4 fragColor;
void main() {
  vec4 texel = texture(uTexture, vUv);
  if (texel.a < uCutoff) discard;
  vec3 color = texel.rgb * uTint;
  color = mix(color, uOverlay.rgb, uOverlay.a);
  color *= vLight * vShade;
  color = mix(color, uFogColor, vFog);
  fragColor = vec4(color, texel.a * uAlpha);
}
`;

export interface EntityMaterialOptions {
  readonly translucent?: boolean;
  readonly doubleSided?: boolean;
  readonly emissive?: boolean;
  readonly cutoff?: number;
}

export function createEntityMaterial(shared: SharedUniforms, texture: Texture, options: EntityMaterialOptions = {}): RawShaderMaterial {
  return new RawShaderMaterial({
    glslVersion: GLSL3,
    vertexShader: VERTEX,
    fragmentShader: FRAGMENT,
    uniforms: {
      uTexture: { value: texture },
      uTint: { value: [1, 1, 1] },
      uOverlay: { value: [0, 0, 0, 0] },
      uLight: { value: [15, 0] },
      uEmissive: { value: options.emissive === true ? 1 : 0 },
      uCutoff: { value: options.cutoff ?? 0.1 },
      uAlpha: { value: 1 },
      uSkyFactor: shared.uSkyFactor,
      uAmbient: shared.uAmbient,
      uFullBright: shared.uFullBright,
      uGamma: shared.uGamma,
      uFogNear: shared.uFogNear,
      uDepthFog: shared.uDepthFog,
      uFogFar: shared.uFogFar,
      uFogColor: shared.uFogColor,
    },
    transparent: options.translucent === true,
    depthWrite: options.translucent !== true,
    side: options.doubleSided === true ? DoubleSide : FrontSide,
  });
}

function uniformArray(material: RawShaderMaterial, name: string, length: number): number[] | null {
  const uniform = material.uniforms[name];
  if (uniform === undefined) return null;
  const current: unknown = uniform.value;
  if (Array.isArray(current) && current.length === length && current.every((value) => typeof value === 'number')) return current;
  const created = new Array<number>(length).fill(0);
  uniform.value = created;
  return created;
}

export function setEntityLight(material: RawShaderMaterial, sky: number, block: number): void {
  const values = uniformArray(material, 'uLight', 2);
  if (values === null) return;
  values[0] = sky;
  values[1] = block;
}

export function setEntityOverlay(material: RawShaderMaterial, red: number, green: number, blue: number, alpha: number): void {
  const values = uniformArray(material, 'uOverlay', 4);
  if (values === null) return;
  values[0] = red;
  values[1] = green;
  values[2] = blue;
  values[3] = alpha;
}

export function setEntityTint(material: RawShaderMaterial, color: number): void {
  const uniform = material.uniforms['uTint'];
  if (uniform !== undefined) uniform.value = rgb(color);
}

export function rgb(color: number): [number, number, number] {
  return [((color >> 16) & 255) / 255, ((color >> 8) & 255) / 255, (color & 255) / 255];
}
