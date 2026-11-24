import * as THREE from 'three';
import type { BlockChange } from '../engine/WorldState';
import type { BlockSnapshot } from '../types/ReplayFile';
import { ITEM_TEXTURE_FALLBACKS, type AtlasItem, type AtlasMapping, type AtlasUV, type BlockFaces } from '../types/AtlasMapping';
import { create2dContext, loadImage, loadImageFromBytes } from './images';

type Vec3 = readonly [number, number, number];
type QuadCorners = readonly [Vec3, Vec3, Vec3, Vec3];
type UvCorner = readonly [number, number];

interface QuadModel {
  readonly vertices: QuadCorners;
  readonly normal: Vec3;
}

interface CubeFace extends QuadModel {
  readonly texture: keyof Pick<BlockFaces, 'top' | 'side' | 'bottom'>;
}

interface MultifaceFace {
  readonly mask: number;
  readonly quads: readonly QuadModel[];
}

const CUBE_FACES: readonly CubeFace[] = [
  { vertices: [[0, 1, 0], [1, 1, 0], [1, 1, 1], [0, 1, 1]], normal: [0, 1, 0], texture: 'top' },
  { vertices: [[0, 0, 1], [1, 0, 1], [1, 0, 0], [0, 0, 0]], normal: [0, -1, 0], texture: 'bottom' },
  { vertices: [[1, 0, 1], [1, 1, 1], [1, 1, 0], [1, 0, 0]], normal: [1, 0, 0], texture: 'side' },
  { vertices: [[0, 0, 0], [0, 1, 0], [0, 1, 1], [0, 0, 1]], normal: [-1, 0, 0], texture: 'side' },
  { vertices: [[0, 0, 1], [0, 1, 1], [1, 1, 1], [1, 0, 1]], normal: [0, 0, 1], texture: 'side' },
  { vertices: [[1, 0, 0], [1, 1, 0], [0, 1, 0], [0, 0, 0]], normal: [0, 0, -1], texture: 'side' },
];

const FACE_UVS: readonly [UvCorner, UvCorner, UvCorner, UvCorner] = [
  [0, 1], [0, 0], [1, 0], [1, 1],
];

const QUAD_INDICES = [0, 2, 1, 0, 3, 2] as const;

const CROSS_QUADS: readonly QuadModel[] = [
  { vertices: [[0.1464, 0, 0.8536], [0.1464, 1, 0.8536], [0.8536, 1, 0.1464], [0.8536, 0, 0.1464]], normal: [-0.7071, 0, 0.7071] },
  { vertices: [[0.8536, 0, 0.1464], [0.8536, 1, 0.1464], [0.1464, 1, 0.8536], [0.1464, 0, 0.8536]], normal: [0.7071, 0, -0.7071] },
  { vertices: [[0.1464, 0, 0.1464], [0.1464, 1, 0.1464], [0.8536, 1, 0.8536], [0.8536, 0, 0.8536]], normal: [0.7071, 0, 0.7071] },
  { vertices: [[0.8536, 0, 0.8536], [0.8536, 1, 0.8536], [0.1464, 1, 0.1464], [0.1464, 0, 0.1464]], normal: [-0.7071, 0, -0.7071] },
];

const FLOOR_QUADS: readonly QuadModel[] = [
  { vertices: [[0, 0.01, 0], [0, 0.01, 1], [1, 0.01, 1], [1, 0.01, 0]], normal: [0, 1, 0] },
  { vertices: [[1, 0.01, 0], [1, 0.01, 1], [0, 0.01, 1], [0, 0.01, 0]], normal: [0, -1, 0] },
];

const MULTIFACE_FACES: readonly MultifaceFace[] = [
  { mask: 1, quads: [
    { vertices: [[0, 0.99, 0], [1, 0.99, 0], [1, 0.99, 1], [0, 0.99, 1]], normal: [0, 1, 0] },
    { vertices: [[0, 0.99, 1], [1, 0.99, 1], [1, 0.99, 0], [0, 0.99, 0]], normal: [0, -1, 0] },
  ]},
  { mask: 2, quads: [
    { vertices: [[0, 0.01, 1], [1, 0.01, 1], [1, 0.01, 0], [0, 0.01, 0]], normal: [0, -1, 0] },
    { vertices: [[0, 0.01, 0], [1, 0.01, 0], [1, 0.01, 1], [0, 0.01, 1]], normal: [0, 1, 0] },
  ]},
  { mask: 4, quads: [
    { vertices: [[1, 0, 0.01], [1, 1, 0.01], [0, 1, 0.01], [0, 0, 0.01]], normal: [0, 0, -1] },
    { vertices: [[0, 0, 0.01], [0, 1, 0.01], [1, 1, 0.01], [1, 0, 0.01]], normal: [0, 0, 1] },
  ]},
  { mask: 8, quads: [
    { vertices: [[0, 0, 0.99], [0, 1, 0.99], [1, 1, 0.99], [1, 0, 0.99]], normal: [0, 0, 1] },
    { vertices: [[1, 0, 0.99], [1, 1, 0.99], [0, 1, 0.99], [0, 0, 0.99]], normal: [0, 0, -1] },
  ]},
  { mask: 16, quads: [
    { vertices: [[0.99, 0, 1], [0.99, 1, 1], [0.99, 1, 0], [0.99, 0, 0]], normal: [1, 0, 0] },
    { vertices: [[0.99, 0, 0], [0.99, 1, 0], [0.99, 1, 1], [0.99, 0, 1]], normal: [-1, 0, 0] },
  ]},
  { mask: 32, quads: [
    { vertices: [[0.01, 0, 0], [0.01, 1, 0], [0.01, 1, 1], [0.01, 0, 1]], normal: [-1, 0, 0] },
    { vertices: [[0.01, 0, 1], [0.01, 1, 1], [0.01, 1, 0], [0.01, 0, 0]], normal: [1, 0, 0] },
  ]},
];

type BoxFaceRegion = readonly [number, number, number, number];

interface BoxFaceRegions {
  readonly right: BoxFaceRegion;
  readonly left: BoxFaceRegion;
  readonly top: BoxFaceRegion;
  readonly bottom: BoxFaceRegion;
  readonly front: BoxFaceRegion;
  readonly back: BoxFaceRegion;
}

type BoxFaceName = keyof BoxFaceRegions;

const BOX_FACE_ORDER: readonly BoxFaceName[] = ['right', 'left', 'top', 'bottom', 'back', 'front'];

type SkinPart = 'head' | 'torso' | 'rightArm' | 'leftArm' | 'rightLeg' | 'leftLeg';

const SKIN_PARTS: Readonly<Record<SkinPart, BoxFaceRegions>> = {
  head: {
    front: [8, 8, 8, 8], right: [0, 8, 8, 8], back: [24, 8, 8, 8], left: [16, 8, 8, 8],
    top: [8, 0, 8, 8], bottom: [16, 0, 8, 8],
  },
  torso: {
    front: [20, 20, 8, 12], right: [16, 20, 4, 12], back: [32, 20, 8, 12], left: [28, 20, 4, 12],
    top: [20, 16, 8, 4], bottom: [28, 16, 8, 4],
  },
  rightArm: {
    front: [44, 20, 4, 12], right: [40, 20, 4, 12], back: [52, 20, 4, 12], left: [48, 20, 4, 12],
    top: [44, 16, 4, 4], bottom: [48, 16, 4, 4],
  },
  leftArm: {
    front: [36, 52, 4, 12], right: [32, 52, 4, 12], back: [44, 52, 4, 12], left: [40, 52, 4, 12],
    top: [36, 48, 4, 4], bottom: [40, 48, 4, 4],
  },
  rightLeg: {
    front: [4, 20, 4, 12], right: [0, 20, 4, 12], back: [12, 20, 4, 12], left: [8, 20, 4, 12],
    top: [4, 16, 4, 4], bottom: [8, 16, 4, 4],
  },
  leftLeg: {
    front: [20, 52, 4, 12], right: [16, 52, 4, 12], back: [28, 52, 4, 12], left: [24, 52, 4, 12],
    top: [20, 48, 4, 4], bottom: [24, 48, 4, 4],
  },
};

const SKIN_PART_BY_PIVOT: Readonly<Record<string, SkinPart>> = {
  head: 'head',
  rightArm: 'rightArm',
  leftArm: 'leftArm',
  rightLeg: 'rightLeg',
  leftLeg: 'leftLeg',
  bodyPivot: 'torso',
};

interface SectionCoords {
  readonly x: number;
  readonly y: number;
  readonly z: number;
}

interface BlockAtlas {
  readonly mapping: AtlasMapping;
  readonly texture: THREE.Texture;
  readonly opaqueMaterial: THREE.MeshLambertMaterial;
  readonly transparentMaterial: THREE.MeshLambertMaterial;
}

type EffectMesh = THREE.Mesh<THREE.BufferGeometry, THREE.MeshBasicMaterial>;

interface ActiveExplosion {
  readonly particles: THREE.Points<THREE.BufferGeometry, THREE.PointsMaterial>;
  readonly positions: Float32Array;
  readonly positionAttribute: THREE.BufferAttribute;
  readonly velocities: Float32Array;
  readonly startTime: number;
  readonly duration: number;
  readonly gravity: boolean;
  readonly effectMeshes: readonly EffectMesh[];
}

class SectionMeshBuilder {
  private readonly positions: number[] = [];
  private readonly normals: number[] = [];
  private readonly uvs: number[] = [];
  private readonly indices: number[] = [];
  private vertexCount = 0;

  addQuad(quad: QuadModel, x: number, y: number, z: number, texture: AtlasUV, topHeight?: number): void {
    const { vertices, normal } = quad;
    this.addVertex(vertices[0], FACE_UVS[0], normal, x, y, z, texture, topHeight);
    this.addVertex(vertices[1], FACE_UVS[1], normal, x, y, z, texture, topHeight);
    this.addVertex(vertices[2], FACE_UVS[2], normal, x, y, z, texture, topHeight);
    this.addVertex(vertices[3], FACE_UVS[3], normal, x, y, z, texture, topHeight);
    for (const offset of QUAD_INDICES) {
      this.indices.push(this.vertexCount + offset);
    }
    this.vertexCount += 4;
  }

  build(material: THREE.Material): THREE.Mesh | null {
    if (this.vertexCount === 0) return null;
    const geometry = new THREE.BufferGeometry();
    geometry.setAttribute('position', new THREE.Float32BufferAttribute(this.positions, 3));
    geometry.setAttribute('normal', new THREE.Float32BufferAttribute(this.normals, 3));
    geometry.setAttribute('uv', new THREE.Float32BufferAttribute(this.uvs, 2));
    geometry.setIndex(this.indices);
    return new THREE.Mesh(geometry, material);
  }

  private addVertex(
    vertex: Vec3, corner: UvCorner, normal: Vec3,
    x: number, y: number, z: number,
    texture: AtlasUV, topHeight: number | undefined,
  ): void {
    const vertexY = topHeight !== undefined && vertex[1] === 1 ? topHeight : vertex[1];
    this.positions.push(x + vertex[0], y + vertexY, z + vertex[2]);
    this.normals.push(normal[0], normal[1], normal[2]);
    this.uvs.push(texture.u + corner[0] * texture.su, texture.v + corner[1] * texture.sv);
  }
}

function setPlaneUVs(geometry: THREE.PlaneGeometry, texture: AtlasUV): void {
  const uvAttr = geometry.getAttribute('uv');
  uvAttr.setXY(0, texture.u, texture.v);
  uvAttr.setXY(1, texture.u + texture.su, texture.v);
  uvAttr.setXY(2, texture.u, texture.v + texture.sv);
  uvAttr.setXY(3, texture.u + texture.su, texture.v + texture.sv);
  uvAttr.needsUpdate = true;
}

function disposeEffectMesh(scene: THREE.Scene, mesh: EffectMesh): void {
  scene.remove(mesh);
  mesh.geometry.dispose();
  mesh.material.dispose();
}

type SectionKey = string; // "sx,sy,sz"

/**
 * Renderer interface abstracting over prismarine-viewer.
 * Supports atlas-based textured rendering with section-based mesh generation.
 */
export class PrismarineRenderer {
  private container: HTMLElement | null = null;
  private readonly scene: THREE.Scene;
  private readonly camera: THREE.PerspectiveCamera;
  private renderer: THREE.WebGLRenderer | null = null;
  private animationFrameId: number | null = null;

  private initialized = false;

  private atlas: BlockAtlas | null = null;
  private transparentStates = new Set<number>();
  private stateToBlockId = new Map<number, number>();
  private waterLevelMap = new Map<number, number>();
  private blockNameToState = new Map<string, number>();
  private itemMapping = new Map<string, { texture: string | null; isBlock: boolean }>();
  // States that should not render at all (barrier, structure_void, etc.)
  private readonly hiddenStates = new Set<number>();

  // Section-based mesh management
  private readonly sections = new Map<SectionKey, { opaque: THREE.Mesh | null; transparent: THREE.Mesh | null }>();
  private readonly blockMap = new Map<string, number>(); // "x,y,z" → stateId
  private readonly dirtySections = new Map<SectionKey, SectionCoords>();

  // Skin textures cache
  private readonly skinTextures = new Map<string, THREE.Texture | null>();
  // Embedded skin PNG data from replay file
  private readonly embeddedSkins = new Map<string, Uint8Array<ArrayBuffer>>();
  private readonly playerSkinIds = new Map<string, string>();
  private readonly skinnedGroups = new WeakSet<THREE.Group>();

  // Armor texture cache: "material_layer1" or "material_layer2" → Texture
  private readonly armorTextureCache = new Map<string, THREE.Texture>();

  // Entity texture cache: entityType → Texture
  private readonly entityTextureCache = new Map<string, THREE.Texture>();

  // Walk cycle tracking
  private readonly playerPrevPositions = new Map<string, { x: number; z: number; time: number; moveDir: number }>();

  // Body yaw tracking for independent head rotation
  private readonly playerBodyYaw = new Map<string, number>();

  // Held item tracking per player
  private readonly playerHeldItems = new Map<string, string>();

  constructor(_mcVersion: string) {
    this.scene = new THREE.Scene();
    this.camera = new THREE.PerspectiveCamera(75, 1, 0.1, 10000);
    this.camera.position.set(0, 80, 0);
  }

  /**
   * Set the texture atlas for block rendering.
   * Must be called before loadSnapshot() for textured rendering.
   */
  async setTextureAtlas(atlasUrl: string, mapping: AtlasMapping): Promise<void> {
    if (mapping.transparentStates) {
      this.transparentStates = new Set(mapping.transparentStates);
    }
    if (mapping.stateToBlock) {
      this.stateToBlockId = new Map(
        Object.entries(mapping.stateToBlock).map(([k, v]) => [Number(k), v])
      );
    }

    const loader = new THREE.TextureLoader();
    const atlasTexture = await loader.loadAsync(atlasUrl);

    atlasTexture.magFilter = THREE.NearestFilter;
    atlasTexture.minFilter = THREE.NearestFilter;
    atlasTexture.colorSpace = THREE.SRGBColorSpace;
    atlasTexture.flipY = false;

    this.atlas = {
      mapping,
      texture: atlasTexture,
      opaqueMaterial: new THREE.MeshLambertMaterial({
        map: atlasTexture,
        transparent: false,
        alphaTest: 0.1,
      }),
      transparentMaterial: new THREE.MeshLambertMaterial({
        map: atlasTexture,
        transparent: true,
        depthWrite: false,
        alphaTest: 0.1,
      }),
    };

    // Block name to state map (for item rendering)
    if (mapping.blockNameToState) {
      this.blockNameToState = new Map(
        Object.entries(mapping.blockNameToState).map(([k, v]) => [k, v])
      );
    }

    // Hidden blocks (should not render)
    const HIDDEN_BLOCKS = ['barrier', 'structure_void', 'light'];
    for (const blockName of HIDDEN_BLOCKS) {
      const stateId = this.blockNameToState.get(blockName);
      if (stateId !== undefined) {
        this.hiddenStates.add(stateId);
        // Also hide all other states that share the same blockId
        const blockId = this.stateToBlockId.get(stateId);
        if (blockId !== undefined) {
          for (const [sid, bid] of this.stateToBlockId) {
            if (bid === blockId) this.hiddenStates.add(sid);
          }
        }
      }
    }

    // Item mapping
    if (mapping.items) {
      this.itemMapping = new Map(
        Object.entries(mapping.items).map(([k, v]) => [k, v])
      );
    }

    // Water level map
    if (mapping.waterLevels) {
      this.waterLevelMap = new Map(
        Object.entries(mapping.waterLevels).map(([k, v]) => [Number(k), v])
      );
    }

    // Load armor + entity textures in parallel (relative to atlas URL)
    const baseUrl = atlasUrl.substring(0, atlasUrl.lastIndexOf('/'));
    const texLoadPromises: Promise<void>[] = [];

    const loadTex = async (url: string): Promise<THREE.Texture> =>
      loader.loadAsync(url).then(tex => {
        tex.magFilter = THREE.NearestFilter;
        tex.minFilter = THREE.NearestFilter;
        tex.colorSpace = THREE.SRGBColorSpace;
        tex.flipY = false;
        return tex;
      });

    if (mapping.armorTextures) {
      for (const [material, paths] of Object.entries(mapping.armorTextures)) {
        for (const [layerKey, relPath] of Object.entries(paths)) {
          texLoadPromises.push(
            loadTex(`${baseUrl}/${relPath}`)
              .then(tex => { this.armorTextureCache.set(`${material}_${layerKey}`, tex); })
              .catch(() => { /* skip missing */ })
          );
        }
      }
    }

    if (mapping.entityTextures) {
      for (const [entityType, relPath] of Object.entries(mapping.entityTextures)) {
        texLoadPromises.push(
          loadTex(`${baseUrl}/${relPath}`)
            .then(tex => { this.entityTextureCache.set(entityType, tex); })
            .catch(() => { /* skip missing */ })
        );
      }
    }

    await Promise.all(texLoadPromises);
  }

  /**
   * Initialize the renderer and attach to a DOM element.
   */
  init(container: HTMLElement): void {
    this.container = container;

    this.renderer = new THREE.WebGLRenderer({ antialias: true });
    this.renderer.setPixelRatio(window.devicePixelRatio);
    this.renderer.setClearColor(0x87ceeb); // sky blue
    this.updateSize();
    container.appendChild(this.renderer.domElement);

    this.setupBasicScene();
    this.initialized = true;
    this.startRenderLoop();
  }

  /**
   * Load the initial block snapshot into the renderer.
   */
  async loadSnapshot(blocks: BlockSnapshot[]): Promise<void> {
    if (!this.initialized) return;

    if (this.atlas) {
      await this.loadBlocksTextured(blocks, this.atlas);
    } else {
      this.loadBlocksBasic(blocks);
    }
  }

  /**
   * Apply incremental block changes from the engine.
   */
  applyBlockChanges(changes: BlockChange[]): void {
    if (!this.initialized) return;

    const atlas = this.atlas;
    if (atlas) {
      for (const change of changes) {
        this.updateBlockTextured(change);
      }
      this.rebuildDirtySections(atlas);
    } else {
      for (const change of changes) {
        this.updateBlockBasic(change);
      }
    }
  }

  // Entity meshes: entityId → group
  private readonly entityMeshes = new Map<number, THREE.Group>();
  // Entity type names for explosion check
  private readonly entityTypeNames = new Map<number, string>();

  // Explosion particles
  private static readonly EXPLOSIVE_TYPES = new Set([
    'tnt', 'wind_charge', 'ender_crystal', 'end_crystal',
    'fireball', 'small_fireball', 'wither_skull', 'dragon_fireball',
  ]);
  private readonly activeExplosions: ActiveExplosion[] = [];

  // Entity type → atlas item name mapping for sprite rendering
  private static readonly ENTITY_ITEM_TEXTURES: Record<string, string> = {
    arrow: 'arrow',
    spectral_arrow: 'spectral_arrow',
    snowball: 'snowball',
    ender_pearl: 'ender_pearl',
    wind_charge: 'wind_charge',
    fire_charge: 'fire_charge',
    experience_bottle: 'experience_bottle',
    egg: 'egg',
    end_crystal: 'end_crystal',
    ender_crystal: 'end_crystal',
    fireball: 'fire_charge',
    small_fireball: 'fire_charge',
    wither_skull: 'wither_skeleton_skull',
    dragon_fireball: 'fire_charge',
    experience_orb: 'experience_bottle',
    trident: 'trident',
  };

  // Block entities that should render as textured cubes
  // Note: falling_block is handled separately with state ID metadata (falling_block:stateId)
  private static readonly ENTITY_BLOCK_NAMES: Record<string, string> = {
    tnt: 'tnt',
    primed_tnt: 'tnt',
  };

  // Humanoid mobs: render with head + body
  private static readonly HUMANOID_MOBS = new Set([
    'zombie', 'skeleton', 'creeper', 'enderman', 'blaze', 'witch',
    'villager', 'zombie_villager', 'pillager', 'vindicator', 'evoker',
    'drowned', 'husk', 'stray', 'wither_skeleton', 'piglin', 'piglin_brute',
    'zombified_piglin', 'armor_stand', 'wandering_trader', 'vex',
  ]);

  // Entity type → dimensions/color
  private static readonly ENTITY_STYLES: Record<string, { width: number; height: number; depth: number; color: number }> = {
    zombie:       { width: 0.6, height: 1.95, depth: 0.6, color: 0x00aa00 },
    skeleton:     { width: 0.6, height: 1.99, depth: 0.6, color: 0xc0c0c0 },
    creeper:      { width: 0.6, height: 1.7,  depth: 0.6, color: 0x00ff00 },
    spider:       { width: 1.4, height: 0.9,  depth: 1.4, color: 0x5c3a1e },
    enderman:     { width: 0.6, height: 2.9,  depth: 0.6, color: 0x161616 },
    blaze:        { width: 0.6, height: 1.8,  depth: 0.6, color: 0xff8800 },
    witch:        { width: 0.6, height: 1.95, depth: 0.6, color: 0x6a0dad },
    slime:        { width: 0.5, height: 0.5,  depth: 0.5, color: 0x88ff88 },
    phantom:      { width: 0.9, height: 0.5,  depth: 0.9, color: 0x4466aa },
    end_crystal:  { width: 2.0, height: 2.0,  depth: 2.0, color: 0xff00ff },
    ender_crystal: { width: 2.0, height: 2.0,  depth: 2.0, color: 0xff00ff },
    armor_stand:  { width: 0.5, height: 1.97, depth: 0.5, color: 0x8b6914 },
    item_frame:   { width: 0.5, height: 0.5,  depth: 0.1, color: 0xd2b48c },
    villager:     { width: 0.6, height: 1.95, depth: 0.6, color: 0xb37a4c },
    iron_golem:   { width: 1.4, height: 2.7,  depth: 1.4, color: 0xd4d4d4 },
    wolf:         { width: 0.6, height: 0.85, depth: 0.6, color: 0xd0d0d0 },
    cat:          { width: 0.6, height: 0.7,  depth: 0.6, color: 0xe8a020 },
    cow:          { width: 0.9, height: 1.4,  depth: 0.9, color: 0x443322 },
    pig:          { width: 0.9, height: 0.9,  depth: 0.9, color: 0xf0a0a0 },
    sheep:        { width: 0.9, height: 1.3,  depth: 0.9, color: 0xf0f0f0 },
    chicken:      { width: 0.4, height: 0.7,  depth: 0.4, color: 0xffffff },
    horse:        { width: 1.4, height: 1.6,  depth: 1.4, color: 0xc09060 },
    zombie_horse: { width: 1.4, height: 1.6,  depth: 1.4, color: 0x5a7a42 },
    skeleton_horse: { width: 1.4, height: 1.6, depth: 1.4, color: 0xc8c8a0 },
    donkey:       { width: 1.4, height: 1.5,  depth: 1.4, color: 0x5a4e3a },
    mule:         { width: 1.4, height: 1.6,  depth: 1.4, color: 0x5a3a20 },
    llama:        { width: 0.9, height: 1.87, depth: 0.9, color: 0xc0a888 },
    trader_llama: { width: 0.9, height: 1.87, depth: 0.9, color: 0xc0a888 },
    turtle:       { width: 1.2, height: 0.4,  depth: 1.2, color: 0x3a8a2e },
    rabbit:       { width: 0.4, height: 0.5,  depth: 0.4, color: 0xb5835a },
    fox:          { width: 0.6, height: 0.7,  depth: 0.6, color: 0xd57835 },
    bee:          { width: 0.7, height: 0.6,  depth: 0.7, color: 0xedc343 },
    goat:         { width: 0.9, height: 1.3,  depth: 0.9, color: 0xb5a58c },
    frog:         { width: 0.5, height: 0.55, depth: 0.5, color: 0x7ea553 },
    allay:        { width: 0.35, height: 0.6, depth: 0.35, color: 0x59c3e0 },
    bat:          { width: 0.5, height: 0.9,  depth: 0.5, color: 0x4e3d2e },
    squid:        { width: 0.8, height: 0.8,  depth: 0.8, color: 0x223366 },
    glow_squid:   { width: 0.8, height: 0.8,  depth: 0.8, color: 0x0a9994 },
    dolphin:      { width: 0.9, height: 0.6,  depth: 0.9, color: 0x8899aa },
    guardian:     { width: 0.85, height: 0.85, depth: 0.85, color: 0x5f8a72 },
    elder_guardian: { width: 1.99, height: 1.99, depth: 1.99, color: 0xb5a58c },
    warden:       { width: 0.9, height: 2.9,  depth: 0.9, color: 0x0b4a4a },
    ravager:      { width: 1.95, height: 2.2,  depth: 1.95, color: 0x767676 },
    ghast:        { width: 4.0, height: 4.0,  depth: 4.0, color: 0xf0f0f0 },
    magma_cube:   { width: 0.5, height: 0.5,  depth: 0.5, color: 0x440000 },
    hoglin:       { width: 1.4, height: 1.4,  depth: 1.4, color: 0x8b5a3a },
    zoglin:       { width: 1.4, height: 1.4,  depth: 1.4, color: 0x88aa88 },
    wither:       { width: 0.9, height: 3.5,  depth: 0.9, color: 0x222222 },
    ender_dragon: { width: 4.0, height: 4.0,  depth: 4.0, color: 0x1a1a2e },
    silverfish:   { width: 0.4, height: 0.3,  depth: 0.4, color: 0x6e6e6e },
    endermite:    { width: 0.4, height: 0.3,  depth: 0.4, color: 0x3b2754 },
    cave_spider:  { width: 0.7, height: 0.5,  depth: 0.7, color: 0x0c424e },
    shulker:      { width: 1.0, height: 1.0,  depth: 1.0, color: 0x8b5e8b },
    breeze:       { width: 0.6, height: 1.77, depth: 0.6, color: 0x89cff0 },
    bogged:       { width: 0.6, height: 1.99, depth: 0.6, color: 0x6a8a3a },
    panda:        { width: 1.3, height: 1.25, depth: 1.3, color: 0xf0f0f0 },
    polar_bear:   { width: 1.4, height: 1.4,  depth: 1.4, color: 0xf0f0f0 },
    ocelot:       { width: 0.6, height: 0.7,  depth: 0.6, color: 0xe8a020 },
    mooshroom:    { width: 0.9, height: 1.4,  depth: 0.9, color: 0xaa2222 },
    snow_golem:   { width: 0.7, height: 1.9,  depth: 0.7, color: 0xf0f0f0 },
    strider:      { width: 0.9, height: 1.7,  depth: 0.9, color: 0x993333 },
    axolotl:      { width: 0.75, height: 0.42, depth: 0.75, color: 0xf0a0c0 },
    camel:        { width: 1.7, height: 2.375, depth: 1.7, color: 0xd4a55a },
    sniffer:      { width: 1.9, height: 1.75, depth: 1.9, color: 0x6a8a44 },
    armadillo:    { width: 0.7, height: 0.65, depth: 0.7, color: 0xa0744a },
    vex:          { width: 0.4, height: 0.8,  depth: 0.4, color: 0x8899cc },
    wandering_trader: { width: 0.6, height: 1.95, depth: 0.6, color: 0x2255aa },
    creaking:     { width: 0.9, height: 2.7,  depth: 0.9, color: 0x3a2a1a },
    item:         { width: 0.25, height: 0.25, depth: 0.25, color: 0xffff00 },
    dropped_item: { width: 0.25, height: 0.25, depth: 0.25, color: 0xffff00 },
    experience_orb: { width: 0.2, height: 0.2, depth: 0.2, color: 0x80ff80 },
    minecart:         { width: 0.98, height: 0.7,  depth: 0.98, color: 0x666666 },
    chest_minecart:   { width: 0.98, height: 0.7,  depth: 0.98, color: 0x8b6914 },
    hopper_minecart:  { width: 0.98, height: 0.7,  depth: 0.98, color: 0x666666 },
    tnt_minecart:     { width: 0.98, height: 0.7,  depth: 0.98, color: 0xcc3333 },
    furnace_minecart: { width: 0.98, height: 0.7,  depth: 0.98, color: 0x888888 },
    spawner_minecart: { width: 0.98, height: 0.7,  depth: 0.98, color: 0x333366 },
    command_block_minecart: { width: 0.98, height: 0.7, depth: 0.98, color: 0xcc8855 },
    boat:             { width: 1.375, height: 0.5625, depth: 1.375, color: 0x8b6914 },
    oak_boat:         { width: 1.375, height: 0.5625, depth: 1.375, color: 0x8b6914 },
    spruce_boat:      { width: 1.375, height: 0.5625, depth: 1.375, color: 0x5b3a1e },
    birch_boat:       { width: 1.375, height: 0.5625, depth: 1.375, color: 0xc8b77a },
    jungle_boat:      { width: 1.375, height: 0.5625, depth: 1.375, color: 0x9a6a40 },
    acacia_boat:      { width: 1.375, height: 0.5625, depth: 1.375, color: 0xa0522d },
    dark_oak_boat:    { width: 1.375, height: 0.5625, depth: 1.375, color: 0x3a2a0a },
    mangrove_boat:    { width: 1.375, height: 0.5625, depth: 1.375, color: 0x6b3a3a },
    cherry_boat:      { width: 1.375, height: 0.5625, depth: 1.375, color: 0xd4869a },
    bamboo_raft:      { width: 1.375, height: 0.5625, depth: 1.375, color: 0x7a9a2a },
    oak_chest_boat:   { width: 1.375, height: 0.5625, depth: 1.375, color: 0x8b6914 },
    chest_boat:       { width: 1.375, height: 0.5625, depth: 1.375, color: 0x8b6914 },
    fishing_bobber:   { width: 0.25, height: 0.25, depth: 0.25, color: 0xcc3333 },
    leash_knot:       { width: 0.375, height: 0.5, depth: 0.375, color: 0x8b6914 },
    painting:         { width: 1.0, height: 1.0, depth: 0.0625, color: 0x8b6914 },
  };

  private static readonly DEFAULT_ENTITY_STYLE = { width: 0.6, height: 1.0, depth: 0.6, color: 0xaa55aa };

  /**
   * Create an entity group with textured rendering where possible.
   * Falls back to colored boxes for mobs without atlas textures.
   */
  private createTexturedEntityGroup(entityTypeName: string): THREE.Group {
    const group = new THREE.Group();

    // Handle item entities with metadata (e.g. "item:diamond_sword")
    if (entityTypeName.startsWith('item:')) {
      const itemName = entityTypeName.substring(5);
      return this.createItemEntityGroup(itemName);
    }

    // Falling block with state ID (e.g. "falling_block:1234") → textured cube from block mapping
    if (entityTypeName.startsWith('falling_block:')) {
      const stateId = parseInt(entityTypeName.substring(14), 10);
      const atlas = this.atlas;
      const blockEntry = atlas && !isNaN(stateId) ? atlas.mapping.blocks[stateId] : undefined;
      if (atlas && blockEntry) {
        const blockMesh = PrismarineRenderer.createBlockMeshFromFaces(blockEntry, atlas);
        blockMesh.scale.set(2.86, 2.86, 2.86);
        blockMesh.position.y = 0.5;
        group.add(blockMesh);
        this.addEntityNametag(group, 'falling_block', 1.5);
        return group;
      }
    }

    // Block entities (TNT, primed_tnt) → textured cube
    const blockName = PrismarineRenderer.ENTITY_BLOCK_NAMES[entityTypeName];
    if (blockName) {
      const blockMesh = this.createHeldItemMesh(blockName);
      blockMesh.scale.set(2.86, 2.86, 2.86);
      blockMesh.position.y = 0.5;
      group.add(blockMesh);
      this.addEntityNametag(group, entityTypeName, 1.5);
      return group;
    }

    // Projectile / sprite entities → billboard from atlas
    const itemTexName = PrismarineRenderer.ENTITY_ITEM_TEXTURES[entityTypeName];
    if (itemTexName) {
      const spriteMesh = this.createEntitySprite(itemTexName);
      if (spriteMesh) {
        // End crystals should be much larger than projectiles
        if (entityTypeName === 'end_crystal' || entityTypeName === 'ender_crystal') {
          spriteMesh.scale.set(3.0, 3.0, 3.0);
          spriteMesh.position.y = 1.0;
        }
        group.add(spriteMesh);
        return group;
      }
    }

    // Item / dropped_item with no metadata → generic item cube
    if (entityTypeName === 'item' || entityTypeName === 'dropped_item') {
      return this.createItemEntityGroup('');
    }

    // Mobs: try textured model first, fall back to colored box
    const style = PrismarineRenderer.ENTITY_STYLES[entityTypeName] ?? PrismarineRenderer.DEFAULT_ENTITY_STYLE;
    const entityTex = this.entityTextureCache.get(entityTypeName);

    if (entityTex && PrismarineRenderer.HUMANOID_MOBS.has(entityTypeName)) {
      // Textured humanoid mob - same model structure as player
      const texMat = new THREE.MeshLambertMaterial({ map: entityTex, alphaTest: 0.1 });

      // Body pivot
      const bodyPivot = new THREE.Group();
      bodyPivot.name = 'bodyPivot';

      // Torso
      const torsoGeo = new THREE.BoxGeometry(0.5, 0.75, 0.25);
      PrismarineRenderer.setSkinUVs(torsoGeo, {
        front: [20, 20, 8, 12], right: [16, 20, 4, 12], back: [32, 20, 8, 12], left: [28, 20, 4, 12],
        top: [20, 16, 8, 4], bottom: [28, 16, 8, 4],
      });
      const torso = new THREE.Mesh(torsoGeo, texMat);
      torso.position.y = 0.375;
      bodyPivot.add(torso);

      // Head
      const headGroup = new THREE.Group();
      headGroup.name = 'head';
      headGroup.position.y = 0.75;
      const headGeo = new THREE.BoxGeometry(0.5, 0.5, 0.5);
      PrismarineRenderer.setSkinUVs(headGeo, {
        front: [8, 8, 8, 8], right: [0, 8, 8, 8], back: [24, 8, 8, 8], left: [16, 8, 8, 8],
        top: [8, 0, 8, 8], bottom: [16, 0, 8, 8],
      });
      const headMesh = new THREE.Mesh(headGeo, texMat);
      headMesh.position.y = 0.25;
      headGroup.add(headMesh);
      bodyPivot.add(headGroup);

      // Right arm
      const rightArmPivot = new THREE.Group();
      rightArmPivot.name = 'rightArm';
      rightArmPivot.position.set(0.375, 0.75, 0);
      const rArmGeo = new THREE.BoxGeometry(0.25, 0.75, 0.25);
      PrismarineRenderer.setSkinUVs(rArmGeo, {
        front: [44, 20, 4, 12], right: [40, 20, 4, 12], back: [52, 20, 4, 12], left: [48, 20, 4, 12],
        top: [44, 16, 4, 4], bottom: [48, 16, 4, 4],
      });
      const rArm = new THREE.Mesh(rArmGeo, texMat);
      rArm.position.y = -0.375;
      rightArmPivot.add(rArm);
      bodyPivot.add(rightArmPivot);

      // Left arm
      const leftArmPivot = new THREE.Group();
      leftArmPivot.name = 'leftArm';
      leftArmPivot.position.set(-0.375, 0.75, 0);
      const lArmGeo = new THREE.BoxGeometry(0.25, 0.75, 0.25);
      PrismarineRenderer.setSkinUVs(lArmGeo, {
        front: [36, 52, 4, 12], right: [32, 52, 4, 12], back: [44, 52, 4, 12], left: [40, 52, 4, 12],
        top: [36, 48, 4, 4], bottom: [40, 48, 4, 4],
      });
      const lArm = new THREE.Mesh(lArmGeo, texMat);
      lArm.position.y = -0.375;
      leftArmPivot.add(lArm);
      bodyPivot.add(leftArmPivot);

      bodyPivot.position.y = 0.75;
      group.add(bodyPivot);

      // Right leg
      const rightLegPivot = new THREE.Group();
      rightLegPivot.name = 'rightLeg';
      rightLegPivot.position.set(0.125, 0.75, 0);
      const rLegGeo = new THREE.BoxGeometry(0.25, 0.75, 0.25);
      PrismarineRenderer.setSkinUVs(rLegGeo, {
        front: [4, 20, 4, 12], right: [0, 20, 4, 12], back: [12, 20, 4, 12], left: [8, 20, 4, 12],
        top: [4, 16, 4, 4], bottom: [8, 16, 4, 4],
      });
      const rLeg = new THREE.Mesh(rLegGeo, texMat);
      rLeg.position.y = -0.375;
      rightLegPivot.add(rLeg);
      group.add(rightLegPivot);

      // Left leg
      const leftLegPivot = new THREE.Group();
      leftLegPivot.name = 'leftLeg';
      leftLegPivot.position.set(-0.125, 0.75, 0);
      const lLegGeo = new THREE.BoxGeometry(0.25, 0.75, 0.25);
      PrismarineRenderer.setSkinUVs(lLegGeo, {
        front: [20, 52, 4, 12], right: [16, 52, 4, 12], back: [28, 52, 4, 12], left: [24, 52, 4, 12],
        top: [20, 48, 4, 4], bottom: [24, 48, 4, 4],
      });
      const lLeg = new THREE.Mesh(lLegGeo, texMat);
      lLeg.position.y = -0.375;
      leftLegPivot.add(lLeg);
      group.add(leftLegPivot);

      this.addEntityNametag(group, entityTypeName, 2.3);
    } else if (entityTex) {
      // Textured non-humanoid mob - box with entity texture mapped to faces
      const texMat = new THREE.MeshLambertMaterial({ map: entityTex, alphaTest: 0.1 });
      const geo = new THREE.BoxGeometry(style.width, style.height, style.depth);
      const mesh = new THREE.Mesh(geo, texMat);
      mesh.position.y = style.height / 2;
      group.add(mesh);

      this.addEntityNametag(group, entityTypeName, style.height + 0.5);
    } else if (PrismarineRenderer.HUMANOID_MOBS.has(entityTypeName)) {
      // Colored humanoid fallback
      const bodyHeight = style.height - 0.5;
      const bodyGeo = new THREE.BoxGeometry(style.width, bodyHeight, style.depth);
      const bodyMat = new THREE.MeshLambertMaterial({ color: style.color });
      const body = new THREE.Mesh(bodyGeo, bodyMat);
      body.position.y = bodyHeight / 2;
      group.add(body);

      const headSize = Math.max(style.width, 0.5);
      const headGeo = new THREE.BoxGeometry(headSize, headSize, headSize);
      const headColor = new THREE.Color(style.color);
      headColor.offsetHSL(0, 0, 0.15);
      const headMat = new THREE.MeshLambertMaterial({ color: headColor });
      const headMesh = new THREE.Mesh(headGeo, headMat);
      headMesh.position.y = bodyHeight + headSize / 2;
      group.add(headMesh);

      this.addEntityNametag(group, entityTypeName, style.height + 0.5);
    } else {
      // Colored non-humanoid fallback
      const geo = new THREE.BoxGeometry(style.width, style.height, style.depth);
      const mat = new THREE.MeshLambertMaterial({ color: style.color });
      const mesh = new THREE.Mesh(geo, mat);
      mesh.position.y = style.height / 2;
      group.add(mesh);

      this.addEntityNametag(group, entityTypeName, style.height + 0.5);
    }

    return group;
  }

  /**
   * Create an item entity group (dropped item on ground).
   */
  private createItemEntityGroup(itemName: string): THREE.Group {
    const group = new THREE.Group();

    if (itemName) {
      const itemMesh = this.createHeldItemMesh(itemName);
      itemMesh.position.y = 0.25;
      itemMesh.scale.set(0.6, 0.6, 0.6);
      group.add(itemMesh);
      return group;
    }

    // Fallback: small yellow cube
    const geo = new THREE.BoxGeometry(0.25, 0.25, 0.25);
    const mat = new THREE.MeshLambertMaterial({ color: 0xffff00 });
    const mesh = new THREE.Mesh(geo, mat);
    mesh.position.y = 0.25;
    group.add(mesh);

    return group;
  }

  /**
   * Create a billboard sprite for a projectile/entity using atlas item texture.
   */
  private createEntitySprite(itemTexName: string): THREE.Mesh | null {
    const atlas = this.atlas;
    if (!atlas) return null;

    const itemInfo = this.itemMapping.get(itemTexName);
    const texKey = itemInfo?.texture || `item/${itemTexName}`;
    const texUV = atlas.mapping.textures[texKey] ?? PrismarineRenderer.fallbackItemTexture(itemTexName, atlas);

    if (!texUV) return null;

    const mat = new THREE.MeshBasicMaterial({
      map: atlas.texture,
      alphaTest: 0.1,
      side: THREE.DoubleSide,
      transparent: true,
    });

    const geo = new THREE.PlaneGeometry(0.5, 0.5);
    setPlaneUVs(geo, texUV);

    const mesh = new THREE.Mesh(geo, mat);
    mesh.position.y = 0.25;
    // Billboard: always face camera (set in render loop or use onBeforeRender)
    mesh.onBeforeRender = (_renderer, _scene, camera) => {
      mesh.quaternion.copy(camera.quaternion);
    };
    return mesh;
  }

  /**
   * Add a floating nametag sprite to an entity group.
   */
  private addEntityNametag(group: THREE.Group, entityTypeName: string, yOffset: number): void {
    const displayName = entityTypeName.replace(/_/g, ' ');
    const ctx = create2dContext(256, 48);
    ctx.fillStyle = 'rgba(0,0,0,0.5)';
    ctx.fillRect(0, 0, 256, 48);
    ctx.fillStyle = '#cccccc';
    ctx.font = 'bold 24px sans-serif';
    ctx.textAlign = 'center';
    ctx.fillText(displayName, 128, 32);
    const texture = new THREE.CanvasTexture(ctx.canvas);
    const spriteMat = new THREE.SpriteMaterial({ map: texture });
    const sprite = new THREE.Sprite(spriteMat);
    sprite.position.y = yOffset;
    sprite.scale.set(1.5, 0.35, 1);
    group.add(sprite);
  }

  /**
   * Update an entity's position and rotation in the scene.
   */
  updateEntity(entityId: number, x: number, y: number, z: number, yaw: number, _pitch: number, entityTypeName?: string): void {
    let group = this.entityMeshes.get(entityId);
    const prevTypeName = this.entityTypeNames.get(entityId);

    // Recreate entity mesh if type name changed (e.g. "item" → "item:diamond_sword")
    if (group && entityTypeName && entityTypeName !== prevTypeName) {
      this.scene.remove(group);
      group = undefined;
    }

    if (!group) {
      const typeName = entityTypeName || '';
      group = this.createTexturedEntityGroup(typeName);
      this.scene.add(group);
      this.entityMeshes.set(entityId, group);
      if (typeName) this.entityTypeNames.set(entityId, typeName);
    }

    group.position.set(x, y, z);
    // Don't rotate dropped items (they spin in updateEntityAnimations)
    const typeName = this.entityTypeNames.get(entityId);
    if (typeName && !typeName.startsWith('item') && typeName !== 'dropped_item') {
      group.rotation.y = Math.PI - yaw * (Math.PI / 180);
    }
  }

  /**
   * Remove an entity from the scene, optionally spawning an explosion.
   */
  removeEntity(entityId: number, entityTypeName?: string): void {
    const group = this.entityMeshes.get(entityId);
    if (group) {
      // Trigger explosion for explosive entity types
      const typeName = entityTypeName || this.entityTypeNames.get(entityId) || '';
      if (PrismarineRenderer.EXPLOSIVE_TYPES.has(typeName)) {
        this.spawnExplosion(group.position.x, group.position.y + 0.5, group.position.z, typeName === 'tnt' ? 2.0 : 1.0, typeName);
      }
      this.scene.remove(group);
      this.entityMeshes.delete(entityId);
      this.entityTypeNames.delete(entityId);
    }
  }

  /**
   * Get all entity IDs currently known to the renderer.
   */
  getKnownEntityIds(): Set<number> {
    return new Set(this.entityMeshes.keys());
  }

  /**
   * Spawn a particle explosion effect at the given position.
   * Wind charges produce a blue/cyan horizontal ring burst; other explosions produce fire + smoke.
   */
  private spawnExplosion(x: number, y: number, z: number, size: number, typeName: string): void {
    if (typeName === 'wind_charge') {
      this.spawnWindChargeExplosion(x, y, z);
      return;
    }

    // --- TNT / generic explosion: fire + smoke particles with a flash sphere ---
    const count = Math.floor(40 + size * 20);
    const positions = new Float32Array(count * 3);
    const colors = new Float32Array(count * 3);
    const velocities = new Float32Array(count * 3);
    const smokeStart = Math.floor(count * 0.5); // first half = fire, second half = smoke

    for (let i = 0; i < count; i++) {
      const i3 = i * 3;
      positions[i3] = x;
      positions[i3 + 1] = y;
      positions[i3 + 2] = z;

      const theta = Math.random() * Math.PI * 2;
      const phi = Math.random() * Math.PI;

      if (i < smokeStart) {
        // Fire particles - fast outward burst
        const speed = (2 + Math.random() * 3) * size;
        velocities[i3] = Math.sin(phi) * Math.cos(theta) * speed;
        velocities[i3 + 1] = Math.cos(phi) * speed * 0.5 + Math.random() * 1.5;
        velocities[i3 + 2] = Math.sin(phi) * Math.sin(theta) * speed;
        // Orange-yellow
        const t = Math.random();
        colors[i3] = 1.0;
        colors[i3 + 1] = 0.3 + t * 0.5;
        colors[i3 + 2] = t * 0.1;
      } else {
        // Smoke particles - slow upward drift
        const speed = (0.5 + Math.random() * 1.5) * size;
        velocities[i3] = Math.sin(phi) * Math.cos(theta) * speed * 0.4;
        velocities[i3 + 1] = 1.0 + Math.random() * 2.0;
        velocities[i3 + 2] = Math.sin(phi) * Math.sin(theta) * speed * 0.4;
        // Gray-black smoke
        const g = 0.15 + Math.random() * 0.25;
        colors[i3] = g;
        colors[i3 + 1] = g;
        colors[i3 + 2] = g;
      }
    }

    const mat = new THREE.PointsMaterial({
      size: 0.3 * size,
      vertexColors: true,
      transparent: true,
      opacity: 1.0,
      depthWrite: false,
      sizeAttenuation: true,
    });

    // Flash sphere - white sphere that expands and fades quickly
    const flashGeo = new THREE.SphereGeometry(0.3 * size, 12, 12);
    const flashMat = new THREE.MeshBasicMaterial({
      color: 0xffffee,
      transparent: true,
      opacity: 0.9,
      depthWrite: false,
      side: THREE.DoubleSide,
    });
    const flash = new THREE.Mesh(flashGeo, flashMat);
    flash.position.set(x, y, z);
    this.scene.add(flash);

    this.addExplosion({ positions, colors, velocities, material: mat, duration: 800, gravity: true, effectMesh: flash });
  }

  /**
   * Spawn a wind-charge-style explosion: blue/cyan particles in a horizontal ring burst
   * plus an expanding transparent ring.
   */
  private spawnWindChargeExplosion(x: number, y: number, z: number): void {
    const count = 18;
    const positions = new Float32Array(count * 3);
    const colors = new Float32Array(count * 3);
    const velocities = new Float32Array(count * 3);

    for (let i = 0; i < count; i++) {
      const i3 = i * 3;
      positions[i3] = x;
      positions[i3 + 1] = y;
      positions[i3 + 2] = z;

      // Horizontal ring pattern - particles spread outward on the XZ plane
      const angle = (i / count) * Math.PI * 2 + (Math.random() - 0.5) * 0.4;
      const speed = 2.5 + Math.random() * 1.5;
      velocities[i3] = Math.cos(angle) * speed;
      velocities[i3 + 1] = (Math.random() - 0.5) * 0.5; // very slight vertical spread
      velocities[i3 + 2] = Math.sin(angle) * speed;

      // Blue / cyan / white palette
      const t = Math.random();
      colors[i3] = 0.5 + t * 0.5;      // R: 0.5-1.0
      colors[i3 + 1] = 0.7 + t * 0.3;  // G: 0.7-1.0
      colors[i3 + 2] = 1.0;             // B: 1.0
    }

    const mat = new THREE.PointsMaterial({
      size: 0.2,
      vertexColors: true,
      transparent: true,
      opacity: 1.0,
      depthWrite: false,
      sizeAttenuation: true,
    });

    const ring = this.spawnWindChargeRing(x, y, z);

    this.addExplosion({ positions, colors, velocities, material: mat, duration: 500, gravity: false, effectMesh: ring });
  }

  private addExplosion(spec: {
    positions: Float32Array;
    colors: Float32Array;
    velocities: Float32Array;
    material: THREE.PointsMaterial;
    duration: number;
    gravity: boolean;
    effectMesh: EffectMesh;
  }): void {
    const positionAttribute = new THREE.BufferAttribute(spec.positions, 3);
    const geometry = new THREE.BufferGeometry();
    geometry.setAttribute('position', positionAttribute);
    geometry.setAttribute('color', new THREE.BufferAttribute(spec.colors, 3));
    const particles = new THREE.Points(geometry, spec.material);
    this.scene.add(particles);
    this.activeExplosions.push({
      particles,
      positions: spec.positions,
      positionAttribute,
      velocities: spec.velocities,
      startTime: performance.now(),
      duration: spec.duration,
      gravity: spec.gravity,
      effectMeshes: [spec.effectMesh],
    });
  }

  private disposeExplosion(explosion: ActiveExplosion): void {
    this.scene.remove(explosion.particles);
    explosion.particles.geometry.dispose();
    explosion.particles.material.dispose();
    for (const mesh of explosion.effectMeshes) {
      disposeEffectMesh(this.scene, mesh);
    }
  }

  /**
   * Create an expanding, fading horizontal ring for wind charge effects.
   * Returns the ring mesh (lifecycle managed by the explosion entry).
   */
  private spawnWindChargeRing(x: number, y: number, z: number): EffectMesh {
    const ringGeo = new THREE.RingGeometry(0.3, 0.5, 32);
    const ringMat = new THREE.MeshBasicMaterial({
      color: 0x89cff0,
      transparent: true,
      opacity: 0.8,
      depthWrite: false,
      side: THREE.DoubleSide,
    });
    const ring = new THREE.Mesh(ringGeo, ringMat);
    ring.position.set(x, y, z);
    // Rotate to lie flat (horizontal)
    ring.rotation.x = -Math.PI / 2;
    this.scene.add(ring);
    return ring;
  }

  /**
   * Update active explosion particles. Called each frame.
   */
  private updateExplosions(): void {
    const now = performance.now();
    for (let i = this.activeExplosions.length - 1; i >= 0; i--) {
      const exp = this.activeExplosions[i];
      if (!exp) continue;
      const elapsed = now - exp.startTime;
      const t = Math.min(elapsed / exp.duration, 1.0);

      if (t >= 1.0) {
        this.disposeExplosion(exp);
        this.activeExplosions.splice(i, 1);
        continue;
      }

      PrismarineRenderer.advanceParticles(exp, 0.016);

      const mat = exp.particles.material;
      mat.opacity = 1.0 - t;
      mat.size *= 0.98;

      for (const mesh of exp.effectMeshes) {
        PrismarineRenderer.animateEffectMesh(mesh, t);
      }
    }
  }

  private static advanceParticles(explosion: ActiveExplosion, dt: number): void {
    const { positions, velocities } = explosion;
    for (let j3 = 0; j3 + 2 < positions.length; j3 += 3) {
      const vx = velocities[j3] ?? 0;
      const vy = velocities[j3 + 1] ?? 0;
      const vz = velocities[j3 + 2] ?? 0;
      positions[j3] = (positions[j3] ?? 0) + vx * dt;
      positions[j3 + 1] = (positions[j3 + 1] ?? 0) + vy * dt;
      positions[j3 + 2] = (positions[j3 + 2] ?? 0) + vz * dt;
      if (explosion.gravity) {
        velocities[j3 + 1] = vy - 9.8 * dt;
      }
    }
    explosion.positionAttribute.needsUpdate = true;
  }

  private static animateEffectMesh(mesh: EffectMesh, t: number): void {
    if (mesh.geometry instanceof THREE.SphereGeometry) {
      const flashT = Math.min(t / 0.2, 1.0);
      const scale = 1.0 + flashT * 4.0;
      mesh.scale.set(scale, scale, scale);
      mesh.material.opacity = 0.9 * (1.0 - flashT);
    } else if (mesh.geometry instanceof THREE.RingGeometry) {
      const ringT = Math.min(t / 0.8, 1.0);
      const scale = 1.0 + ringT * (2.0 / 0.3 - 1.0);
      mesh.scale.set(scale, scale, scale);
      mesh.material.opacity = 0.8 * (1.0 - ringT);
    }
  }

  // Player meshes: uuid → group (body + head + nametag)
  private readonly playerMeshes = new Map<string, THREE.Group>();

  /**
   * Create a multi-part player model group.
   */
  private createPlayerGroup(name: string): THREE.Group {
    const group = new THREE.Group();

    // Body pivot - tilts when sneaking
    const bodyPivot = new THREE.Group();
    bodyPivot.name = 'bodyPivot';

    // Torso (0.5 x 0.75 x 0.25)
    const torsoGeo = new THREE.BoxGeometry(0.5, 0.75, 0.25);
    const torsoMat = new THREE.MeshLambertMaterial({ color: 0x4488cc });
    const torso = new THREE.Mesh(torsoGeo, torsoMat);
    torso.position.y = 0.375;
    bodyPivot.add(torso);

    // Head (0.5 x 0.5 x 0.5) - separate pivot for yaw + pitch
    const head = new THREE.Group();
    head.name = 'head';
    head.rotation.order = 'YXZ'; // yaw (Y) first, then pitch (X)
    head.position.y = 0.75;
    const headMesh = new THREE.Mesh(
      new THREE.BoxGeometry(0.5, 0.5, 0.5),
      new THREE.MeshLambertMaterial({ color: 0xddaa77 }),
    );
    headMesh.position.y = 0.25;
    head.add(headMesh);
    bodyPivot.add(head);

    // Right arm pivot at shoulder (+X side)
    const rightArmPivot = new THREE.Group();
    rightArmPivot.name = 'rightArm';
    rightArmPivot.position.set(0.375, 0.75, 0);
    const rightArm = new THREE.Mesh(
      new THREE.BoxGeometry(0.25, 0.75, 0.25),
      new THREE.MeshLambertMaterial({ color: 0x4488cc }),
    );
    rightArm.position.y = -0.375;
    rightArmPivot.add(rightArm);
    bodyPivot.add(rightArmPivot);

    // Left arm pivot at shoulder (-X side)
    const leftArmPivot = new THREE.Group();
    leftArmPivot.name = 'leftArm';
    leftArmPivot.position.set(-0.375, 0.75, 0);
    const leftArm = new THREE.Mesh(
      new THREE.BoxGeometry(0.25, 0.75, 0.25),
      new THREE.MeshLambertMaterial({ color: 0x4488cc }),
    );
    leftArm.position.y = -0.375;
    leftArmPivot.add(leftArm);
    bodyPivot.add(leftArmPivot);

    bodyPivot.position.y = 0.75;
    group.add(bodyPivot);

    // Right leg pivot at hip (+X side)
    const rightLegPivot = new THREE.Group();
    rightLegPivot.name = 'rightLeg';
    rightLegPivot.position.set(0.125, 0.75, 0);
    const rightLeg = new THREE.Mesh(
      new THREE.BoxGeometry(0.25, 0.75, 0.25),
      new THREE.MeshLambertMaterial({ color: 0x333366 }),
    );
    rightLeg.position.y = -0.375;
    rightLegPivot.add(rightLeg);
    group.add(rightLegPivot);

    // Left leg pivot at hip (-X side)
    const leftLegPivot = new THREE.Group();
    leftLegPivot.name = 'leftLeg';
    leftLegPivot.position.set(-0.125, 0.75, 0);
    const leftLeg = new THREE.Mesh(
      new THREE.BoxGeometry(0.25, 0.75, 0.25),
      new THREE.MeshLambertMaterial({ color: 0x333366 }),
    );
    leftLeg.position.y = -0.375;
    leftLegPivot.add(leftLeg);
    group.add(leftLegPivot);

    // Nametag sprite above head (no background, with health)
    const nametagSprite = PrismarineRenderer.createNametagSprite(name, 20);
    group.add(nametagSprite);

    return group;
  }

  /**
   * Create a nametag sprite with player name and health hearts (no background).
   */
  private static createNametagSprite(name: string, health: number): THREE.Sprite {
    const ctx = create2dContext(512, 64);
    ctx.clearRect(0, 0, 512, 64);

    // Draw name
    ctx.fillStyle = '#ffffff';
    ctx.font = 'bold 28px sans-serif';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.strokeStyle = 'rgba(0,0,0,0.8)';
    ctx.lineWidth = 3;
    ctx.strokeText(name, 256, 20);
    ctx.fillText(name, 256, 20);

    // Draw health as hearts below name
    const fullHearts = Math.floor(health / 2);
    const halfHeart = health % 2 >= 1;
    const totalHearts = 10;
    const heartSize = 12;
    const startX = 256 - (totalHearts * (heartSize + 1)) / 2;

    for (let i = 0; i < totalHearts; i++) {
      const hx = startX + i * (heartSize + 1);
      const hy = 42;
      if (i < fullHearts) {
        ctx.fillStyle = '#ff2222';
      } else if (i === fullHearts && halfHeart) {
        ctx.fillStyle = '#ff6666';
      } else {
        ctx.fillStyle = 'rgba(80,0,0,0.5)';
      }
      // Simple heart shape using unicode or a filled rect (pixel style)
      ctx.fillRect(hx, hy, heartSize, heartSize - 2);
      // Small notch at top for heart shape
      ctx.fillRect(hx + 1, hy - 2, heartSize / 2 - 1, 3);
      ctx.fillRect(hx + heartSize / 2 + 1, hy - 2, heartSize / 2 - 2, 3);
    }

    const texture = new THREE.CanvasTexture(ctx.canvas);
    texture.magFilter = THREE.LinearFilter;
    const spriteMat = new THREE.SpriteMaterial({ map: texture, transparent: true, depthTest: false });
    const sprite = new THREE.Sprite(spriteMat);
    sprite.name = 'nametag';
    sprite.position.y = 2.3;
    sprite.scale.set(3, 0.4, 1);
    sprite.renderOrder = 999;
    return sprite;
  }

  /**
   * Update a player's position, rotation, and animations in the scene.
   */
  // Armor material colors by armor type keyword (fallback when no block texture)
  private static readonly ARMOR_COLORS: Record<string, number> = {
    diamond: 0x00cccc,
    iron: 0xc0c0c0,
    gold: 0xffd700,
    golden: 0xffd700,
    netherite: 0x3a3a3a,
    leather: 0x8b4513,
    chainmail: 0xa8a8a8,
    chain: 0xa8a8a8,
    turtle: 0x40a060,
  };

  private static getArmorColor(itemName: string): number | null {
    for (const [key, color] of Object.entries(PrismarineRenderer.ARMOR_COLORS)) {
      if (itemName.includes(key)) return color;
    }
    return null;
  }

  /** Get the armor material name from an item name (e.g. "diamond_helmet" → "diamond") */
  private static getArmorMaterial(itemName: string): string | null {
    const materials = ['diamond', 'iron', 'golden', 'netherite', 'leather', 'chainmail', 'turtle'];
    for (const mat of materials) {
      if (itemName.includes(mat)) return mat === 'golden' ? 'gold' : mat;
    }
    return null;
  }

  /** Get the armor layer key: leggings use layer2, everything else uses layer1 */
  private static getArmorLayer(itemName: string): string {
    return itemName.includes('leggings') ? 'layer2' : 'layer1';
  }

  /**
   * Minecraft armor UV regions (pixel coords on 64×32 texture).
   * Derived from HumanoidModel textureOffsets and box dimensions.
   * Format: [x, y, width, height] in pixels.
   */
  private static readonly ARMOR_UV_PARTS: Readonly<Record<'head' | 'body' | 'arm' | 'leg', BoxFaceRegions>> = {
    // Head: textureOffset(0,0), box 8×8×8
    head: {
      right: [0, 8, 8, 8], left: [16, 8, 8, 8],
      top: [8, 0, 8, 8], bottom: [16, 0, 8, 8],
      front: [8, 8, 8, 8], back: [24, 8, 8, 8],
    },
    // Body: textureOffset(16,16), box 8×12×4
    body: {
      right: [16, 20, 4, 12], left: [28, 20, 4, 12],
      top: [20, 16, 8, 4], bottom: [28, 16, 8, 4],
      front: [20, 20, 8, 12], back: [32, 20, 8, 12],
    },
    // Arm: textureOffset(40,16), box 4×12×4
    arm: {
      right: [40, 20, 4, 12], left: [48, 20, 4, 12],
      top: [44, 16, 4, 4], bottom: [48, 16, 4, 4],
      front: [44, 20, 4, 12], back: [52, 20, 4, 12],
    },
    // Leg: textureOffset(0,16), box 4×12×4
    leg: {
      right: [0, 20, 4, 12], left: [8, 20, 4, 12],
      top: [4, 16, 4, 4], bottom: [8, 16, 4, 4],
      front: [4, 20, 4, 12], back: [12, 20, 4, 12],
    },
  };

  /**
   * Set UV coordinates on a BoxGeometry to map a specific armor part
   * from a 64×32 armor texture.
   */
  private static setArmorUVs(
    geo: THREE.BoxGeometry,
    partType: 'head' | 'body' | 'arm' | 'leg',
    mirrored = false,
  ): void {
    const part = PrismarineRenderer.ARMOR_UV_PARTS[partType];
    const uvAttr = geo.getAttribute('uv');
    const TW = 64, TH = 32;

    for (const [fi, orderedFace] of BOX_FACE_ORDER.entries()) {
      let faceName = orderedFace;
      // When mirrored (left arm/leg), swap right↔left face assignments
      if (mirrored) {
        if (faceName === 'right') faceName = 'left';
        else if (faceName === 'left') faceName = 'right';
      }

      const [px, py, pw, ph] = part[faceName];
      const u = px / TW;
      const v = py / TH;
      const su = pw / TW;
      const sv = ph / TH;

      const base = fi * 4;
      if (mirrored && faceName !== 'top' && faceName !== 'bottom') {
        // Flip U horizontally for mirrored side/front/back faces
        uvAttr.setXY(base + 0, u + su, v);
        uvAttr.setXY(base + 1, u, v);
        uvAttr.setXY(base + 2, u + su, v + sv);
        uvAttr.setXY(base + 3, u, v + sv);
      } else {
        uvAttr.setXY(base + 0, u, v);
        uvAttr.setXY(base + 1, u + su, v);
        uvAttr.setXY(base + 2, u, v + sv);
        uvAttr.setXY(base + 3, u + su, v + sv);
      }
    }
    uvAttr.needsUpdate = true;
  }

  /**
   * Create an armor piece mesh using the actual Minecraft armor texture
   * with proper UV mapping for the given body part.
   * Falls back to a solid color if no armor texture is cached.
   */
  private createArmorMesh(
    itemName: string,
    width: number, height: number, depth: number,
    partType: 'head' | 'body' | 'arm' | 'leg',
    mirrored = false,
  ): THREE.Mesh {
    const geo = new THREE.BoxGeometry(width, height, depth);

    const material = PrismarineRenderer.getArmorMaterial(itemName);
    const layer = PrismarineRenderer.getArmorLayer(itemName);

    if (material) {
      const cacheKey = `${material}_${layer}`;
      const armorTex = this.armorTextureCache.get(cacheKey);

      if (armorTex) {
        PrismarineRenderer.setArmorUVs(geo, partType, mirrored);
        return new THREE.Mesh(geo, new THREE.MeshLambertMaterial({
          map: armorTex,
          alphaTest: 0.1,
        }));
      }
    }

    // Fallback: solid color
    const color = PrismarineRenderer.getArmorColor(itemName) ?? 0x888888;
    return new THREE.Mesh(geo, new THREE.MeshLambertMaterial({ color }));
  }

  // Track current armor to avoid recreating every frame
  private readonly playerArmorState = new Map<string, string>();

  // Track nametag health to avoid re-rendering every frame
  private readonly playerNametagHealth = new Map<string, number>();

  updatePlayer(
    uuid: string, name: string,
    x: number, y: number, z: number,
    yaw: number, pitch: number,
    sneaking = false,
    lastSwingTime = 0,
    currentTimeMs = 0,
    skinUuid?: string,
    heldItem?: string,
    lastDamageTime = 0,
    equipment?: Map<number, string | { itemName: string; enchantments?: { id: string; level: number }[] }>,
    health = 20,
  ): void {
    let group = this.playerMeshes.get(uuid);

    if (!group) {
      group = this.createPlayerGroup(name);
      this.scene.add(group);
      this.playerMeshes.set(uuid, group);
    }

    const skinId = skinUuid || uuid;
    this.playerSkinIds.set(uuid, skinId);
    if (!this.skinTextures.has(skinId)) {
      this.skinTextures.set(skinId, null);
      void this.loadSkinTexture(skinId).then(tex => {
        if (tex) {
          this.skinTextures.set(skinId, tex);
          this.applySkinToPlayersUsing(skinId, tex);
        }
      });
    } else {
      const cachedTex = this.skinTextures.get(skinId);
      if (cachedTex) {
        this.applySkinToGroup(group, cachedTex);
      }
    }

    group.position.set(x, y, z);

    // Update nametag if health changed
    const prevHealth = this.playerNametagHealth.get(uuid);
    if (prevHealth === undefined || Math.floor(prevHealth) !== Math.floor(health)) {
      this.playerNametagHealth.set(uuid, health);
      const oldTag = group.getObjectByName('nametag');
      if (oldTag instanceof THREE.Sprite) {
        group.remove(oldTag);
        oldTag.material.map?.dispose();
        oldTag.material.dispose();
      }
      const newTag = PrismarineRenderer.createNametagSprite(name, health);
      group.add(newTag);
    }

    // Sneak animation - lean upper body forward (toward local -Z = MC forward)
    const bodyPivot = group.getObjectByName('bodyPivot');
    if (bodyPivot) {
      if (sneaking) {
        bodyPivot.position.y = 0.45;
        bodyPivot.rotation.x = -0.5;
      } else {
        bodyPivot.position.y = 0.75;
        bodyPivot.rotation.x = 0;
      }
    }

    // Arm swing animation
    const rightArm = group.getObjectByName('rightArm');
    const leftArm = group.getObjectByName('leftArm');
    const rightLeg = group.getObjectByName('rightLeg');
    const leftLeg = group.getObjectByName('leftLeg');
    const swingElapsed = currentTimeMs - lastSwingTime;

    const isSwinging = lastSwingTime > 0 && swingElapsed >= 0 && swingElapsed < 300;

    // Walk cycle - compute speed and movement direction from position deltas
    const prev = this.playerPrevPositions.get(uuid);
    let speed = 0;
    let moveDir = 0; // movement direction in degrees (Minecraft yaw convention)
    if (prev) {
      const dx = x - prev.x;
      const dz = z - prev.z;
      const dt = (currentTimeMs - prev.time) / 1000;
      const elapsed = dt > 0 ? dt : 0.016;
      speed = Math.sqrt(dx * dx + dz * dz) / elapsed;
      if (speed > 0.5) {
        // Convert movement vector to Minecraft yaw degrees
        // MC yaw: 0=south(+Z), 90=west(-X), 180=north(-Z), 270=east(+X)
        moveDir = -Math.atan2(-dx, dz) * (180 / Math.PI);
      } else {
        moveDir = prev.moveDir;
      }
      if (dt >= 0.05) {
        this.playerPrevPositions.set(uuid, { x, z, time: currentTimeMs, moveDir });
      }
    } else {
      this.playerPrevPositions.set(uuid, { x, z, time: currentTimeMs, moveDir: yaw });
    }

    // Body/head yaw split:
    // In Minecraft, the body faces the movement direction when walking.
    // The head (yaw from replay) can look independently up to ~75° from body.
    // When standing still, body stays put but clamps to 75° from head.
    const MAX_HEAD_TURN = 15;
    let bodyYaw = this.playerBodyYaw.get(uuid) ?? yaw;

    if (speed > 1.0) {
      // Moving: body faces movement direction, smoothly lerp toward it
      let diff = moveDir - bodyYaw;
      diff = ((diff % 360) + 540) % 360 - 180;
      bodyYaw += diff * 0.25;
    }

    // Always clamp body to within MAX_HEAD_TURN of head yaw
    let diff = yaw - bodyYaw;
    diff = ((diff % 360) + 540) % 360 - 180;
    if (Math.abs(diff) > MAX_HEAD_TURN) {
      bodyYaw = yaw - Math.sign(diff) * MAX_HEAD_TURN;
    }

    this.playerBodyYaw.set(uuid, bodyYaw);

    // Apply body rotation to group
    group.rotation.y = Math.PI - bodyYaw * (Math.PI / 180);

    // Apply head rotation (relative yaw from body + pitch)
    const head = group.getObjectByName('head');
    if (head) {
      let headRelYaw = yaw - bodyYaw;
      headRelYaw = ((headRelYaw % 360) + 540) % 360 - 180;
      head.rotation.y = -headRelYaw * (Math.PI / 180);
      head.rotation.x = -pitch * (Math.PI / 180);
    }

    if (speed > 0.5) {
      const swing = Math.sin(currentTimeMs * 8 / 1000) * (Math.PI / 4) * Math.min(speed / 6, 1);
      if (rightLeg) rightLeg.rotation.x = swing;
      if (leftLeg) leftLeg.rotation.x = -swing;

      // Counter-swing arms when walking (unless attack swing is active)
      if (!isSwinging) {
        if (rightArm) rightArm.rotation.x = -swing;
        if (leftArm) leftArm.rotation.x = swing;
      }
    } else {
      if (rightLeg) rightLeg.rotation.x = 0;
      if (leftLeg) leftLeg.rotation.x = 0;
    }

    // Arm swing animation (attack) - positive rotation.x swings arm forward with corrected yaw
    if (isSwinging) {
      const t = swingElapsed / 300;
      const swingAngle = Math.sin(t * Math.PI) * (Math.PI / 2);
      if (rightArm) rightArm.rotation.x = swingAngle;
      if (leftArm) leftArm.rotation.x = 0;
    } else if (speed <= 0.5) {
      if (rightArm) rightArm.rotation.x = 0;
      if (leftArm) leftArm.rotation.x = 0;
    }

    // Held item mesh
    const itemName = heldItem ?? '';
    const prevItem = this.playerHeldItems.get(uuid);
    if (itemName !== prevItem) {
      this.playerHeldItems.set(uuid, itemName);
      if (rightArm) {
        // Remove old item mesh
        const oldItem = rightArm.getObjectByName('heldItemMesh');
        if (oldItem) rightArm.remove(oldItem);

        if (itemName && itemName !== 'air') {
          const itemMesh = this.createHeldItemMesh(itemName);
          itemMesh.name = 'heldItemMesh';
          this.positionHeldItem(itemMesh, itemName);
          rightArm.add(itemMesh);
        }
      }
    }

    // Armor overlays
    if (equipment) {
      const getEquipName = (slot: number): string => {
        const val = equipment.get(slot);
        if (val === undefined) return '';
        return typeof val === 'string' ? val : val.itemName;
      };
      const armorKey = Array.from(equipment.entries()).sort((a, b) => a[0] - b[0]).map(([k, v]) => `${k}:${typeof v === 'string' ? v : v.itemName}`).join(',');
      const prevArmorKey = this.playerArmorState.get(uuid);
      if (armorKey !== prevArmorKey) {
        this.playerArmorState.set(uuid, armorKey);
        // Remove old armor overlays
        const oldArmors: THREE.Object3D[] = [];
        group.traverse((child) => { if (child.name.startsWith('armor_')) oldArmors.push(child); });
        for (const a of oldArmors) a.parent?.remove(a);

        // Slot 2=HEAD, 3=CHEST, 4=LEGS, 5=FEET
        // Armor renders as slightly-larger overlay boxes covering the body part (like MC)
        const headItem = getEquipName(2);
        const chestItem = getEquipName(3);
        const legsItem = getEquipName(4);
        const feetItem = getEquipName(5);

        if (headItem && headItem !== 'air') {
          const armorHead = this.createArmorMesh(headItem, 0.56, 0.56, 0.56, 'head');
          armorHead.name = 'armor_head';
          armorHead.position.y = 0.25;
          const headGroup = group.getObjectByName('head');
          if (headGroup) headGroup.add(armorHead);
        }

        if (chestItem && chestItem !== 'air') {
          // Torso overlay
          const armorChest = this.createArmorMesh(chestItem, 0.56, 0.8, 0.3, 'body');
          armorChest.name = 'armor_chest';
          armorChest.position.y = 0.375;
          const bp = group.getObjectByName('bodyPivot');
          if (bp) bp.add(armorChest);
          // Arm overlays (left arm is mirrored)
          for (const armName of ['rightArm', 'leftArm']) {
            const armPivot = group.getObjectByName(armName);
            if (armPivot) {
              const armorArm = this.createArmorMesh(chestItem, 0.3, 0.8, 0.3, 'arm', armName === 'leftArm');
              armorArm.name = `armor_${armName}`;
              armorArm.position.y = -0.375;
              armPivot.add(armorArm);
            }
          }
        }

        if (legsItem && legsItem !== 'air') {
          for (const legName of ['rightLeg', 'leftLeg']) {
            const legPivot = group.getObjectByName(legName);
            if (legPivot) {
              const armorLeg = this.createArmorMesh(legsItem, 0.3, 0.8, 0.3, 'leg', legName === 'leftLeg');
              armorLeg.name = `armor_${legName}`;
              armorLeg.position.y = -0.375;
              legPivot.add(armorLeg);
            }
          }
        }

        if (feetItem && feetItem !== 'air') {
          for (const legName of ['rightLeg', 'leftLeg']) {
            const legPivot = group.getObjectByName(legName);
            if (legPivot) {
              const armorBoot = this.createArmorMesh(feetItem, 0.3, 0.5, 0.35, 'leg', legName === 'leftLeg');
              armorBoot.name = `armor_boot_${legName}`;
              armorBoot.position.set(0, -0.5, 0.025);
              legPivot.add(armorBoot);
            }
          }
        }

        // Offhand item (slot 1)
        const offhandItem = getEquipName(1);
        const leftArmGroup = group.getObjectByName('leftArm');
        if (leftArmGroup) {
          const oldOffhand = leftArmGroup.getObjectByName('offhandItemMesh');
          if (oldOffhand) leftArmGroup.remove(oldOffhand);

          if (offhandItem && offhandItem !== 'air') {
            const offhandMesh = this.createHeldItemMesh(offhandItem);
            offhandMesh.name = 'offhandItemMesh';
            this.positionHeldItem(offhandMesh, offhandItem);
            leftArmGroup.add(offhandMesh);
          }
        }
      }
    }

    // Damage red tint: flash red for 400ms after taking damage
    const DAMAGE_FLASH_MS = 400;
    const damageElapsed = currentTimeMs - lastDamageTime;
    const isDamaged = lastDamageTime > 0 && damageElapsed >= 0 && damageElapsed < DAMAGE_FLASH_MS;
    const damageIntensity = isDamaged ? 1 - (damageElapsed / DAMAGE_FLASH_MS) : 0;
    group.traverse((child) => {
      if (child instanceof THREE.Mesh && child.material instanceof THREE.MeshLambertMaterial) {
        child.material.emissive.setRGB(damageIntensity, 0, 0);
      }
    });
  }

  private positionHeldItem(itemMesh: THREE.Mesh, itemName: string): void {
    const isBlock = (this.itemMapping.get(itemName)?.isBlock ?? false) || this.blockNameToState.has(itemName);
    if (isBlock) {
      itemMesh.position.set(0, -0.75, -0.2);
    } else {
      itemMesh.position.set(0, -0.55, -0.35);
      itemMesh.rotation.set(0, Math.PI / 2, -Math.PI / 4);
    }
  }

  /**
   * Remove a player from the scene.
   */
  removePlayer(uuid: string): void {
    const group = this.playerMeshes.get(uuid);
    if (group) {
      this.scene.remove(group);
      this.playerMeshes.delete(uuid);
    }
    this.playerPrevPositions.delete(uuid);
    this.playerHeldItems.delete(uuid);
    this.playerBodyYaw.delete(uuid);
    this.playerSkinIds.delete(uuid);
  }

  /**
   * Reset all cached player state (armor, held items, body yaw, etc.).
   * Call this before a seek so stale state doesn't persist across time jumps.
   */
  resetPlayerState(): void {
    // Remove all player meshes from scene
    this.playerMeshes.forEach((group) => this.scene.remove(group));
    this.playerMeshes.clear();
    this.playerPrevPositions.clear();
    this.playerHeldItems.clear();
    this.playerArmorState.clear();
    this.playerBodyYaw.clear();
    this.playerNametagHealth.clear();
    this.playerSkinIds.clear();
    // Remove all entity meshes from scene
    this.entityMeshes.forEach((group) => this.scene.remove(group));
    this.entityMeshes.clear();
  }

  /**
   * Create a small mesh for a held item.
   * Block items use a textured cube from the atlas; non-block items use a textured flat quad.
   * Unknown items fall back to a colored flat quad.
   */
  /**
   * Create a textured block mesh directly from block face data (for falling_block entities).
   */
  private static createBlockMeshFromFaces(blockFaces: BlockFaces, atlas: BlockAtlas): THREE.Mesh {
    const size = 0.35;
    const geo = new THREE.BoxGeometry(size, size, size);
    const uvAttr = geo.getAttribute('uv');
    const faceTexNames = [
      blockFaces.side, blockFaces.side,
      blockFaces.top, blockFaces.bottom,
      blockFaces.side, blockFaces.side,
    ];
    for (const [fi, texName] of faceTexNames.entries()) {
      const texUV = atlas.mapping.textures[texName];
      if (!texUV) continue;
      const base = fi * 4;
      uvAttr.setXY(base + 0, texUV.u, texUV.v);
      uvAttr.setXY(base + 1, texUV.u + texUV.su, texUV.v);
      uvAttr.setXY(base + 2, texUV.u, texUV.v + texUV.sv);
      uvAttr.setXY(base + 3, texUV.u + texUV.su, texUV.v + texUV.sv);
    }
    uvAttr.needsUpdate = true;
    return new THREE.Mesh(geo, new THREE.MeshLambertMaterial({ map: atlas.texture, alphaTest: 0.1 }));
  }

  private static createItemPlaneMesh(texUV: AtlasUV, atlas: BlockAtlas): THREE.Mesh {
    const geo = new THREE.PlaneGeometry(0.85, 0.85);
    setPlaneUVs(geo, texUV);
    const mat = new THREE.MeshLambertMaterial({
      map: atlas.texture,
      alphaTest: 0.1,
      side: THREE.DoubleSide,
    });
    return new THREE.Mesh(geo, mat);
  }

  private static createPlaceholderItemMesh(itemName: string): THREE.Mesh {
    const geo = new THREE.PlaneGeometry(0.85, 0.85);
    let hash = 0;
    for (let i = 0; i < itemName.length; i++) {
      hash = ((hash << 5) - hash + itemName.charCodeAt(i)) | 0;
    }
    const hue = (Math.abs(hash) % 360) / 360;
    const color = new THREE.Color().setHSL(hue, 0.7, 0.5);
    const mat = new THREE.MeshLambertMaterial({ color, side: THREE.DoubleSide });
    return new THREE.Mesh(geo, mat);
  }

  private static fallbackItemTexture(itemName: string, atlas: BlockAtlas): AtlasUV | undefined {
    const fallback = ITEM_TEXTURE_FALLBACKS[itemName];
    return fallback === undefined ? undefined : atlas.mapping.textures[fallback];
  }

  private createHeldItemMesh(itemName: string): THREE.Mesh {
    const atlas = this.atlas;
    const atlasMesh = atlas ? this.createAtlasItemMesh(itemName, this.itemMapping.get(itemName), atlas) : null;
    return atlasMesh ?? PrismarineRenderer.createPlaceholderItemMesh(itemName);
  }

  private createAtlasItemMesh(itemName: string, itemInfo: AtlasItem | undefined, atlas: BlockAtlas): THREE.Mesh | null {
    const stateId = this.blockNameToState.get(itemName);
    const blockFaces = stateId === undefined ? undefined : atlas.mapping.blocks[stateId];
    if (itemInfo?.isBlock && blockFaces) {
      return PrismarineRenderer.createBlockMeshFromFaces(blockFaces, atlas);
    }
    const itemTexture = itemInfo?.texture ? atlas.mapping.textures[itemInfo.texture] : undefined;
    if (itemTexture) {
      return PrismarineRenderer.createItemPlaneMesh(itemTexture, atlas);
    }
    if (blockFaces) {
      return PrismarineRenderer.createBlockMeshFromFaces(blockFaces, atlas);
    }
    const fallbackTexture = PrismarineRenderer.fallbackItemTexture(itemName, atlas);
    return fallbackTexture ? PrismarineRenderer.createItemPlaneMesh(fallbackTexture, atlas) : null;
  }

  /**
   * Register embedded skin PNG data from the replay file.
   * Must be called before updatePlayer so the skin is available when the player mesh is created.
   */
  setEmbeddedSkin(uuid: string, skinPng: Uint8Array<ArrayBuffer>): void {
    this.embeddedSkins.set(uuid, skinPng);
  }

  /**
   * Preload all embedded skins as textures and apply to existing player meshes.
   * Returns a Promise that resolves when all skins are loaded.
   */
  async preloadEmbeddedSkins(): Promise<void> {
    const promises: Promise<void>[] = [];
    for (const [uuid] of this.embeddedSkins) {
      if (!this.skinTextures.has(uuid) || this.skinTextures.get(uuid) === null) {
        this.skinTextures.set(uuid, null);
        promises.push(
          this.loadSkinTexture(uuid).then(tex => {
            if (tex) {
              this.skinTextures.set(uuid, tex);
              this.applySkinToPlayersUsing(uuid, tex);
            }
          })
        );
      }
    }
    await Promise.all(promises);
  }

  getCamera(): THREE.PerspectiveCamera {
    return this.camera;
  }

  getScene(): THREE.Scene {
    return this.scene;
  }

  getDomElement(): HTMLCanvasElement | null {
    return this.renderer?.domElement ?? null;
  }

  updateSize(): void {
    if (!this.container || !this.renderer) return;
    const { clientWidth, clientHeight } = this.container;
    this.renderer.setSize(clientWidth, clientHeight);
    this.camera.aspect = clientWidth / clientHeight;
    this.camera.updateProjectionMatrix();
  }

  dispose(): void {
    if (this.animationFrameId !== null) {
      cancelAnimationFrame(this.animationFrameId);
    }
    this.renderer?.dispose();
    this.renderer?.domElement.remove();
    this.atlas?.texture.dispose();
    this.atlas?.opaqueMaterial.dispose();
    this.atlas?.transparentMaterial.dispose();
    for (const tex of this.armorTextureCache.values()) {
      tex.dispose();
    }
    this.armorTextureCache.clear();
    for (const tex of this.entityTextureCache.values()) {
      tex.dispose();
    }
    this.entityTextureCache.clear();
    for (const tex of this.skinTextures.values()) {
      tex?.dispose();
    }
    this.skinTextures.clear();
    this.playerPrevPositions.clear();
    this.playerHeldItems.clear();
    this.playerArmorState.clear();
    this.playerBodyYaw.clear();
    this.playerNametagHealth.clear();
    this.playerSkinIds.clear();
    this.entityMeshes.clear();
    this.entityTypeNames.clear();
    for (const exp of this.activeExplosions) {
      this.disposeExplosion(exp);
    }
    this.activeExplosions.length = 0;
    this.scene.clear();
    this.sections.clear();
    this.blockMap.clear();
    this.initialized = false;
  }

  // --- Private methods ---

  private setupBasicScene(): void {
    const ambient = new THREE.AmbientLight(0xffffff, 0.6);
    this.scene.add(ambient);

    const sun = new THREE.DirectionalLight(0xffffff, 0.8);
    sun.position.set(50, 100, 50);
    this.scene.add(sun);
  }

  // =========================================================================
  // Atlas-based textured rendering (section meshes)
  // =========================================================================

  private static sectionKey(x: number, y: number, z: number): SectionKey {
    return `${x >> 4},${y >> 4},${z >> 4}`;
  }

  private static addSection(sections: Map<SectionKey, SectionCoords>, x: number, y: number, z: number): void {
    const key = PrismarineRenderer.sectionKey(x, y, z);
    if (!sections.has(key)) {
      sections.set(key, { x: x >> 4, y: y >> 4, z: z >> 4 });
    }
  }

  private async loadBlocksTextured(blocks: BlockSnapshot[], atlas: BlockAtlas): Promise<void> {
    if (blocks.length === 0) return;

    const sectionsToBuild = new Map<SectionKey, SectionCoords>();
    for (const block of blocks) {
      this.blockMap.set(`${block.x},${block.y},${block.z}`, block.stateId);
      PrismarineRenderer.addSection(sectionsToBuild, block.x, block.y, block.z);
    }

    const sections = Array.from(sectionsToBuild);
    const BATCH_SIZE = 8;
    for (let i = 0; i < sections.length; i += BATCH_SIZE) {
      for (const [key, coords] of sections.slice(i, i + BATCH_SIZE)) {
        this.buildSectionMesh(key, coords, atlas);
      }
      if (i + BATCH_SIZE < sections.length) {
        await new Promise<void>(r => setTimeout(r, 0));
      }
    }
  }

  private updateBlockTextured(change: BlockChange): void {
    const posKey = `${change.x},${change.y},${change.z}`;

    if (change.stateId === 0) {
      this.blockMap.delete(posKey);
    } else {
      this.blockMap.set(posKey, change.stateId);
    }

    const bx = change.x, by = change.y, bz = change.z;
    const dirty = this.dirtySections;
    PrismarineRenderer.addSection(dirty, bx, by, bz);
    if ((bx & 15) === 0) PrismarineRenderer.addSection(dirty, bx - 1, by, bz);
    if ((bx & 15) === 15) PrismarineRenderer.addSection(dirty, bx + 1, by, bz);
    if ((by & 15) === 0) PrismarineRenderer.addSection(dirty, bx, by - 1, bz);
    if ((by & 15) === 15) PrismarineRenderer.addSection(dirty, bx, by + 1, bz);
    if ((bz & 15) === 0) PrismarineRenderer.addSection(dirty, bx, by, bz - 1);
    if ((bz & 15) === 15) PrismarineRenderer.addSection(dirty, bx, by, bz + 1);
  }

  private rebuildDirtySections(atlas: BlockAtlas): void {
    for (const [key, coords] of this.dirtySections) {
      this.buildSectionMesh(key, coords, atlas);
    }
    this.dirtySections.clear();
  }

  private removeSection(key: SectionKey): void {
    const oldPair = this.sections.get(key);
    if (!oldPair) return;
    if (oldPair.opaque) { this.scene.remove(oldPair.opaque); oldPair.opaque.geometry.dispose(); }
    if (oldPair.transparent) { this.scene.remove(oldPair.transparent); oldPair.transparent.geometry.dispose(); }
    this.sections.delete(key);
  }

  private buildSectionMesh(key: SectionKey, section: SectionCoords, atlas: BlockAtlas): void {
    this.removeSection(key);

    const baseX = section.x * 16;
    const baseY = section.y * 16;
    const baseZ = section.z * 16;
    const opaque = new SectionMeshBuilder();
    const transparent = new SectionMeshBuilder();

    for (let lx = 0; lx < 16; lx++) {
      for (let ly = 0; ly < 16; ly++) {
        for (let lz = 0; lz < 16; lz++) {
          this.addBlockGeometry(baseX + lx, baseY + ly, baseZ + lz, atlas.mapping, opaque, transparent);
        }
      }
    }

    const opaqueMesh = opaque.build(atlas.opaqueMaterial);
    const transparentMesh = transparent.build(atlas.transparentMaterial);
    if (opaqueMesh) this.scene.add(opaqueMesh);
    if (transparentMesh) this.scene.add(transparentMesh);

    if (opaqueMesh || transparentMesh) {
      this.sections.set(key, { opaque: opaqueMesh, transparent: transparentMesh });
    }
  }

  private addBlockGeometry(
    wx: number, wy: number, wz: number,
    mapping: AtlasMapping,
    opaque: SectionMeshBuilder,
    transparent: SectionMeshBuilder,
  ): void {
    const stateId = this.blockMap.get(`${wx},${wy},${wz}`);
    if (stateId === undefined || stateId === 0 || this.hiddenStates.has(stateId)) return;

    const blockFaces = mapping.blocks[stateId];
    if (!blockFaces) return;

    if (PrismarineRenderer.addShapedBlock(blockFaces, wx, wy, wz, mapping, transparent)) return;

    const isTransparent = this.transparentStates.has(stateId);
    const target = isTransparent ? transparent : opaque;
    const waterHeight = this.waterLevelMap.get(stateId);

    for (const face of CUBE_FACES) {
      const [dx, dy, dz] = face.normal;
      if (this.isFaceCulled(stateId, isTransparent, wx + dx, wy + dy, wz + dz)) continue;
      const texUV = mapping.textures[blockFaces[face.texture]];
      if (!texUV) continue;
      target.addQuad(face, wx, wy, wz, texUV, waterHeight);
    }
  }

  private static addShapedBlock(
    blockFaces: BlockFaces,
    wx: number, wy: number, wz: number,
    mapping: AtlasMapping,
    target: SectionMeshBuilder,
  ): boolean {
    switch (blockFaces.shape) {
      case 'multiface': {
        const faceMask = blockFaces.faceMask;
        if (faceMask === undefined) return false;
        const texUV = mapping.textures[blockFaces.side];
        if (!texUV) return true;
        for (const face of MULTIFACE_FACES) {
          if ((faceMask & face.mask) === 0) continue;
          for (const quad of face.quads) {
            target.addQuad(quad, wx, wy, wz, texUV);
          }
        }
        return true;
      }
      case 'cross':
        PrismarineRenderer.addQuads(CROSS_QUADS, wx, wy, wz, mapping.textures[blockFaces.side], target);
        return true;
      case 'floor':
        PrismarineRenderer.addQuads(FLOOR_QUADS, wx, wy, wz, mapping.textures[blockFaces.top] ?? mapping.textures[blockFaces.side], target);
        return true;
      case undefined:
        return false;
    }
  }

  private static addQuads(
    quads: readonly QuadModel[],
    wx: number, wy: number, wz: number,
    texUV: AtlasUV | undefined,
    target: SectionMeshBuilder,
  ): void {
    if (!texUV) return;
    for (const quad of quads) {
      target.addQuad(quad, wx, wy, wz, texUV);
    }
  }

  private isFaceCulled(stateId: number, isTransparent: boolean, nx: number, ny: number, nz: number): boolean {
    const neighborState = this.blockMap.get(`${nx},${ny},${nz}`);
    if (neighborState === undefined || neighborState === 0 || this.hiddenStates.has(neighborState)) return false;
    if (!this.transparentStates.has(neighborState)) return true;
    if (!isTransparent) return false;
    const curBlock = this.stateToBlockId.get(stateId);
    if (curBlock === undefined || curBlock !== this.stateToBlockId.get(neighborState)) return false;
    const curWater = this.waterLevelMap.get(stateId);
    return curWater === undefined || curWater === this.waterLevelMap.get(neighborState);
  }

  // =========================================================================
  // Player skin loading
  // =========================================================================

  private async loadSkinTexture(uuid: string): Promise<THREE.Texture | null> {
    const cleanUuid = uuid.replace(/-/g, '');
    const embeddedPng = this.embeddedSkins.get(uuid) ?? this.embeddedSkins.get(cleanUuid);
    const embeddedImage = embeddedPng && embeddedPng.length > 0
      ? await loadImageFromBytes(embeddedPng, 'image/png').catch(() => null)
      : null;
    const image = embeddedImage ?? await loadImage(`https://mineskin.eu/skin/${cleanUuid}`, 'anonymous').catch(() => null);
    return image ? PrismarineRenderer.createSkinTexture(image) : null;
  }

  private static createSkinTexture(image: HTMLImageElement): THREE.Texture {
    const tex = new THREE.Texture(image);
    tex.magFilter = THREE.NearestFilter;
    tex.minFilter = THREE.NearestFilter;
    tex.colorSpace = THREE.SRGBColorSpace;
    tex.needsUpdate = true;
    return tex;
  }

  /**
   * Set UV coordinates on a BoxGeometry for a specific face from the skin texture.
   * faceCoords: [x, y, w, h] in pixel coordinates on a 64×64 skin.
   * Three.js BoxGeometry face order: +x(right), -x(left), +y(top), -y(bottom), +z(front), -z(back)
   * Each face has 4 UV pairs in the uv attribute.
   */
  private static setSkinUVs(
    geometry: THREE.BoxGeometry,
    faces: BoxFaceRegions,
  ): void {
    const uvAttr = geometry.getAttribute('uv');
    const W = 64, H = 64;

    // Three.js BoxGeometry face order: +x, -x, +y, -y, +z, -z
    // Each face: 4 vertices × 2 uv components = 8 values
    // Model faces -Z locally, then PI rotation around Y flips it for world orientation.
    // After PI rotation: +Z face = world back, -Z face = world front.
    // Swap front↔back so the skin face texture appears on the correct side.
    for (const [fi, faceName] of BOX_FACE_ORDER.entries()) {
      const [px, py, pw, ph] = faces[faceName];
      const u0 = px / W, v0 = py / H;
      const u1 = (px + pw) / W, v1 = (py + ph) / H;

      const base = fi * 4;
      // BoxGeometry UV layout per face: (u0,v0), (u1,v0), (u0,v1), (u1,v1)
      uvAttr.setXY(base + 0, u0, 1 - v0);
      uvAttr.setXY(base + 1, u1, 1 - v0);
      uvAttr.setXY(base + 2, u0, 1 - v1);
      uvAttr.setXY(base + 3, u1, 1 - v1);
    }
    uvAttr.needsUpdate = true;
  }

  private applySkinToPlayersUsing(skinId: string, texture: THREE.Texture): void {
    for (const [playerUuid, group] of this.playerMeshes) {
      if (this.playerSkinIds.get(playerUuid) === skinId) {
        this.applySkinToGroup(group, texture);
      }
    }
  }

  private applySkinToGroup(group: THREE.Group, texture: THREE.Texture): void {
    if (this.skinnedGroups.has(group)) return;
    this.skinnedGroups.add(group);

    const skinMat = new THREE.MeshLambertMaterial({
      map: texture,
      alphaTest: 0.1,
    });

    group.traverse((child) => {
      if (!(child instanceof THREE.Mesh) || child.name) return;
      const skinPart = SKIN_PART_BY_PIVOT[child.parent?.name ?? ''];
      if (skinPart === undefined) return;
      if (child.geometry instanceof THREE.BoxGeometry) {
        PrismarineRenderer.setSkinUVs(child.geometry, SKIN_PARTS[skinPart]);
        child.material = skinMat;
      }
    });
  }

  // =========================================================================
  // Fallback: InstancedMesh colored cubes (no atlas)
  // =========================================================================

  private instancedMesh: THREE.InstancedMesh | null = null;
  private readonly blockIndexMap = new Map<string, number>();
  private instanceCount = 0;
  private maxInstances = 0;
  private readonly hiddenScale = new THREE.Matrix4().makeScale(0, 0, 0);
  private readonly scratchMatrix = new THREE.Matrix4();
  private readonly scratchColor = new THREE.Color();

  private static blockColor(stateId: number, target: THREE.Color): THREE.Color {
    const hue = (stateId * 137) % 360;
    return target.setHSL(hue / 360, 0.6, 0.5);
  }

  private loadBlocksBasic(blocks: BlockSnapshot[]): void {
    if (blocks.length === 0) return;

    this.maxInstances = blocks.length + 1024;
    const geometry = new THREE.BoxGeometry(1, 1, 1);
    const material = new THREE.MeshLambertMaterial({ vertexColors: false });

    const mesh = new THREE.InstancedMesh(geometry, material, this.maxInstances);
    mesh.count = blocks.length;
    this.instanceCount = blocks.length;

    const matrix = this.scratchMatrix;
    const color = this.scratchColor;

    for (const [i, block] of blocks.entries()) {
      matrix.identity();
      matrix.setPosition(block.x + 0.5, block.y + 0.5, block.z + 0.5);
      mesh.setMatrixAt(i, matrix);
      mesh.setColorAt(i, PrismarineRenderer.blockColor(block.stateId, color));

      this.blockIndexMap.set(`${block.x},${block.y},${block.z}`, i);
    }

    mesh.instanceMatrix.needsUpdate = true;
    if (mesh.instanceColor) mesh.instanceColor.needsUpdate = true;

    this.scene.add(mesh);
    this.instancedMesh = mesh;
  }

  private updateBlockBasic(change: BlockChange): void {
    if (!this.instancedMesh) return;
    const key = `${change.x},${change.y},${change.z}`;
    const existingIdx = this.blockIndexMap.get(key);

    if (change.stateId === 0) {
      if (existingIdx !== undefined) {
        this.instancedMesh.setMatrixAt(existingIdx, this.hiddenScale);
        this.instancedMesh.instanceMatrix.needsUpdate = true;
        this.blockIndexMap.delete(key);
      }
    } else if (existingIdx !== undefined) {
      this.instancedMesh.setColorAt(existingIdx, PrismarineRenderer.blockColor(change.stateId, this.scratchColor));
      if (this.instancedMesh.instanceColor) this.instancedMesh.instanceColor.needsUpdate = true;
    } else if (this.instanceCount < this.maxInstances) {
      const idx = this.instanceCount++;
      this.instancedMesh.count = this.instanceCount;

      const matrix = this.scratchMatrix.identity().setPosition(change.x + 0.5, change.y + 0.5, change.z + 0.5);
      this.instancedMesh.setMatrixAt(idx, matrix);
      this.instancedMesh.instanceMatrix.needsUpdate = true;

      this.instancedMesh.setColorAt(idx, PrismarineRenderer.blockColor(change.stateId, this.scratchColor));
      if (this.instancedMesh.instanceColor) this.instancedMesh.instanceColor.needsUpdate = true;

      this.blockIndexMap.set(key, idx);
    }
  }

  private startRenderLoop(): void {
    const render = () => {
      this.animationFrameId = requestAnimationFrame(render);
      this.updateExplosions();
      this.updateEntityAnimations();
      this.renderer?.render(this.scene, this.camera);
    };
    render();
  }

  /**
   * Update per-frame entity animations (e.g. dropped item rotation).
   */
  private updateEntityAnimations(): void {
    const now = performance.now() / 1000;
    this.entityMeshes.forEach((group, entityId) => {
      const typeName = this.entityTypeNames.get(entityId);
      if ((typeName?.startsWith('item')) || typeName === 'dropped_item') {
        // Slow Y-axis rotation for dropped items
        const innerGroup = group.children[0];
        if (innerGroup && innerGroup.type !== 'Sprite') {
          innerGroup.rotation.y = now * 1.5;
        }
      }
    });
  }
}
