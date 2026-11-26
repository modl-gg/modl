import {
  type IUniform,
  DataTexture,
  FloatType,
  GLSL3,
  NearestFilter,
  NearestMipmapLinearFilter,
  NoColorSpace,
  RGBAFormat,
  RawShaderMaterial,
  FrontSide,
  UnsignedByteType,
} from 'three';
import { frameAt, type AtlasSprite } from './world/Atlas.ts';
import { LIGHTMAP_GLSL } from './Lighting.ts';

export const MAX_MIP_LEVEL = 4;

const VERTEX = `
precision highp float;
precision highp int;
uniform mat4 modelMatrix;
uniform mat4 modelViewMatrix;
uniform mat4 projectionMatrix;
uniform vec3 cameraPosition;
uniform sampler2D uAnimations;
uniform float uFogNear;
uniform float uDepthFog;
uniform float uFogFar;
in vec3 position;
in vec2 uv;
in vec4 color;
in vec4 light;
out vec2 vUv;
out vec2 vNextUv;
out float vBlend;
out vec4 vColor;
out vec3 vLight;
out float vFog;
${LIGHTMAP_GLSL}
void main() {
  vec4 viewPosition = modelViewMatrix * vec4(position, 1.0);
  gl_Position = projectionMatrix * viewPosition;
  int slot = int(light.z + light.w * 256.0 + 0.5);
  vec4 offsets = slot > 0 ? texelFetch(uAnimations, ivec2(slot, 0), 0) : vec4(0.0);
  vec4 extra = slot > 0 ? texelFetch(uAnimations, ivec2(slot, 1), 0) : vec4(0.0);
  vUv = uv + offsets.xy;
  vNextUv = uv + offsets.zw;
  vBlend = extra.x;
  vColor = color;
  vLight = lightmap(light.x / 16.0, light.y / 16.0);
  vec3 worldPosition = (modelMatrix * vec4(position, 1.0)).xyz - cameraPosition;
  float distance = uDepthFog > 0.5 ? -viewPosition.z : max(length(worldPosition.xz), abs(worldPosition.y));
  vFog = clamp((distance - uFogNear) / max(1.0, uFogFar - uFogNear), 0.0, 1.0);
}
`;

const FRAGMENT = `
precision highp float;
uniform sampler2D uAtlas;
uniform float uAtlasSize;
uniform float uCutoff;
uniform float uOpacity;
uniform vec3 uFogColor;
in vec2 vUv;
in vec2 vNextUv;
in float vBlend;
in vec4 vColor;
in vec3 vLight;
in float vFog;
out vec4 fragColor;
float mipLevel(vec2 uv) {
  vec2 texel = uv * uAtlasSize;
  vec2 dx = dFdx(texel);
  vec2 dy = dFdy(texel);
  float rho = max(dot(dx, dx), dot(dy, dy));
  return clamp(0.5 * log2(max(rho, 1e-8)), 0.0, ${String(MAX_MIP_LEVEL)}.0);
}
void main() {
  float lod = mipLevel(vUv);
  vec4 texel = textureLod(uAtlas, vUv, lod);
  if (vBlend > 0.0) texel = mix(texel, textureLod(uAtlas, vNextUv, lod), vBlend);
  if (texel.a < uCutoff) discard;
  vec4 colored = texel * vColor;
  colored.rgb *= vLight;
  colored.rgb = mix(colored.rgb, uFogColor, vFog);
  fragColor = vec4(colored.rgb, uOpacity < 0.0 ? colored.a : 1.0);
}
`;

export interface SharedUniforms {
  readonly uAtlas: IUniform<DataTexture | null>;
  readonly uAtlasSize: IUniform<number>;
  readonly uAnimations: IUniform<DataTexture>;
  readonly uSkyFactor: IUniform<number>;
  readonly uAmbient: IUniform<number>;
  readonly uFullBright: IUniform<number>;
  readonly uGamma: IUniform<number>;
  readonly uFogNear: IUniform<number>;
  readonly uDepthFog: IUniform<number>;
  readonly uFogFar: IUniform<number>;
  readonly uFogColor: IUniform<[number, number, number]>;
}

export type WorldLayerName = 'solid' | 'cutout' | 'translucent';

export class WorldMaterials {
  readonly uniforms: SharedUniforms;
  readonly solid: RawShaderMaterial;
  readonly cutout: RawShaderMaterial;
  readonly translucent: RawShaderMaterial;
  private atlas: DataTexture | null = null;
  private animated: readonly AtlasSprite[] = [];
  private readonly animationData: Float32Array;
  private readonly animationTexture: DataTexture;
  private lastTick = -1;

  constructor() {
    this.animationData = new Float32Array(1024 * 2 * 4);
    this.animationTexture = new DataTexture(this.animationData, 1024, 2, RGBAFormat, FloatType);
    this.animationTexture.minFilter = NearestFilter;
    this.animationTexture.magFilter = NearestFilter;
    this.animationTexture.needsUpdate = true;
    this.uniforms = {
      uAtlas: { value: null },
      uAtlasSize: { value: 1 },
      uAnimations: { value: this.animationTexture },
      uSkyFactor: { value: 1 },
      uAmbient: { value: 0 },
      uFullBright: { value: 0 },
      uGamma: { value: 0.5 },
      uFogNear: { value: 64 },
      uDepthFog: { value: 0 },
      uFogFar: { value: 96 },
      uFogColor: { value: [0.75, 0.85, 1] },
    };
    this.solid = this.material(-1, false, 1);
    this.cutout = this.material(0.5, false, 1);
    this.translucent = this.material(0.01, true, -1);
  }

  private material(cutoff: number, transparent: boolean, opacity: number): RawShaderMaterial {
    return new RawShaderMaterial({
      glslVersion: GLSL3,
      vertexShader: VERTEX,
      fragmentShader: FRAGMENT,
      uniforms: { ...this.uniforms, uCutoff: { value: cutoff }, uOpacity: { value: opacity } },
      transparent,
      depthWrite: true,
      side: FrontSide,
    });
  }

  setAtlas(size: number, levels: readonly Uint8Array<ArrayBuffer>[], sprites: readonly AtlasSprite[]): void {
    this.atlas?.dispose();
    const base = levels[0] ?? new Uint8Array(size * size * 4);
    const texture = new DataTexture(base, size, size, RGBAFormat, UnsignedByteType);
    texture.mipmaps = levels.map((data, level) => ({ data, width: Math.max(1, size >> level), height: Math.max(1, size >> level) }));
    texture.generateMipmaps = false;
    texture.minFilter = NearestMipmapLinearFilter;
    texture.magFilter = NearestFilter;
    texture.colorSpace = NoColorSpace;
    texture.flipY = false;
    texture.needsUpdate = true;
    this.atlas = texture;
    this.uniforms.uAtlas.value = texture;
    this.uniforms.uAtlasSize.value = size;
    this.animated = sprites.filter((sprite) => sprite.animation !== null);
    this.lastTick = -1;
  }

  tick(gameTick: number): void {
    const tick = Math.floor(gameTick);
    if (tick === this.lastTick || this.animated.length === 0) return;
    this.lastTick = tick;
    const size = this.uniforms.uAtlasSize.value;
    for (const sprite of this.animated) {
      const animation = sprite.animation;
      const slot = sprite.animationSlot;
      if (animation === null || slot <= 0 || slot >= 1024) continue;
      const current = frameAt(animation, tick);
      const origin = sprite.frameOrigins[current.frame] ?? [sprite.x, sprite.y];
      const next = sprite.frameOrigins[current.next] ?? origin;
      const offset = slot * 4;
      this.animationData[offset] = (origin[0] - sprite.x) / size;
      this.animationData[offset + 1] = (origin[1] - sprite.y) / size;
      this.animationData[offset + 2] = (next[0] - sprite.x) / size;
      this.animationData[offset + 3] = (next[1] - sprite.y) / size;
      this.animationData[1024 * 4 + offset] = current.blend;
    }
    this.animationTexture.needsUpdate = true;
  }

  dispose(): void {
    this.atlas?.dispose();
    this.animationTexture.dispose();
    this.solid.dispose();
    this.cutout.dispose();
    this.translucent.dispose();
  }
}
