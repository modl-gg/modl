import { Color, DataTexture, LinearSRGBColorSpace, Matrix4, NoToneMapping, OrthographicCamera, PerspectiveCamera, Scene, WebGLRenderTarget, WebGLRenderer } from 'three';
import type { LoadedAssets } from '../assets/loadAssets.ts';
import { atLeast, parseVersion } from '../format/MinecraftVersion.ts';
import { DEFAULT_DIMENSION, type DimensionInfo, type GameModeName } from '../format/ReplayModel.ts';
import { SparseBlockMap } from '../format/WorldLayout.ts';
import { BlockEntities } from '../render/entities/BlockEntities.ts';
import { isBlockEntityState } from '../render/entities/blockEntityStates.ts';
import { EntityRenderer } from '../render/entities/EntityRenderer.ts';
import { EntityTextures } from '../render/entities/EntityTextures.ts';
import { type Environment, environment } from '../render/Environment.ts';
import { skyDarken } from '../render/Lighting.ts';
import { SkyDome } from '../render/SkyDome.ts';
import type { Biome, BiomeTable } from '../render/world/Biomes.ts';
import { FullCubes } from '../render/world/FullCubes.ts';
import { type BlockChange, loadBiomes } from '../render/world/WorldService.ts';
import { WorldClient } from '../render/WorldClient.ts';
import { WorldRenderer } from '../render/WorldRenderer.ts';
import { aimPoint, blockReach } from './AimProjection.ts';
import { BlockCursor } from './BlockCursor.ts';
import { blockEntitySites } from './BlockEntitySites.ts';
import { CameraController } from './CameraController.ts';
import { type CameraSubject, type Direction, lookDirection } from './CameraRig.ts';
import { formatChat } from './ChatFormat.ts';
import { PlayerSnapshots } from './PlayerSnapshots.ts';
import type { PreparedReplay } from './PreparedReplay.ts';
import type {
  AimListener,
  CameraMode,
  ChatLine,
  LightingMode,
  PlaybackListener,
  PlaybackState,
  ReplayMarker,
  ReplayPlayer,
  ReplayPlayerInfo,
  ScreenPoint,
} from './ReplayPlayer.ts';
import { ReplayTimeline } from './ReplayTimeline.ts';
import { WorldProbe } from './WorldProbe.ts';

const PUBLISH_INTERVAL_MS = 100;
const PORTRAIT_TURN = 25;
const MAX_CHAT_LINES = 100;
const MAX_PIXEL_RATIO = 2;
const MAX_FRAME_DELTA_MS = 250;
const EYE_HEIGHT = 1.62;
const SNEAK_EYE_HEIGHT = 1.27;
const FREE_SEED_HEIGHT = 3;
const FREE_SEED_DISTANCE = 4;
const FREE_SEED_PITCH = 30;

interface FrameSubject extends CameraSubject {
  readonly gameMode: GameModeName | null;
}

interface WorldSlot {
  readonly dimension: DimensionInfo;
  readonly probe: WorldProbe;
  readonly cursor: BlockCursor;
  readonly sites: SparseBlockMap<number>;
}

class ReplayPlayerImpl implements ReplayPlayer {
  readonly durationMs: number;
  private readonly canvas: HTMLCanvasElement;
  private readonly replay: PreparedReplay;
  private readonly assets: LoadedAssets;
  private readonly timeline: ReplayTimeline;
  private readonly snapshots: PlayerSnapshots;
  private readonly slots = new Map<number, WorldSlot>();
  private readonly fullCubes: FullCubes;
  private readonly biomes: BiomeTable;
  private readonly legacy: boolean;
  private readonly renderer: WebGLRenderer;
  private readonly scene = new Scene();
  private readonly camera = new PerspectiveCamera(70, 1, 0.05, 1024);
  private readonly world: WorldRenderer;
  private readonly sky: SkyDome;
  private readonly entities: EntityRenderer;
  private readonly entityTextures: EntityTextures;
  private readonly blockEntities: BlockEntities;
  private readonly controls: CameraController;
  private readonly client: WorldClient;
  private readonly resizeObserver: ResizeObserver;
  private readonly listeners = new Set<PlaybackListener>();
  private readonly aimListeners = new Set<AimListener>();
  private readonly viewProjection = new Matrix4();
  private readonly clearColor = new Color();
  private readonly sectionKeys = new Set<number>();
  private readonly lookScratch: Direction = [0, 0, 0];
  private aim: ScreenPoint | null = null;
  private failure: string | null = null;
  private contextIsLost = false;
  private portrait: WebGLRenderTarget | null = null;
  private current: WorldSlot;
  private currentWorld: number;
  private timeMs = 0;
  private playing = false;
  private speed = 1;
  private followed: number | null;
  private cameraMode: CameraMode = 'follow';
  private lighting: LightingMode = 'game';
  private ready = false;
  private frameHandle = 0;
  private lastFrameAt = 0;
  private lastPublishAt = 0;
  private dirty = true;
  private disposed = false;
  private width = 1;
  private height = 1;
  private chatCache: ChatLine[] | null = null;
  private biomeCell: readonly [number, number, number] | null = null;
  private biome: Biome | null = null;

  constructor(canvas: HTMLCanvasElement, replay: PreparedReplay, assets: LoadedAssets) {
    this.canvas = canvas;
    this.replay = replay;
    this.assets = assets;
    this.timeline = new ReplayTimeline(replay.timeline);
    this.durationMs = Math.max(1, this.timeline.durationMs);
    this.snapshots = new PlayerSnapshots(this.timeline, replay.header.hardcore);
    this.legacy = !atLeast(parseVersion(assets.version), '1.13');
    this.biomes = loadBiomes(assets.pack);
    this.fullCubes = new FullCubes(assets.library, replay.palette);
    for (const [index, world] of replay.worlds) {
      this.slots.set(index, {
        dimension: world.dimension,
        probe: new WorldProbe(world.dimension.hasSkyLight, world.sections),
        cursor: new BlockCursor(this.timeline.blocks, index),
        sites: blockEntitySites(world.blockEntities),
      });
    }
    this.currentWorld = this.timeline.worldAt(0);
    this.current = this.slot(this.currentWorld);
    const target = replay.header.targetEntityId;
    this.followed = target !== null && this.timeline.entities.has(target) ? target : null;

    this.renderer = new WebGLRenderer({ canvas, antialias: false, powerPreference: 'high-performance', preserveDrawingBuffer: false });
    this.renderer.outputColorSpace = LinearSRGBColorSpace;
    this.renderer.toneMapping = NoToneMapping;
    this.renderer.setPixelRatio(Math.min(MAX_PIXEL_RATIO, window.devicePixelRatio));
    this.world = new WorldRenderer();
    this.sky = new SkyDome();
    this.scene.add(this.sky.mesh);
    this.scene.add(this.world.group);
    this.world.materials.uniforms.uGamma.value = this.legacy ? 0 : 0.5;
    this.entityTextures = new EntityTextures(assets);
    this.entities = new EntityRenderer(assets, this.entityTextures, this.world.materials.uniforms, this.timeline, (x, y, z) => this.lightAt(x, y, z));
    this.entities.onChange = () => { this.requestRender(); };
    this.scene.add(this.entities.group);
    this.blockEntities = new BlockEntities(this.entityTextures, this.world.materials.uniforms, (x, y, z) => this.lightAt(x, y, z));
    this.blockEntities.onChange = () => { this.requestRender(); };
    this.scene.add(this.blockEntities.group);
    this.controls = new CameraController(canvas, (x, y, z) => this.current.probe.solid(x, y, z));
    this.controls.onInput = () => { this.requestRender(); };
    this.controls.onEnterFree = () => { this.setCameraMode('free'); };
    const [focusX, focusY, focusZ] = this.focusPoint();
    this.controls.setFree(focusX, focusY + FREE_SEED_HEIGHT, focusZ - FREE_SEED_DISTANCE, 0, FREE_SEED_PITCH);
    if (this.followed === null) this.setCameraMode('free');
    canvas.addEventListener('webglcontextlost', this.contextLost);
    canvas.addEventListener('webglcontextrestored', this.contextRestored);
    this.resizeObserver = new ResizeObserver(() => {
      this.resize();
      this.requestRender();
    });
    this.resizeObserver.observe(canvas);
    this.resize();
    this.client = this.createClient();
    this.enterWorld(this.currentWorld);
    this.requestRender();
  }

  private slot(index: number): WorldSlot {
    let slot = this.slots.get(index);
    if (slot === undefined) {
      const dimension = { ...DEFAULT_DIMENSION, world: index };
      slot = { dimension, probe: new WorldProbe(dimension.hasSkyLight, []), cursor: new BlockCursor(this.timeline.blocks, index), sites: new SparseBlockMap<number>() };
      this.slots.set(index, slot);
    }
    return slot;
  }

  private lightAt(x: number, y: number, z: number): number {
    return this.current.probe.light(Math.floor(x), Math.floor(y), Math.floor(z));
  }

  private createClient(): WorldClient {
    const files = this.assets.files;
    return new WorldClient({
      version: this.assets.version,
      primary: new Map(files.primary),
      models: files.models === null ? null : new Map(files.models),
      palette: this.replay.palette,
      biomeKeys: this.replay.biomeKeys,
      itemKeys: this.replay.itemKeys,
      worlds: [...this.replay.worlds].map(([world, data]) => ({ world, dimension: data.dimension, sections: data.sections })),
    }, {
      atlas: (size, levels, sprites) => {
        if (size > this.renderer.capabilities.maxTextureSize) {
          this.fail('texture atlas exceeds the GPU texture size');
          return;
        }
        this.world.materials.setAtlas(size, levels, sprites);
        const texture = this.world.materials.uniforms.uAtlas.value;
        if (texture instanceof DataTexture) {
          this.entities.setAtlas({ size, pixels: levels[0] ?? new Uint8Array(), sprites: new Map(sprites.map((sprite) => [sprite.id, sprite])), texture }, this.assets.library, this.replay.palette);
        }
        this.requestRender();
      },
      section: (key, x, y, z, mesh) => {
        this.world.setSection(key, x, y, z, mesh);
        this.sectionKeys.add(key);
        this.requestRender();
      },
      idle: () => {
        if (!this.ready) {
          this.ready = true;
          this.publish(true);
        }
      },
      collision: (world, keys, bits) => {
        this.slot(world).probe.acceptCollision(keys, bits);
        this.requestRender();
      },
      error: (message) => {
        this.fail(message);
      },
    });
  }

  private fail(message: string): void {
    this.failure ??= message;
    this.ready = true;
    this.publish(true);
  }

  private enterWorld(index: number): void {
    for (const key of this.sectionKeys) this.world.removeSection(key);
    this.sectionKeys.clear();
    this.currentWorld = index;
    this.current = this.slot(index);
    const dimension = this.current.dimension;
    this.world.materials.uniforms.uAmbient.value = dimension.key.endsWith('the_nether') ? 0.1 : 0;
    this.biomeCell = null;
    const changes = this.moveBlocks(this.timeMs);
    this.blockEntities.clear();
    this.current.sites.forEach((x, y, z, state) => { this.blockEntities.set(x, y, z, this.replay.palette[state] ?? null); });
    this.client.openWorld(index, changes, this.focusPoint());
  }

  private moveBlocks(time: number): BlockChange[] {
    const changes = this.current.cursor.moveTo(time);
    for (const change of changes) {
      const state = this.replay.palette[change.state] ?? null;
      this.current.probe.setSolid(change.x, change.y, change.z, change.state !== 0 && this.fullCubes.has(change.state));
      if (isBlockEntityState(state)) this.current.sites.set(change.x, change.y, change.z, change.state);
      else this.current.sites.delete(change.x, change.y, change.z);
    }
    return changes;
  }

  private seekBlocks(time: number): void {
    const changes = this.moveBlocks(time);
    this.client.apply(changes);
    for (const change of changes) this.blockEntities.set(change.x, change.y, change.z, this.replay.palette[change.state] ?? null);
  }

  private focusPoint(): [number, number, number] {
    const track = this.followed === null ? undefined : this.timeline.entities.get(this.followed);
    const pose = track?.pose(this.timeMs) ?? null;
    if (pose !== null) return [pose.x, pose.y, pose.z];
    for (const candidate of this.timeline.entities.values()) {
      const first = candidate.pose(this.timeMs);
      if (first !== null) return [first.x, first.y, first.z];
    }
    return [0, 64, 0];
  }

  private resize(): void {
    this.width = Math.max(1, this.canvas.clientWidth);
    this.height = Math.max(1, this.canvas.clientHeight);
    this.renderer.setSize(this.width, this.height, false);
    this.camera.aspect = this.width / this.height;
    this.camera.updateProjectionMatrix();
  }

  private requestRender(): void {
    this.dirty = true;
    if (this.frameHandle === 0 && !this.disposed) {
      this.lastFrameAt = performance.now();
      this.frameHandle = requestAnimationFrame((now) => { this.frame(now); });
    }
  }

  private frame(now: number): void {
    this.frameHandle = 0;
    if (this.disposed || this.contextIsLost) return;
    const delta = Math.min(MAX_FRAME_DELTA_MS, Math.max(0, now - this.lastFrameAt));
    this.lastFrameAt = now;
    if (this.playing) {
      this.timeMs = Math.min(this.durationMs, this.timeMs + delta * this.speed);
      if (this.timeMs >= this.durationMs) {
        this.playing = false;
        this.publish(true);
      }
      this.applyTime();
    }
    this.renderFrame(delta / 1000);
    if (this.playing) this.publish(false);
    if (this.playing || this.controls.moving || this.dirty) {
      this.frameHandle = requestAnimationFrame((next) => { this.frame(next); });
    }
  }

  private applyTime(): void {
    const world = this.timeline.worldAt(this.timeMs);
    if (world !== this.currentWorld) this.enterWorld(world);
    else this.seekBlocks(this.timeMs);
  }

  private subject(): FrameSubject | null {
    if (this.followed === null) return null;
    const track = this.timeline.entities.get(this.followed);
    if (track?.present(this.timeMs) !== true) return null;
    const pose = track.pose(this.timeMs);
    if (pose === null) return null;
    const metadata = track.metadata.at(this.timeMs);
    const sneaking = metadata?.flags?.sneaking === true || metadata?.pose === 'crouching';
    const info = track.uuid === null ? null : this.timeline.playerInfo(track.uuid, this.timeMs);
    const gameMode = (track.id === this.timeline.targetEntityId ? this.timeline.targetAt(this.timeMs).gameMode : null) ?? info?.gameMode ?? null;
    return { x: pose.x, y: pose.y, z: pose.z, yaw: pose.headYaw, pitch: pose.pitch, eyeHeight: sneaking ? SNEAK_EYE_HEIGHT : EYE_HEIGHT, gameMode };
  }

  private updateEnvironment(): Environment {
    const uniforms = this.world.materials.uniforms;
    const day = this.timeline.dayAt(this.timeMs);
    uniforms.uSkyFactor.value = skyDarken(day);
    uniforms.uFullBright.value = this.lighting === 'full-bright' ? 1 : 0;
    const result = environment({
      dimension: this.current.dimension.key,
      biome: this.focusBiome(),
      radius: Math.max(32, this.replay.header.radiusBlocks || 64),
      day,
      legacy: this.legacy,
      fullBright: this.lighting === 'full-bright',
    });
    uniforms.uFogNear.value = result.fogNear;
    uniforms.uFogFar.value = result.fogFar;
    uniforms.uDepthFog.value = result.depthFog ? 1 : 0;
    const color = result.fogColor;
    const fog = uniforms.uFogColor.value;
    fog[0] = ((color >> 16) & 255) / 255;
    fog[1] = ((color >> 8) & 255) / 255;
    fog[2] = (color & 255) / 255;
    this.clearColor.setRGB(((color >> 16) & 255) / 255, ((color >> 8) & 255) / 255, (color & 255) / 255, LinearSRGBColorSpace);
    this.renderer.setClearColor(this.clearColor);
    return result;
  }

  private focusBiome(): Biome {
    const position = this.camera.position;
    const x = Math.floor(position.x);
    const y = Math.floor(position.y);
    const z = Math.floor(position.z);
    const cell = this.biomeCell;
    if (this.biome !== null && cell !== null && cell[0] === x >> 2 && cell[1] === y >> 2 && cell[2] === z >> 2) return this.biome;
    const index = this.current.probe.biome(x, y, z);
    const biome = this.biomes.get(index < 0 ? null : this.replay.biomeKeys[index] ?? null);
    this.biomeCell = [x >> 2, y >> 2, z >> 2];
    this.biome = biome;
    return biome;
  }

  private readonly contextLost = (event: Event): void => {
    event.preventDefault();
    this.contextIsLost = true;
    this.publish(true);
  };

  private readonly contextRestored = (): void => {
    this.contextIsLost = false;
    this.publish(true);
    this.requestRender();
  };

  private renderFrame(deltaSeconds: number): void {
    this.dirty = false;
    const subject = this.subject();
    const surroundings = this.updateEnvironment();
    this.world.materials.tick(this.timeMs / 50);
    this.entities.hide(this.cameraMode === 'first-person' ? this.followed : null, this.cameraMode === 'free' ? null : this.followed);
    this.entities.update(this.timeMs);
    this.controls.update(this.camera, subject, deltaSeconds);
    this.sky.mesh.visible = surroundings.skyTop !== surroundings.skyHorizon;
    this.sky.update(this.camera.position, surroundings.skyTop, surroundings.skyHorizon);
    this.world.limitToSquare(this.camera.position.x, this.camera.position.z, surroundings.squareEdge);
    this.world.sortTranslucent(this.camera.position);
    this.renderer.render(this.scene, this.camera);
    if (this.aimListeners.size > 0) this.updateAim(subject);
  }

  private updateAim(subject: FrameSubject | null): void {
    this.viewProjection.multiplyMatrices(this.camera.projectionMatrix, this.camera.matrixWorldInverse);
    const probe = this.current.probe;
    const next = aimPoint({
      cameraMode: this.cameraMode,
      subject: subject === null ? null : { eye: [subject.x, subject.y + subject.eyeHeight, subject.z], direction: lookDirection(subject.yaw, subject.pitch, this.lookScratch) },
      reach: blockReach(subject?.gameMode ?? null),
      fullCubeAt: (x, y, z) => probe.solid(x, y, z),
      viewProjection: this.viewProjection.elements,
      width: this.width,
      height: this.height,
    });
    if (next?.x === this.aim?.x && next?.y === this.aim?.y) return;
    this.aim = next;
    for (const listener of this.aimListeners) listener(next);
  }

  state(): PlaybackState {
    return {
      ready: this.ready,
      error: this.failure ?? (this.contextIsLost ? 'webgl_context_lost' : null),
      playing: this.playing,
      timeMs: this.timeMs,
      durationMs: this.durationMs,
      speed: this.speed,
      followedEntityId: this.followed,
      cameraMode: this.cameraMode,
      lighting: this.lighting,
    };
  }

  private publish(immediate: boolean): void {
    const now = performance.now();
    if (!immediate && now - this.lastPublishAt < PUBLISH_INTERVAL_MS) return;
    this.lastPublishAt = now;
    const snapshot = this.state();
    for (const listener of this.listeners) listener(snapshot);
  }

  play(): void {
    if (this.playing) return;
    if (this.timeMs >= this.durationMs) {
      this.timeMs = 0;
      this.applyTime();
    }
    this.playing = true;
    this.publish(true);
    this.requestRender();
  }

  pause(): void {
    if (!this.playing) return;
    this.playing = false;
    this.publish(true);
    this.requestRender();
  }

  seek(timeMs: number): void {
    this.timeMs = Math.min(this.durationMs, Math.max(0, Number.isFinite(timeMs) ? timeMs : 0));
    this.applyTime();
    const [x, y, z] = this.focusPoint();
    this.client.focus(x, y, z);
    this.publish(true);
    this.requestRender();
  }

  setSpeed(speed: number): void {
    this.speed = Math.min(16, Math.max(0.05, Number.isFinite(speed) ? speed : 1));
    this.publish(true);
  }

  follow(entityId: number | null): void {
    this.followed = entityId !== null && this.timeline.entities.has(entityId) ? entityId : null;
    if (this.followed === null && this.cameraMode !== 'free') {
      this.setCameraMode('free');
      return;
    }
    this.publish(true);
    this.requestRender();
  }

  setCameraMode(mode: CameraMode): void {
    const next = this.followed === null ? 'free' : mode;
    this.controls.setMode(next, this.camera);
    this.cameraMode = next;
    this.publish(true);
    this.requestRender();
  }

  captureMouse(): void {
    this.controls.captureMouse();
  }

  setFreeCamera(x: number, y: number, z: number, yaw: number, pitch: number): void {
    this.setCameraMode('free');
    this.controls.setFree(x, y, z, yaw, pitch);
    this.client.focus(x, y, z);
    this.requestRender();
  }

  private portraitTarget(width: number, height: number): WebGLRenderTarget {
    if (this.portrait?.width !== width || this.portrait.height !== height) {
      this.portrait?.dispose();
      this.portrait = new WebGLRenderTarget(width, height);
    }
    return this.portrait;
  }

  renderPlayerPortrait(entityId: number, width: number, height: number): ImageBitmap | null {
    if (this.disposed || this.contextIsLost) return null;
    const visual = this.entities.visual(entityId);
    const pose = this.timeline.entities.get(entityId)?.pose(this.timeMs) ?? null;
    if (visual === null || pose === null) return null;
    const pixelWidth = Math.max(1, Math.min(1024, Math.round(width)));
    const pixelHeight = Math.max(1, Math.min(1024, Math.round(height)));
    const target = this.portraitTarget(pixelWidth, pixelHeight);
    const scene = new Scene();
    const root = visual.root;
    const parent = root.parent;
    const visible = root.visible;
    const uniforms = this.world.materials.uniforms;
    const fullBright = uniforms.uFullBright.value;
    const fogFar = uniforms.uFogFar.value;
    const fogNear = uniforms.uFogNear.value;
    const viewHeight = visual.height * 1.25;
    const camera = new OrthographicCamera(-viewHeight * pixelWidth / pixelHeight / 2, viewHeight * pixelWidth / pixelHeight / 2, viewHeight / 2, -viewHeight / 2, 0.05, 32);
    const [dx, , dz] = lookDirection(pose.yaw + PORTRAIT_TURN, 0);
    const center = { x: root.position.x, y: root.position.y + visual.height * 0.56, z: root.position.z };
    camera.position.set(center.x + dx * 8, center.y + 0.5, center.z + dz * 8);
    camera.lookAt(center.x, center.y, center.z);
    try {
      scene.add(root);
      root.visible = true;
      uniforms.uFullBright.value = 1;
      uniforms.uFogNear.value = 1e6;
      uniforms.uFogFar.value = 2e6;
      this.renderer.setRenderTarget(target);
      this.renderer.setClearColor(0x000000, 0);
      this.renderer.clear();
      this.renderer.render(scene, camera);
      const pixels = new Uint8Array(pixelWidth * pixelHeight * 4);
      this.renderer.readRenderTargetPixels(target, 0, 0, pixelWidth, pixelHeight, pixels);
      const flipped = new Uint8ClampedArray(pixels.length);
      const row = pixelWidth * 4;
      for (let y = 0; y < pixelHeight; y++) flipped.set(pixels.subarray((pixelHeight - 1 - y) * row, (pixelHeight - y) * row), y * row);
      const canvas = new OffscreenCanvas(pixelWidth, pixelHeight);
      canvas.getContext('2d')?.putImageData(new ImageData(flipped, pixelWidth, pixelHeight), 0, 0);
      return canvas.transferToImageBitmap();
    } finally {
      this.renderer.setRenderTarget(null);
      this.renderer.setClearColor(this.clearColor, 1);
      uniforms.uFullBright.value = fullBright;
      uniforms.uFogNear.value = fogNear;
      uniforms.uFogFar.value = fogFar;
      root.visible = visible;
      if (parent !== null) parent.add(root);
      this.requestRender();
    }
  }

  setLighting(mode: LightingMode): void {
    this.lighting = mode;
    this.publish(true);
    this.requestRender();
  }

  subscribe(listener: PlaybackListener): () => void {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  }

  subscribeAim(listener: AimListener): () => void {
    this.aimListeners.add(listener);
    listener(this.aim);
    this.requestRender();
    return () => {
      this.aimListeners.delete(listener);
    };
  }

  players(): readonly ReplayPlayerInfo[] {
    return this.snapshots.at(this.timeMs);
  }

  markers(): readonly ReplayMarker[] {
    return this.timeline.markers;
  }

  chatUntil(timeMs: number): readonly ChatLine[] {
    this.chatCache ??= this.timeline.chat.map((entry) => ({ timeMs: entry.timeMs, segments: formatChat(entry.json, (key) => this.assets.lang(key)) }));
    const lines = this.chatCache;
    let low = 0;
    let high = lines.length;
    while (low < high) {
      const middle = (low + high) >> 1;
      if ((lines[middle]?.timeMs ?? Infinity) <= timeMs) low = middle + 1;
      else high = middle;
    }
    return lines.slice(Math.max(0, low - MAX_CHAT_LINES), low);
  }

  dispose(): void {
    if (this.disposed) return;
    this.disposed = true;
    if (this.frameHandle !== 0) cancelAnimationFrame(this.frameHandle);
    this.frameHandle = 0;
    this.listeners.clear();
    this.aimListeners.clear();
    this.resizeObserver.disconnect();
    this.canvas.removeEventListener('webglcontextlost', this.contextLost);
    this.canvas.removeEventListener('webglcontextrestored', this.contextRestored);
    this.controls.dispose();
    this.client.dispose();
    this.portrait?.dispose();
    this.sky.dispose();
    this.entities.dispose();
    this.blockEntities.dispose();
    this.entityTextures.dispose();
    this.world.dispose();
    this.renderer.dispose();
  }
}

export function createReplayPlayer(canvas: HTMLCanvasElement, replay: PreparedReplay, assets: LoadedAssets): ReplayPlayer {
  return new ReplayPlayerImpl(canvas, replay, assets);
}
