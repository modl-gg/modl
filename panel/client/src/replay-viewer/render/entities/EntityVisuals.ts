import { BoxGeometry, Group, Mesh, MeshBasicMaterial, type Object3D, type RawShaderMaterial } from 'three';
import type { EntityFlags, EntityMetadataData, ItemStackData, PoseName } from '../../format/ReplayModel.ts';
import type { EntityPose, EquipmentState } from '../../engine/ReplayTimeline.ts';
import { disposeGeometries } from '../disposeTree.ts';
import { createEntityMaterial, setEntityLight, setEntityOverlay, setEntityTint } from './EntityMaterial.ts';
import type { LoadedTexture } from './EntityTextures.ts';
import { type BuiltModel, type ModelDef, buildModel, javaRotate, resetPose } from './ModelParts.ts';
import type { ItemMesh, ItemMeshFactory } from './ItemMeshes.ts';
import type { SharedUniforms } from '../WorldMaterials.ts';

export interface EntityFrame {
  readonly time: number;
  readonly pose: EntityPose;
  readonly metadata: EntityMetadataData;
  readonly equipment: EquipmentState;
  readonly swing: number | null;
  readonly offhandSwing: number | null;
  readonly hurt: number | null;
  readonly deathMs: number | null;
  readonly sky: number;
  readonly block: number;
  readonly ageTicks: number;
}

export interface EntityVisual {
  readonly root: Group;
  readonly height: number;
  update(frame: EntityFrame): void;
  dispose(): void;
}

export type Animator = (model: BuiltModel, frame: EntityFrame, netHeadYaw: number) => void;

const DEG = Math.PI / 180;
const FLAGS_NONE: EntityFlags = { onFire: false, sneaking: false, sprinting: false, swimming: false, invisible: false, glowing: false, gliding: false };

export function flagsOf(metadata: EntityMetadataData): EntityFlags {
  return metadata.flags ?? FLAGS_NONE;
}

export function poseOf(metadata: EntityMetadataData): PoseName {
  const flags = flagsOf(metadata);
  if (metadata.pose !== undefined && metadata.pose !== 'standing') return metadata.pose;
  if (flags.gliding) return 'fall_flying';
  if (flags.swimming) return 'swimming';
  if (flags.sneaking) return 'crouching';
  return 'standing';
}

function holding(stack: ItemStackData | null | undefined): boolean {
  return stack !== undefined && stack !== null;
}

function limbSwing(frame: EntityFrame): [number, number] {
  const amount = Math.min(1, (frame.pose.speed / 20) * 4);
  return [frame.pose.walkDistance * 4, amount];
}

function headAngles(frame: EntityFrame, netHeadYaw: number): [number, number] {
  return [frame.pose.pitch * DEG, netHeadYaw * DEG];
}

export const humanoidAnimator = (zombieArms: boolean): Animator => (model, frame, netHeadYaw) => {
  resetPose(model);
  const [position, amount] = limbSwing(frame);
  const [headPitch, headYaw] = headAngles(frame, netHeadYaw);
  const head = model.parts.get('head');
  const body = model.parts.get('body');
  const rightArm = model.parts.get('right_arm');
  const leftArm = model.parts.get('left_arm');
  const rightLeg = model.parts.get('right_leg');
  const leftLeg = model.parts.get('left_leg');
  let rightArmX = Math.cos(position * 0.6662 + Math.PI) * 2 * amount * 0.5;
  let leftArmX = Math.cos(position * 0.6662) * 2 * amount * 0.5;
  let rightArmY = 0;
  let leftArmY = 0;
  let rightArmZ = 0;
  let bodyX = 0;
  let bodyY = 0;
  if (holding(frame.equipment.mainhand)) rightArmX = rightArmX * 0.5 - Math.PI / 10;
  if (holding(frame.equipment.offhand)) leftArmX = leftArmX * 0.5 - Math.PI / 10;
  if (zombieArms) {
    rightArmX = -Math.PI / 2 + Math.cos(position * 0.6662) * 0.05;
    leftArmX = -Math.PI / 2 - Math.cos(position * 0.6662) * 0.05;
  }
  const swing = frame.swing;
  if (swing !== null) {
    bodyY = Math.sin(Math.sqrt(swing) * Math.PI * 2) * 0.2;
    rightArmY += bodyY;
    leftArmY += bodyY;
    leftArmX += bodyY;
    let eased = 1 - swing;
    eased = eased * eased;
    eased = eased * eased;
    eased = 1 - eased;
    const lift = Math.sin(eased * Math.PI);
    const reach = Math.sin(swing * Math.PI) * -(headPitch - 0.7) * 0.75;
    rightArmX -= lift * 1.2 + reach;
    rightArmY += bodyY * 2;
    rightArmZ += Math.sin(swing * Math.PI) * -0.4;
  }
  const crouching = poseOf(frame.metadata) === 'crouching';
  if (crouching) {
    bodyX = 0.5;
    rightArmX += 0.4;
    leftArmX += 0.4;
    body?.position.setY(24 - 3.2);
    head?.position.setY(24 - 4.2);
    rightArm?.position.setY(24 - 5.2);
    leftArm?.position.setY(24 - 5.2);
    rightLeg?.position.set(rightLeg.position.x, 24 - 12.2, -4);
    leftLeg?.position.set(leftLeg.position.x, 24 - 12.2, -4);
  }
  javaRotate(head, headPitch, headYaw, 0);
  javaRotate(body, bodyX, bodyY, 0);
  javaRotate(rightArm, rightArmX, rightArmY, rightArmZ);
  javaRotate(leftArm, leftArmX, leftArmY, 0);
  javaRotate(rightLeg, Math.cos(position * 0.6662) * 1.4 * amount, 0, 0);
  javaRotate(leftLeg, Math.cos(position * 0.6662 + Math.PI) * 1.4 * amount, 0, 0);
};

export const quadrupedAnimator: Animator = (model, frame, netHeadYaw) => {
  resetPose(model);
  const [position, amount] = limbSwing(frame);
  const [headPitch, headYaw] = headAngles(frame, netHeadYaw);
  const head = model.parts.get('head') ?? model.parts.get('head_parts');
  if (head !== undefined) {
    const rest = model.rest.get(head.name);
    javaRotate(head, headPitch + (rest?.rotation[0] ?? 0), headYaw, 0);
  }
  javaRotate(model.parts.get('right_hind_leg'), Math.cos(position * 0.6662) * 1.4 * amount, 0, 0);
  javaRotate(model.parts.get('left_hind_leg'), Math.cos(position * 0.6662 + Math.PI) * 1.4 * amount, 0, 0);
  javaRotate(model.parts.get('right_front_leg'), Math.cos(position * 0.6662 + Math.PI) * 1.4 * amount, 0, 0);
  javaRotate(model.parts.get('left_front_leg'), Math.cos(position * 0.6662) * 1.4 * amount, 0, 0);
  const wing = frame.pose.onGround ? 0 : Math.sin(frame.ageTicks * 1.2) + 1;
  javaRotate(model.parts.get('right_wing'), 0, 0, wing);
  javaRotate(model.parts.get('left_wing'), 0, 0, -wing);
};

export const spiderAnimator: Animator = (model, frame, netHeadYaw) => {
  resetPose(model);
  const [position, amount] = limbSwing(frame);
  const [headPitch, headYaw] = headAngles(frame, netHeadYaw);
  javaRotate(model.parts.get('head'), headPitch, headYaw, 0);
  const swing = position * 0.6662 * 2;
  const offsets = [0, Math.PI, Math.PI / 2, Math.PI * 1.5];
  const names = [['right_hind_leg', 'left_hind_leg'], ['right_middle_hind_leg', 'left_middle_hind_leg'], ['right_middle_front_leg', 'left_middle_front_leg'], ['right_front_leg', 'left_front_leg']];
  names.forEach(([right, left], index) => {
    const offset = offsets[index] ?? 0;
    const yaw = -(Math.cos(swing + offset) * 0.4) * amount;
    const lift = Math.abs(Math.sin(position * 0.6662 + offset) * 0.4) * amount;
    const sides: [string | undefined, number][] = [[right, 1], [left, -1]];
    for (const [name, sign] of sides) {
      const node = name === undefined ? undefined : model.parts.get(name);
      const rest = name === undefined ? undefined : model.rest.get(name);
      if (node === undefined || rest === undefined) continue;
      node.rotation.set(rest.rotation[0], rest.rotation[1] - yaw * sign, rest.rotation[2] - lift * sign, 'ZYX');
    }
  });
};

export const villagerAnimator: Animator = (model, frame, netHeadYaw) => {
  resetPose(model);
  const [position, amount] = limbSwing(frame);
  const [headPitch, headYaw] = headAngles(frame, netHeadYaw);
  javaRotate(model.parts.get('head'), headPitch, headYaw, 0);
  javaRotate(model.parts.get('arms'), -0.75, 0, 0);
  javaRotate(model.parts.get('right_leg'), Math.cos(position * 0.6662) * 1.4 * amount * 0.5, 0, 0);
  javaRotate(model.parts.get('left_leg'), Math.cos(position * 0.6662 + Math.PI) * 1.4 * amount * 0.5, 0, 0);
};

export const staticAnimator: Animator = () => undefined;

export interface MobOptions {
  readonly definition: ModelDef;
  readonly texture: LoadedTexture;
  readonly animator: Animator;
  readonly height: number;
  readonly scale?: number;
  readonly tint?: number;
  readonly overlays?: readonly { readonly definition: ModelDef; readonly texture: LoadedTexture; readonly tint?: number; readonly emissive?: boolean }[];
  readonly rootOffset?: number;
  readonly innerYaw?: number;
  readonly wearsArmor?: boolean;
}

export interface ArmorLayer {
  readonly definition: ModelDef;
  readonly texture: LoadedTexture;
  readonly tint: number;
  readonly parts: readonly string[];
}

export class ModelVisual implements EntityVisual {
  readonly root = new Group();
  readonly height: number;
  protected readonly body = new Group();
  protected readonly models: BuiltModel[] = [];
  protected readonly materials: RawShaderMaterial[] = [];
  readonly wearsArmor: boolean;
  private readonly animator: Animator;
  private readonly items: ItemMesh[] = [];
  private readonly inner = new Group();
  private readonly uniforms: SharedUniforms;
  private armor: { model: BuiltModel; material: RawShaderMaterial }[] = [];
  private heldKey = '';
  private lastNetHeadYaw = 0;

  constructor(uniforms: SharedUniforms, options: MobOptions) {
    this.height = options.height;
    this.animator = options.animator;
    this.wearsArmor = options.wearsArmor === true;
    this.uniforms = uniforms;
    const inner = this.inner;
    inner.rotation.y = (options.innerYaw ?? 0) * DEG;
    inner.position.y = options.rootOffset ?? 0;
    this.body.add(inner);
    this.root.add(this.body);
    const layers = [{ definition: options.definition, texture: options.texture, tint: options.tint ?? 0xffffff, emissive: false }, ...(options.overlays ?? []).map((overlay) => ({ ...overlay, tint: overlay.tint ?? 0xffffff, emissive: overlay.emissive ?? false }))];
    for (const layer of layers) {
      const material = createEntityMaterial(uniforms, layer.texture.texture, { emissive: layer.emissive });
      setEntityTint(material, layer.tint);
      const model = buildModel(layer.definition, material, layer.texture.width, layer.texture.height);
      inner.add(model.root);
      this.models.push(model);
      this.materials.push(material);
    }
    this.body.scale.setScalar(options.scale ?? 1);
  }

  protected part(name: string): Object3D | undefined {
    return this.models[0]?.parts.get(name);
  }

  attachHeldItems(factory: ItemMeshFactory | null, equipment: EquipmentState): void {
    const main = equipment.mainhand ?? null;
    const off = equipment.offhand ?? null;
    const key = `${main?.item ?? ''}|${off?.item ?? ''}`;
    if (key === this.heldKey || factory === null) return;
    this.heldKey = key;
    for (const item of this.items) {
      item.object.removeFromParent();
      for (const material of item.materials) material.dispose();
    }
    this.items.length = 0;
    this.attachItem(factory, main, 'right_arm', -1, 'thirdperson_righthand');
    this.attachItem(factory, off, 'left_arm', 1, 'thirdperson_lefthand');
  }

  private attachItem(factory: ItemMeshFactory, stack: ItemStackData | null, armName: string, side: number, context: 'thirdperson_righthand' | 'thirdperson_lefthand'): void {
    if (stack === null) return;
    const arm = this.part(armName);
    if (arm === undefined) return;
    const mesh = factory.create(stack.item, context);
    if (mesh === null) return;
    const javaSpace = new Group();
    javaSpace.rotation.x = Math.PI;
    javaSpace.scale.setScalar(16);
    const hand = new Group();
    hand.rotation.set(-Math.PI / 2, Math.PI, 0, 'XYZ');
    const offset = new Group();
    offset.position.set(side / 16, 0.125, -0.625);
    hand.add(offset);
    offset.add(mesh.object);
    javaSpace.add(hand);
    arm.add(javaSpace);
    this.items.push({ object: javaSpace, materials: mesh.materials });
  }

  update(frame: EntityFrame): void {
    const pose = frame.pose;
    this.root.position.set(pose.x, pose.y, pose.z);
    const bodyYaw = pose.yaw;
    const netHeadYaw = Math.max(-75, Math.min(75, ((pose.headYaw - bodyYaw + 540) % 360) - 180));
    this.body.rotation.set(0, -bodyYaw * DEG, 0, 'YXZ');
    this.body.position.set(0, 0, 0);
    const posture = poseOf(frame.metadata);
    if (frame.deathMs !== null) {
      const progress = Math.min(1, Math.sqrt(Math.max(0, frame.deathMs / 50 - 1) / 20 * 1.6));
      this.body.rotation.z = progress * HALF_TURN;
    } else if (posture === 'swimming' || posture === 'fall_flying') {
      this.body.rotation.x = (90 + pose.pitch) * DEG;
      this.body.position.y = 0.3;
    } else if (posture === 'sleeping') {
      this.body.rotation.z = HALF_TURN;
      this.body.position.y = 0.2;
    }
    for (const model of this.models) this.animator(model, frame, netHeadYaw);
    this.lastNetHeadYaw = netHeadYaw;
    const hurt = frame.hurt !== null || frame.deathMs !== null;
    const invisible = flagsOf(frame.metadata).invisible;
    this.root.visible = !invisible;
    for (const material of this.materials) this.light(material, frame, hurt);
    for (const item of this.items) for (const material of item.materials) this.light(material, frame, hurt);
  }

  private light(material: RawShaderMaterial, frame: EntityFrame, hurt: boolean): void {
    setEntityLight(material, frame.sky, frame.block);
    setEntityOverlay(material, 1, 0, 0, hurt ? 0.3 : 0);
  }

  setArmor(layers: readonly ArmorLayer[]): void {
    for (const piece of this.armor) {
      piece.model.root.removeFromParent();
      piece.material.dispose();
      disposeGeometries(piece.model.root);
    }
    this.armor = layers.map((layer) => {
      const material = createEntityMaterial(this.uniforms, layer.texture.texture);
      setEntityTint(material, layer.tint);
      const model = buildModel(layer.definition, material, layer.texture.width, layer.texture.height);
      for (const [name, node] of model.parts) {
        if (!layer.parts.includes(name)) node.children.forEach((child) => { if (child instanceof Mesh) child.visible = false; });
      }
      this.inner.add(model.root);
      return { model, material };
    });
  }

  updateArmor(frame: EntityFrame): void {
    for (const piece of this.armor) {
      this.animator(piece.model, frame, this.lastNetHeadYaw);
      setEntityLight(piece.material, frame.sky, frame.block);
      setEntityOverlay(piece.material, 1, 0, 0, frame.hurt !== null || frame.deathMs !== null ? 0.3 : 0);
    }
  }

  dispose(): void {
    this.setArmor([]);
    for (const material of this.materials) material.dispose();
    for (const item of this.items) for (const material of item.materials) material.dispose();
    for (const model of this.models) disposeGeometries(model.root);
  }
}

const HALF_TURN = Math.PI / 2;

export class BoxVisual implements EntityVisual {
  readonly root = new Group();
  readonly height: number;
  private readonly geometry: BoxGeometry;
  private readonly material: MeshBasicMaterial;

  constructor(width: number, height: number, color: number) {
    this.height = height;
    this.geometry = new BoxGeometry(width, height, width);
    this.geometry.translate(0, height / 2, 0);
    this.material = new MeshBasicMaterial({ color, wireframe: false, transparent: true, opacity: 0.55, depthWrite: false });
    this.root.add(new Mesh(this.geometry, this.material));
  }

  update(frame: EntityFrame): void {
    this.root.position.set(frame.pose.x, frame.pose.y, frame.pose.z);
    this.root.rotation.y = -frame.pose.yaw * DEG;
  }

  dispose(): void {
    this.geometry.dispose();
    this.material.dispose();
  }
}
