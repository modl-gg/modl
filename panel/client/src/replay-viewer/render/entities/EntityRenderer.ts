import { Group, Mesh, MeshBasicMaterial, NoColorSpace, type Object3D, PlaneGeometry, type RawShaderMaterial, Texture } from 'three';
import type { LoadedAssets } from '../../assets/loadAssets.ts';
import { atLeast, parseVersion } from '../../format/MinecraftVersion.ts';
import type { EntityMetadataData, ItemStackData } from '../../format/ReplayModel.ts';
import { plainText } from '../../engine/ChatFormat.ts';
import type { EntityPose, EntityTrack, EquipmentState, ReplayTimeline } from '../../engine/ReplayTimeline.ts';
import { bareName } from '../../format/ids.ts';
import type { BlockLibrary } from '../world/BlockLibrary.ts';
import type { BlockState } from '../../format/BlockState.ts';
import type { SharedUniforms } from '../WorldMaterials.ts';
import { LEATHER_COLOR } from '../world/Tints.ts';
import { setEntityLight, setEntityOverlay } from './EntityMaterial.ts';
import {
  armorModel,
  armorStandModel,
  boatModel,
  chickenModel,
  cowModel,
  creeperModel,
  endermanModel,
  horseModel,
  legacyPlayerModel,
  minecartModel,
  pigModel,
  playerModel,
  sheepModel,
  sheepWoolModel,
  skeletonModel,
  spiderModel,
  villagerModel,
  zombieModel,
} from './EntityModels.ts';
import { type EntityTextures, type LoadedTexture, defaultSkin } from './EntityTextures.ts';
import {
  type ArmorLayer,
  BoxVisual,
  type EntityFrame,
  type EntityVisual,
  type MobOptions,
  ModelVisual,
  flagsOf,
  humanoidAnimator,
  quadrupedAnimator,
  spiderAnimator,
  staticAnimator,
  villagerAnimator,
} from './EntityVisuals.ts';
import { type AtlasView, ItemMeshFactory } from './ItemMeshes.ts';
import { NameTag } from './NameTag.ts';
import { decodeSkin } from '../../assets/Skins.ts';

export type LightProbe = (x: number, y: number, z: number) => number;

const NO_METADATA: EntityMetadataData = {};
const NO_EQUIPMENT: EquipmentState = {};
const REST_POSE: EntityPose = { x: 0, y: 0, z: 0, yaw: 0, pitch: 0, headYaw: 0, onGround: true, speed: 0, walkDistance: 0 };

class FrameScratch implements EntityFrame {
  time = 0;
  pose: EntityPose = REST_POSE;
  metadata: EntityMetadataData = NO_METADATA;
  equipment: EquipmentState = NO_EQUIPMENT;
  swing: number | null = null;
  offhandSwing: number | null = null;
  hurt: number | null = null;
  deathMs: number | null = null;
  sky = 15;
  block = 0;
  ageTicks = 0;
}

const DYE_COLORS = [0xf9fffe, 0xf9801d, 0xc74ebd, 0x3ab3da, 0xfed83d, 0x80c71f, 0xf38baa, 0x474f52, 0x9d9d97, 0x169c9c, 0x8932b8, 0x3c44aa, 0x835432, 0x5e7c16, 0xb02e26, 0x1d1d21];
const HORSE_COLORS = ['white', 'creamy', 'chestnut', 'brown', 'black', 'gray', 'darkbrown'];
const ARMOR_SLOTS = ['head', 'chest', 'legs', 'feet'] as const;

const SHADOW_RADIUS: ReadonlyMap<string, number> = new Map([
  ['player', 0.5], ['zombie', 0.5], ['husk', 0.5], ['drowned', 0.5], ['skeleton', 0.5], ['stray', 0.5], ['wither_skeleton', 0.7], ['creeper', 0.5], ['spider', 1], ['cave_spider', 0.7],
  ['enderman', 0.5], ['villager', 0.5], ['wandering_trader', 0.5], ['cow', 0.7], ['mooshroom', 0.7], ['pig', 0.7], ['sheep', 0.7], ['chicken', 0.3], ['horse', 0.75], ['donkey', 0.75],
  ['mule', 0.75], ['armor_stand', 0], ['item', 0.15], ['falling_block', 0.5], ['tnt', 0.5],
]);

function shadowMap(): Texture {
  const canvas = new OffscreenCanvas(32, 32);
  const context = canvas.getContext('2d');
  if (context !== null) {
    const gradient = context.createRadialGradient(16, 16, 0, 16, 16, 16);
    gradient.addColorStop(0, 'rgba(0, 0, 0, 0.55)');
    gradient.addColorStop(0.7, 'rgba(0, 0, 0, 0.45)');
    gradient.addColorStop(1, 'rgba(0, 0, 0, 0)');
    context.fillStyle = gradient;
    context.fillRect(0, 0, 32, 32);
  }
  const texture = new Texture(canvas);
  texture.colorSpace = NoColorSpace;
  texture.needsUpdate = true;
  return texture;
}

interface Instance {
  shadow: Mesh | null;
  visual: EntityVisual | null;
  readonly track: EntityTrack;
  nameTag: NameTag | null;
  customName: string | null;
  customText: string;
  armorKey: string;
  disposed: boolean;
}

function armorMaterial(item: string): string | null {
  const name = bareName(item);
  const match = /^(leather|chainmail|iron|golden|gold|diamond|netherite|copper|turtle)_(helmet|chestplate|leggings|boots)$/.exec(name);
  if (match === null) return name === 'turtle_helmet' ? 'turtle_scute' : null;
  const material = match[1] ?? '';
  return material === 'golden' ? 'gold' : material;
}

function armorTint(stack: ItemStackData): number {
  if (!bareName(stack.item).startsWith('leather_')) return 0xffffff;
  return stack.dyeColor ?? LEATHER_COLOR;
}

export class EntityRenderer {
  readonly group = new Group();
  private readonly assets: LoadedAssets;
  private readonly uniforms: SharedUniforms;
  private readonly timeline: ReplayTimeline;
  private readonly probe: LightProbe;
  private readonly textures: EntityTextures;
  private readonly instances = new Map<number, Instance>();
  private readonly seen = new Set<number>();
  private readonly frame = new FrameScratch();
  private readonly modernSkins: boolean;
  private readonly legacyModels: boolean;
  private items: ItemMeshFactory | null = null;
  private library: BlockLibrary | null = null;
  private palette: readonly BlockState[] = [];
  private hiddenEntity: number | null = null;
  private hiddenNameTag: number | null = null;
  private readonly shadowGeometry = new PlaneGeometry(1, 1).rotateX(-Math.PI / 2);
  private readonly shadowTexture = shadowMap();
  private readonly shadowMaterial = new MeshBasicMaterial({ map: this.shadowTexture, transparent: true, depthWrite: false, polygonOffset: true, polygonOffsetFactor: -2, polygonOffsetUnits: -2 });
  onChange: () => void = () => undefined;

  constructor(assets: LoadedAssets, textures: EntityTextures, uniforms: SharedUniforms, timeline: ReplayTimeline, probe: LightProbe) {
    this.assets = assets;
    this.uniforms = uniforms;
    this.timeline = timeline;
    this.probe = probe;
    this.textures = textures;
    const version = parseVersion(assets.version);
    this.modernSkins = atLeast(version, '1.19.3');
    this.legacyModels = !atLeast(version, '1.9');
  }

  setAtlas(atlas: AtlasView, library: BlockLibrary, palette: readonly BlockState[]): void {
    this.items?.dispose();
    this.items = new ItemMeshFactory(this.assets.pack, library, atlas, this.uniforms);
    this.library = library;
    this.palette = palette;
    for (const instance of this.instances.values()) this.discard(instance);
    this.instances.clear();
  }

  visual(entityId: number): EntityVisual | null {
    return this.instances.get(entityId)?.visual ?? null;
  }

  hide(entityId: number | null, nameTagOf: number | null): void {
    this.hiddenEntity = entityId;
    this.hiddenNameTag = nameTagOf;
  }

  update(time: number): void {
    const seen = this.seen;
    seen.clear();
    for (const track of this.timeline.entities.values()) {
      if (!track.present(time)) continue;
      const pose = track.pose(time);
      if (pose === null) continue;
      seen.add(track.id);
      let instance = this.instances.get(track.id);
      if (instance?.track !== track) {
        if (instance !== undefined) this.discard(instance);
        instance = { shadow: null, visual: null, track, nameTag: null, customName: null, customText: '', armorKey: '', disposed: false };
        this.instances.set(track.id, instance);
        this.build(instance);
      }
      const visual = instance.visual;
      if (visual === null) continue;
      const metadata = track.metadata.at(time) ?? NO_METADATA;
      const equipment = this.equipmentFor(track, time);
      const light = this.probe(pose.x, pose.y + 0.2, pose.z);
      const frame = this.frame;
      frame.time = time;
      frame.pose = pose;
      frame.metadata = metadata;
      frame.equipment = equipment;
      frame.swing = track.swingProgress(time, false);
      frame.offhandSwing = track.swingProgress(time, true);
      frame.hurt = track.hurtProgress(time);
      frame.deathMs = track.deathTime(time);
      frame.sky = light >> 4;
      frame.block = flagsOf(metadata).onFire ? 15 : light & 15;
      frame.ageTicks = time / 50;
      visual.update(frame);
      visual.root.visible = visual.root.visible && track.id !== this.hiddenEntity;
      if (visual instanceof ModelVisual) {
        visual.attachHeldItems(this.items, equipment);
        this.updateArmor(instance, visual, equipment, frame);
      }
      this.updateNameTag(instance, visual, track, time, metadata.customName ?? null, metadata.customNameVisible === true);
      this.updateShadow(instance, visual, track, pose.onGround && frame.deathMs === null);
    }
    for (const [id, instance] of this.instances) {
      if (seen.has(id)) continue;
      this.discard(instance);
      this.instances.delete(id);
    }
  }

  private equipmentFor(track: EntityTrack, time: number): EquipmentState {
    const recorded = track.equipment.at(time) ?? NO_EQUIPMENT;
    if (track.id !== this.timeline.targetEntityId) return recorded;
    const target = this.timeline.targetEquipment(time);
    const merged: Record<string, ItemStackData | null> = { ...recorded };
    for (const [slot, stack] of Object.entries(target)) {
      if (stack !== null) merged[slot] = stack;
    }
    return merged;
  }

  private updateShadow(instance: Instance, visual: EntityVisual, track: EntityTrack, grounded: boolean): void {
    const radius = SHADOW_RADIUS.get(bareName(track.type)) ?? 0.5;
    if (radius <= 0) return;
    if (instance.shadow === null) {
      instance.shadow = new Mesh(this.shadowGeometry, this.shadowMaterial);
      instance.shadow.scale.setScalar(radius * 2);
      instance.shadow.renderOrder = 3;
      this.group.add(instance.shadow);
    }
    instance.shadow.visible = grounded && visual.root.visible;
    instance.shadow.position.set(visual.root.position.x, Math.floor(visual.root.position.y + 0.01) + 0.015, visual.root.position.z);
  }

  private updateNameTag(instance: Instance, visual: EntityVisual, track: EntityTrack, time: number, customName: string | null, customVisible: boolean): void {
    let text: string | null = null;
    if (track.type === 'minecraft:player' && track.uuid !== null) text = this.timeline.playerInfo(track.uuid, time)?.name ?? null;
    else if (customName !== null && customVisible) text = this.customText(instance, customName);
    else if (visual instanceof BoxVisual) text = this.assets.lang(`entity.minecraft.${bareName(track.type)}`) ?? bareName(track.type);
    if (text === null || text === '' || !visual.root.visible || track.id === this.hiddenNameTag) {
      if (instance.nameTag !== null) instance.nameTag.sprite.visible = false;
      return;
    }
    if (instance.nameTag?.text !== text) {
      instance.nameTag?.sprite.removeFromParent();
      instance.nameTag?.dispose();
      instance.nameTag = new NameTag(this.assets.font(), text);
      this.group.add(instance.nameTag.sprite);
    }
    const sprite = instance.nameTag.sprite;
    sprite.visible = true;
    sprite.position.set(visual.root.position.x, visual.root.position.y + visual.height + 0.5, visual.root.position.z);
  }

  private customText(instance: Instance, customName: string): string {
    if (instance.customName !== customName) {
      instance.customName = customName;
      instance.customText = plainText(customName, (key) => this.assets.lang(key));
    }
    return instance.customText;
  }

  private updateArmor(instance: Instance, visual: ModelVisual, equipment: EquipmentState, frame: EntityFrame): void {
    if (!visual.wearsArmor) return;
    const pieces = ARMOR_SLOTS.map((slot) => equipment[slot] ?? null);
    const key = pieces.map((stack) => `${stack?.item ?? ''}:${String(stack?.dyeColor ?? '')}`).join('|');
    if (key !== instance.armorKey) {
      instance.armorKey = key;
      visual.setArmor([]);
      void this.loadArmor(pieces).then((layers) => {
        if (instance.disposed || instance.armorKey !== key) return;
        visual.setArmor(layers);
        this.onChange();
      });
    }
    visual.updateArmor(frame);
  }

  private async loadArmor(pieces: readonly (ItemStackData | null)[]): Promise<ArmorLayer[]> {
    const layers: ArmorLayer[] = [];
    const parts: readonly (readonly string[])[] = [['head'], ['body', 'right_arm', 'left_arm'], ['body', 'right_leg', 'left_leg'], ['right_leg', 'left_leg']];
    for (let index = 0; index < pieces.length; index++) {
      const stack = pieces[index];
      if (stack === null || stack === undefined) continue;
      const material = armorMaterial(stack.item);
      if (material === null) continue;
      const leggings = index === 2;
      const texture = await this.textures.first(leggings
        ? [`textures/entity/equipment/humanoid_leggings/${material}.png`, `textures/models/armor/${material}_layer_2.png`]
        : [`textures/entity/equipment/humanoid/${material}.png`, `textures/models/armor/${material}_layer_1.png`]);
      if (texture === null) continue;
      layers.push({ definition: armorModel(leggings ? 0.5 : 1), texture, tint: armorTint(stack), parts: parts[index] ?? [] });
      if (material === 'leather') {
        const overlay = await this.textures.first(leggings
          ? ['textures/entity/equipment/humanoid_leggings/leather_overlay.png', 'textures/models/armor/leather_layer_2_overlay.png']
          : ['textures/entity/equipment/humanoid/leather_overlay.png', 'textures/models/armor/leather_layer_1_overlay.png']);
        if (overlay !== null) layers.push({ definition: armorModel(leggings ? 0.5 : 1.01), texture: overlay, tint: 0xffffff, parts: parts[index] ?? [] });
      }
    }
    return layers;
  }

  private discard(instance: Instance): void {
    instance.disposed = true;
    instance.shadow?.removeFromParent();
    if (instance.visual !== null) {
      instance.visual.root.removeFromParent();
      instance.visual.dispose();
    }
    instance.nameTag?.sprite.removeFromParent();
    instance.nameTag?.dispose();
  }

  private build(instance: Instance): void {
    void this.createVisual(instance.track).then((visual) => {
      if (instance.disposed) {
        visual?.dispose();
        return;
      }
      instance.visual = visual ?? new BoxVisual(0.6, 1.8, 0xff00ff);
      this.group.add(instance.visual.root);
      this.onChange();
    });
  }

  private mob(options: Omit<MobOptions, 'texture'>, texture: LoadedTexture | null, expectedWidth: number, fallback: [number, number]): EntityVisual {
    if (texture?.width !== expectedWidth) return new BoxVisual(fallback[0], fallback[1], 0x8899aa);
    return new ModelVisual(this.uniforms, { ...options, texture });
  }

  private async playerVisual(track: EntityTrack): Promise<EntityVisual | null> {
    const info = track.uuid === null ? null : this.timeline.playerInfo(track.uuid, Infinity) ?? this.timeline.playerInfo(track.uuid, 0);
    const fallback = defaultSkin(track.uuid, this.modernSkins);
    let texture: LoadedTexture | null = null;
    let slim = fallback.slim;
    const png = info?.skinPng ?? null;
    const hash = info?.skinHash ?? null;
    if (png !== null) {
      texture = await this.textures.skin(`png:${track.uuid ?? ''}`, async () => decodeSkin(png), true);
      slim = false;
    } else if (hash !== null) {
      texture = await this.textures.skin(`hash:${hash}`, async () => this.assets.skin(hash).catch(() => null), false);
      slim = info?.slim ?? false;
    }
    if (texture === null) {
      texture = await this.textures.request(fallback.path);
      slim = fallback.slim;
    }
    if (texture === null) return new BoxVisual(0.6, 1.8, 0x4466cc);
    const definition = this.legacyModels || texture.height === 32 ? legacyPlayerModel() : playerModel(slim);
    return new ModelVisual(this.uniforms, { definition, texture, animator: humanoidAnimator(false), height: 1.8, wearsArmor: true });
  }

  private async createVisual(track: EntityTrack): Promise<EntityVisual | null> {
    const name = bareName(track.type);
    const textures = this.textures;
    const metadata = track.metadata.valueAt(0) ?? {};
    switch (name) {
      case 'player':
        return this.playerVisual(track);
      case 'zombie':
      case 'husk':
      case 'drowned': {
        const texture = await textures.first([`textures/entity/zombie/${name}.png`]);
        return this.mob({ definition: zombieModel(), animator: humanoidAnimator(true), height: 1.95, wearsArmor: true }, texture, 64, [0.6, 1.95]);
      }
      case 'skeleton':
      case 'stray':
      case 'wither_skeleton': {
        const texture = await textures.first([`textures/entity/skeleton/${name}.png`]);
        return this.mob({ definition: skeletonModel(), animator: humanoidAnimator(false), height: 1.99, scale: name === 'wither_skeleton' ? 1.2 : 1, wearsArmor: true }, texture, 64, [0.6, 1.99]);
      }
      case 'creeper': {
        const texture = await textures.first(['textures/entity/creeper/creeper.png']);
        return this.mob({ definition: creeperModel(), animator: quadrupedAnimator, height: 1.7 }, texture, 64, [0.6, 1.7]);
      }
      case 'spider':
      case 'cave_spider': {
        const texture = await textures.first([`textures/entity/spider/${name}.png`]);
        return this.mob({ definition: spiderModel(), animator: spiderAnimator, height: name === 'spider' ? 0.9 : 0.5, scale: name === 'spider' ? 1 : 0.7 }, texture, 64, [1.4, 0.9]);
      }
      case 'enderman': {
        const texture = await textures.first(['textures/entity/enderman/enderman.png']);
        const eyes = await textures.first(['textures/entity/enderman/enderman_eyes.png']);
        return this.mob({ definition: endermanModel(), animator: humanoidAnimator(false), height: 2.9, overlays: eyes === null ? [] : [{ definition: endermanModel(), texture: eyes, emissive: true }] }, texture, 64, [0.6, 2.9]);
      }
      case 'villager':
      case 'wandering_trader': {
        const texture = await textures.first(name === 'villager' ? ['textures/entity/villager/villager.png'] : ['textures/entity/wandering_trader.png']);
        const type = name === 'villager' ? await textures.first(['textures/entity/villager/type/plains.png']) : null;
        return this.mob({ definition: villagerModel(), animator: villagerAnimator, height: 1.95, overlays: type === null ? [] : [{ definition: villagerModel(), texture: type }] }, texture, 64, [0.6, 1.95]);
      }
      case 'cow':
      case 'mooshroom': {
        const texture = await textures.first(name === 'cow'
          ? ['textures/entity/cow/cow_temperate.png', 'textures/entity/cow/temperate_cow.png', 'textures/entity/cow/cow.png']
          : ['textures/entity/cow/mooshroom_red.png', 'textures/entity/cow/red_mooshroom.png', 'textures/entity/cow/mooshroom.png']);
        return this.mob({ definition: cowModel(), animator: quadrupedAnimator, height: 1.4, scale: metadata.baby === true ? 0.5 : 1 }, texture, 64, [0.9, 1.4]);
      }
      case 'pig': {
        const texture = await textures.first(['textures/entity/pig/pig_temperate.png', 'textures/entity/pig/temperate_pig.png', 'textures/entity/pig/pig.png']);
        return this.mob({ definition: pigModel(), animator: quadrupedAnimator, height: 0.9, scale: metadata.baby === true ? 0.5 : 1 }, texture, 64, [0.9, 0.9]);
      }
      case 'sheep': {
        const texture = await textures.first(['textures/entity/sheep/sheep.png']);
        const wool = await textures.first(['textures/entity/sheep/sheep_wool.png', 'textures/entity/sheep/sheep_fur.png']);
        const color = DYE_COLORS[(metadata.variant ?? 0) & 15] ?? 0xffffff;
        return this.mob({ definition: sheepModel(), animator: quadrupedAnimator, height: 1.3, overlays: wool === null ? [] : [{ definition: sheepWoolModel(), texture: wool, tint: color }] }, texture, 64, [0.9, 1.3]);
      }
      case 'chicken': {
        const texture = await textures.first(['textures/entity/chicken/chicken_temperate.png', 'textures/entity/chicken/temperate_chicken.png', 'textures/entity/chicken.png']);
        return this.mob({ definition: chickenModel(), animator: quadrupedAnimator, height: 0.7 }, texture, 64, [0.4, 0.7]);
      }
      case 'horse':
      case 'donkey':
      case 'mule':
      case 'skeleton_horse':
      case 'zombie_horse': {
        const variant = HORSE_COLORS[(metadata.variant ?? 0) & 255] ?? 'white';
        const path = name === 'horse' ? `textures/entity/horse/horse_${variant}.png` : `textures/entity/horse/${name === 'skeleton_horse' ? 'horse_skeleton' : name === 'zombie_horse' ? 'horse_zombie' : name}.png`;
        const texture = await textures.first([path]);
        if (texture?.height !== 64) return new BoxVisual(1.4, 1.6, 0x8b5a2b);
        return this.mob({ definition: horseModel(), animator: quadrupedAnimator, height: 1.6 }, texture, 64, [1.4, 1.6]);
      }
      case 'armor_stand': {
        const texture = await textures.first(['textures/entity/armorstand/wood.png']);
        return this.mob({ definition: armorStandModel(), animator: staticAnimator, height: 1.975, wearsArmor: true }, texture, 64, [0.5, 1.975]);
      }
      case 'minecart':
      case 'chest_minecart':
      case 'furnace_minecart':
      case 'hopper_minecart':
      case 'tnt_minecart':
      case 'command_block_minecart':
      case 'spawner_minecart': {
        const texture = await textures.first(['textures/entity/minecart.png', 'textures/entity/minecart/minecart.png']);
        return this.mob({ definition: minecartModel(), animator: staticAnimator, height: 0.7, rootOffset: -1.5 + 0.375, innerYaw: 90 }, texture, 64, [0.98, 0.7]);
      }
      case 'item':
        return this.itemVisual(track);
      case 'falling_block':
      case 'tnt':
        return this.blockVisual(name === 'tnt' ? { name: 'tnt', properties: {} } : this.palette[metadata.blockState ?? 0] ?? null, name === 'tnt');
      default:
        break;
    }
    if (name.endsWith('_boat') || name.endsWith('_raft') || name === 'boat') {
      const wood = name === 'boat' ? 'oak' : name.replace(/_(chest_)?(boat|raft)$/, '');
      const texture = await textures.first([`textures/entity/boat/${wood}.png`]);
      return this.mob({ definition: boatModel(), animator: staticAnimator, height: 0.5625, rootOffset: -1.5 + 0.375, innerYaw: -90 }, texture, 128, [1.375, 0.5625]);
    }
    if (name === 'arrow' || name === 'spectral_arrow' || name === 'trident') return new BoxVisual(0.15, 0.15, 0xcccccc);
    if (name === 'experience_orb') return new BoxVisual(0.25, 0.25, 0x9cff3a);
    return new BoxVisual(0.6, 0.6, 0xc0c0c0);
  }

  private itemVisual(track: EntityTrack): EntityVisual | null {
    const stack = track.metadata.at(Infinity)?.item ?? track.metadata.valueAt(0)?.item ?? null;
    const factory = this.items;
    if (stack === null || factory === null) return new BoxVisual(0.25, 0.25, 0xffffff);
    const mesh = factory.create(stack.item, 'ground');
    if (mesh === null) return new BoxVisual(0.25, 0.25, 0xffffff);
    return new ItemVisual(mesh.object, mesh.materials, track.id);
  }

  private blockVisual(state: BlockState | null, tnt: boolean): EntityVisual | null {
    const library = this.library;
    const factory = this.items;
    if (state === null || library === null || factory === null) return new BoxVisual(0.98, 0.98, 0xaaaaaa);
    const baked = library.bake(state);
    const quads = baked.parts.flatMap((options) => options[0]?.model.quads ?? []);
    if (quads.length === 0) return new BoxVisual(0.98, 0.98, 0xaaaaaa);
    const block = factory.blockMesh(quads);
    block.mesh.position.set(-0.5, 0, -0.5);
    return new BlockVisual(block.mesh, block.material, block.geometry, tnt);
  }

  dispose(): void {
    for (const instance of this.instances.values()) this.discard(instance);
    this.instances.clear();
    this.items?.dispose();
    this.shadowGeometry.dispose();
    this.shadowMaterial.dispose();
    this.shadowTexture.dispose();
  }
}

class ItemVisual implements EntityVisual {
  readonly root = new Group();
  readonly height = 0.25;
  private readonly spin: Object3D;
  private readonly materials: readonly RawShaderMaterial[];
  private readonly bobOffset: number;

  constructor(object: Object3D, materials: readonly RawShaderMaterial[], seed: number) {
    this.spin = new Group();
    this.spin.add(object);
    this.root.add(this.spin);
    this.materials = materials;
    this.bobOffset = (seed * 0.6180339) % 1 * Math.PI * 2;
  }

  update(frame: EntityFrame): void {
    const age = frame.ageTicks;
    const bob = Math.sin(age / 10 + this.bobOffset) * 0.1 + 0.1;
    this.root.position.set(frame.pose.x, frame.pose.y + bob + 0.25 * 0.5, frame.pose.z);
    this.spin.rotation.y = (age / 20 + this.bobOffset);
    for (const material of this.materials) setEntityLight(material, frame.sky, frame.block);
  }

  dispose(): void {
    for (const material of this.materials) material.dispose();
  }
}

class BlockVisual implements EntityVisual {
  readonly root = new Group();
  readonly height = 1;
  private readonly material: RawShaderMaterial;
  private readonly geometry: { dispose(): void };
  private readonly tnt: boolean;
  constructor(mesh: Object3D, material: RawShaderMaterial, geometry: { dispose(): void }, tnt: boolean) {
    this.root.add(mesh);
    this.material = material;
    this.geometry = geometry;
    this.tnt = tnt;
  }

  update(frame: EntityFrame): void {
    this.root.position.set(frame.pose.x, frame.pose.y, frame.pose.z);
    setEntityLight(this.material, frame.sky, frame.block);
    if (this.tnt) {
      const flash = Math.floor(frame.ageTicks / 5) % 2 === 0;
      setEntityOverlay(this.material, 1, 1, 1, flash ? 0.6 : 0);
    }
  }

  dispose(): void {
    this.material.dispose();
    this.geometry.dispose();
  }
}

