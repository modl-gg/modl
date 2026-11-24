import React, { useRef, useEffect, useEffectEvent, useState, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import i18n from '@/lib/i18n';
import { parseReplayAsync } from '../engine/ReplayParser';
import { ReplayEngine, type TrackedPlayer } from '../engine/ReplayEngine';
import { PrismarineRenderer } from '../renderer/PrismarineRenderer';
import { CameraController } from '../renderer/CameraController';
import { loadAtlas, type LoadedAtlas } from '../renderer/AtlasLoader';
import { loadImage } from '../renderer/images';
import type { PlaybackState, PlaybackRate } from '../types/PlaybackState';
import { EventType, type ParsedReplay, type PlayerSpawnEvent } from '../types/ReplayFile';
import { TimelineControls } from './TimelineControls';
import { PlayerSidebar, type PlayerState, type PlayerSummary } from './PlayerList';
import { LoadingOverlay } from './LoadingOverlay';
import { InfoButton } from './InfoButton';
import { InfoSidebar } from './InfoSidebar';
import type { AtlasInfo } from './atlas-items';
import type { LabelSubmitHandler } from './labeling/ReviewStep';

export interface ReplayViewerProps {
  /** URL to fetch the .modlreplay file from (e.g., presigned B2 URL) */
  replayUrl: string;
  /** Minecraft version for block rendering (e.g., "1.21.4") */
  mcVersion: string;
  /** URL to the texture atlas PNG (e.g., "/atlas.png") - overrides auto-resolution */
  atlasUrl?: string;
  /** URL to the atlas mapping JSON (e.g., "/atlas-data.json") - overrides auto-resolution */
  atlasMappingUrl?: string;
  /** Base URL for versioned atlas directory (default: "/atlas") */
  atlasBaseUrl?: string;
  /** Called when the replay has been loaded and is ready to play */
  onLoad?: () => void;
  /** Called when an error occurs during loading or playback */
  onError?: (error: Error) => void;
  /** Replay identifier for info display and label submission */
  replayId?: string;
  /** Called when user submits labels via the labeling wizard */
  onLabelSubmit?: LabelSubmitHandler;
  /** Whether the replay has already been labeled (hides labeling wizard) */
  labeled?: boolean;
}

const defaultPlaybackState: PlaybackState = {
  playing: false,
  currentTimeMs: 0,
  totalDurationMs: 0,
  rate: 1,
  followingPlayer: null,
};

const AUTO_HIDE_MS = 3000;

interface Point3 {
  x: number;
  y: number;
  z: number;
}

async function fetchReplay(replayUrl: string, signal: AbortSignal): Promise<ParsedReplay> {
  const response = await fetch(replayUrl, { signal });
  if (!response.ok) {
    throw new Error(i18n.t('replay.viewer.fetchFailed', { status: response.status }));
  }
  return parseReplayAsync(await response.arrayBuffer());
}

function initialCameraTarget(replay: ParsedReplay): Point3 {
  const firstSpawn = replay.events.find((e): e is PlayerSpawnEvent => e.type === EventType.PLAYER_SPAWN);
  if (firstSpawn) {
    return { x: firstSpawn.x, y: firstSpawn.y, z: firstSpawn.z };
  }
  const { targetX, targetY, targetZ } = replay.header;
  const hasTarget = targetX !== 0 || targetY !== 0 || targetZ !== 0;
  if (hasTarget || replay.snapshot.length === 0) {
    return { x: targetX, y: targetY, z: targetZ };
  }
  const sum = { x: 0, y: 0, z: 0 };
  for (const block of replay.snapshot) {
    sum.x += block.x; sum.y += block.y; sum.z += block.z;
  }
  const count = replay.snapshot.length;
  return { x: sum.x / count, y: sum.y / count, z: sum.z / count };
}

function toPlayerState(player: TrackedPlayer): PlayerState {
  return {
    uuid: player.uuid,
    playerName: player.playerName,
    health: player.health,
    food: player.food,
    saturation: player.saturation,
    effects: player.effects,
    equipment: new Map(player.equipment),
    inventory: new Map(player.inventory),
  };
}

function toAtlasInfo(atlasImage: HTMLImageElement, atlas: LoadedAtlas): AtlasInfo {
  return {
    atlasImage,
    textures: atlas.mapping.textures,
    items: atlas.mapping.items,
    blockNameToState: atlas.mapping.blockNameToState,
    blocks: atlas.mapping.blocks,
  };
}

export const ReplayViewer: React.FC<ReplayViewerProps> = ({
  replayUrl,
  mcVersion,
  atlasUrl,
  atlasMappingUrl,
  atlasBaseUrl = '/atlas',
  onLoad,
  onError,
  replayId,
  onLabelSubmit,
  labeled,
}) => {
  const { t } = useTranslation();
  const containerRef = useRef<HTMLDivElement>(null);
  const engineRef = useRef<ReplayEngine | null>(null);
  const rendererRef = useRef<PrismarineRenderer | null>(null);
  const cameraControllerRef = useRef<CameraController | null>(null);
  const entityTypeNamesRef = useRef<Map<number, string>>(new Map());

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [playbackState, setPlaybackState] = useState<PlaybackState>(defaultPlaybackState);
  const [players, setPlayers] = useState<PlayerSummary[]>([]);
  const [atlasInfo, setAtlasInfo] = useState<AtlasInfo | null>(null);
  const [selectedPlayerState, setSelectedPlayerState] = useState<PlayerState | null>(null);
  const selectedPlayerRef = useRef<string | null>(null);
  const autoExpandedRef = useRef(false);

  const [controlsVisible, setControlsVisible] = useState(true);
  const [showInfo, setShowInfo] = useState(false);
  const [expandedPlayer, setExpandedPlayer] = useState<string | null>(null);
  const hideTimerRef = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);

  const playbackStateRef = useRef(playbackState);
  playbackStateRef.current = playbackState;

  const notifyLoaded = useEffectEvent(() => { onLoad?.(); });
  const notifyError = useEffectEvent((loadError: Error) => { onError?.(loadError); });

  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;

    const resetTimer = () => {
      setControlsVisible(true);
      clearTimeout(hideTimerRef.current);
      hideTimerRef.current = setTimeout(() => {
        if (!showInfo) {
          setControlsVisible(false);
        }
      }, AUTO_HIDE_MS);
    };

    const handleMouseMove = () => resetTimer();
    const handleMouseLeave = () => {
      clearTimeout(hideTimerRef.current);
      if (!showInfo) {
        setControlsVisible(false);
      }
    };

    container.addEventListener('mousemove', handleMouseMove);
    container.addEventListener('mouseleave', handleMouseLeave);
    resetTimer();

    return () => {
      container.removeEventListener('mousemove', handleMouseMove);
      container.removeEventListener('mouseleave', handleMouseLeave);
      clearTimeout(hideTimerRef.current);
    };
  }, [showInfo]);

  useEffect(() => {
    const abort = new AbortController();
    const { signal } = abort;
    autoExpandedRef.current = false;

    async function attachRenderer(container: HTMLElement, parsed: ParsedReplay, renderer: PrismarineRenderer): Promise<void> {
      const atlas = await loadAtlas({
        atlasUrl,
        atlasMappingUrl,
        atlasBaseUrl,
        mcVersion: parsed.header.mcVersion || mcVersion,
        signal,
      });
      if (atlas) {
        const atlasApplied = await renderer.setTextureAtlas(atlas.imageUrl, atlas.mapping).then(() => true, () => false);
        signal.throwIfAborted();
        if (atlasApplied) {
          loadImage(atlas.imageUrl, 'anonymous')
            .then((image) => {
              if (!signal.aborted) setAtlasInfo(toAtlasInfo(image, atlas));
            })
            .catch(() => undefined);
        }
      }

      renderer.init(container);
      await renderer.loadSnapshot(parsed.snapshot);
      signal.throwIfAborted();

      const canvas = renderer.getDomElement();
      if (canvas) {
        cameraControllerRef.current = new CameraController(renderer.getCamera(), canvas);
        const target = initialCameraTarget(parsed);
        renderer.getCamera().position.set(target.x - 10, target.y + 15, target.z + 10);
        renderer.getCamera().lookAt(target.x, target.y, target.z);
      }
    }

    function syncPlayers(engine: ReplayEngine, renderer: PrismarineRenderer, state: PlaybackState): Map<string, TrackedPlayer> {
      const playerMap = engine.getPlayers();
      const summaries: PlayerSummary[] = [];
      playerMap.forEach((p) => {
        if (!p.alive) {
          renderer.removePlayer(p.uuid);
          return;
        }
        summaries.push({ uuid: p.uuid, playerName: p.playerName, effects: p.effects });
        const skinData = engine.getPlayerSkin(p.uuid);
        if (skinData) {
          renderer.setEmbeddedSkin(p.uuid, skinData);
          if (p.skinUuid && p.skinUuid !== p.uuid) {
            renderer.setEmbeddedSkin(p.skinUuid, skinData);
          }
        }
        const pos = engine.getInterpolatedPlayerPosition(p.uuid) ?? p;
        renderer.updatePlayer(
          p.uuid, p.playerName,
          pos.x, pos.y, pos.z, pos.yaw, pos.pitch,
          p.sneaking, p.lastSwingTime, state.currentTimeMs,
          p.skinUuid, p.heldItem, p.lastDamageTime,
          p.equipment, p.health,
        );
      });
      setPlayers(summaries);

      const firstPlayer = summaries[0];
      if (!autoExpandedRef.current && selectedPlayerRef.current === null && firstPlayer) {
        autoExpandedRef.current = true;
        selectedPlayerRef.current = firstPlayer.uuid;
        setExpandedPlayer(firstPlayer.uuid);
      }
      return playerMap;
    }

    function syncEntities(engine: ReplayEngine, renderer: PrismarineRenderer): void {
      const activeEntityIds = new Set<number>();
      engine.getEntities().forEach((e) => {
        activeEntityIds.add(e.entityId);
        entityTypeNamesRef.current.set(e.entityId, e.entityTypeName);
        const epos = engine.getInterpolatedEntityPosition(e.entityId) ?? e;
        renderer.updateEntity(e.entityId, epos.x, epos.y, epos.z, epos.yaw, epos.pitch, e.entityTypeName);
      });
      renderer.getKnownEntityIds().forEach((id) => {
        if (!activeEntityIds.has(id)) {
          renderer.removeEntity(id, entityTypeNamesRef.current.get(id));
          entityTypeNamesRef.current.delete(id);
        }
      });
    }

    async function load(): Promise<void> {
      const parsed = await fetchReplay(replayUrl, signal);
      signal.throwIfAborted();

      const engine = new ReplayEngine(parsed);
      engineRef.current = engine;
      const renderer = new PrismarineRenderer(mcVersion);
      rendererRef.current = renderer;

      const container = containerRef.current;
      if (container) {
        await attachRenderer(container, parsed, renderer);
      }

      engine.subscribe((state, changes) => {
        if (signal.aborted) return;
        setPlaybackState(state);

        if (changes.length > 0) {
          renderer.applyBlockChanges(changes);
        }

        const playerMap = syncPlayers(engine, renderer, state);
        syncEntities(engine, renderer);

        const selectedUuid = selectedPlayerRef.current;
        const selected = selectedUuid === null ? undefined : playerMap.get(selectedUuid);
        if (selected) {
          setSelectedPlayerState(toPlayerState(selected));
        }

        const camera = cameraControllerRef.current;
        if (state.followingPlayer && camera) {
          const pos = engine.getInterpolatedPlayerPosition(state.followingPlayer);
          if (pos) {
            camera.setMode('follow');
            camera.setFollowTarget(pos);
          }
        }
      });

      engine.seek(0);

      setLoading(false);
      notifyLoaded();

      void renderer.preloadEmbeddedSkins();
    }

    load().catch((err: unknown) => {
      if (signal.aborted) return;
      const loadError = err instanceof Error ? err : new Error(String(err));
      setError(loadError.message);
      setLoading(false);
      notifyError(loadError);
    });

    return () => {
      abort.abort();
      engineRef.current?.dispose();
      rendererRef.current?.dispose();
      cameraControllerRef.current?.dispose();
    };
  }, [replayUrl, mcVersion, atlasUrl, atlasMappingUrl, atlasBaseUrl]);

  // Handle resize - both window resize and container layout changes
  useEffect(() => {
    const handleResize = () => rendererRef.current?.updateSize();
    window.addEventListener('resize', handleResize);

    const container = containerRef.current;
    let observer: ResizeObserver | null = null;
    if (container) {
      observer = new ResizeObserver(() => rendererRef.current?.updateSize());
      observer.observe(container);
    }

    return () => {
      window.removeEventListener('resize', handleResize);
      observer?.disconnect();
    };
  }, []);

  // Camera update loop
  useEffect(() => {
    let lastTime = performance.now();
    let frameId: number;

    const update = (time: number) => {
      const dt = (time - lastTime) / 1000;
      lastTime = time;
      cameraControllerRef.current?.update(dt);
      frameId = requestAnimationFrame(update);
    };

    frameId = requestAnimationFrame(update);
    return () => cancelAnimationFrame(frameId);
  }, []);

  const handlePlay = useCallback(() => engineRef.current?.play(), []);
  const handlePause = useCallback(() => engineRef.current?.pause(), []);
  const handleSeek = useCallback((timeMs: number) => {
    rendererRef.current?.resetPlayerState();
    engineRef.current?.seek(timeMs);
  }, []);
  const handleRateChange = useCallback((rate: PlaybackRate) => engineRef.current?.setRate(rate), []);

  const handleFollowPlayer = useCallback((uuid: string | null) => {
    engineRef.current?.followPlayer(uuid);
    if (uuid) {
      cameraControllerRef.current?.setMode('follow');
    } else {
      cameraControllerRef.current?.setMode('free');
    }
  }, []);

  const handleExpandPlayer = useCallback((uuid: string | null) => {
    autoExpandedRef.current = true;
    setExpandedPlayer(uuid);
    selectedPlayerRef.current = uuid;
    const player = uuid === null ? undefined : engineRef.current?.getPlayers().get(uuid);
    setSelectedPlayerState(player ? toPlayerState(player) : null);
  }, []);

  // Keyboard shortcuts
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      // Don't handle if user is typing in an input
      if (e.target instanceof HTMLInputElement || e.target instanceof HTMLTextAreaElement) return;

      const ps = playbackStateRef.current;
      switch (e.key) {
        case ' ':
          e.preventDefault();
          if (ps.playing) handlePause();
          else handlePlay();
          break;
        case 'ArrowRight':
          e.preventDefault();
          handleSeek(Math.min(ps.totalDurationMs, ps.currentTimeMs + 5000));
          break;
        case 'ArrowLeft':
          e.preventDefault();
          handleSeek(Math.max(0, ps.currentTimeMs - 5000));
          break;
        default:
          break;
      }
    };
    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [handlePlay, handlePause, handleSeek]);

  return (
    <div
      ref={containerRef}
      style={{
        position: 'absolute',
        inset: 0,
        overflow: 'hidden',
        backgroundColor: '#1a1a2e',
        cursor: controlsVisible ? 'default' : 'none',
      }}
    >
      {loading && <LoadingOverlay />}
      {error !== null && (
        <div style={{
          position: 'absolute',
          inset: 0,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          backgroundColor: 'rgba(0, 0, 0, 0.8)',
          color: '#ef4444',
          fontFamily: 'system-ui, sans-serif',
          fontSize: 14,
          zIndex: 10,
          padding: 24,
          textAlign: 'center',
        }}>
          {t('replay.viewer.loadFailed', { error })}
        </div>
      )}
      {!loading && error === null && (
        <>
          {/* Info button - top-left (hidden when sidebar is open) */}
          <InfoButton onClick={() => setShowInfo(v => !v)} visible={controlsVisible && !showInfo} />

          {/* Info sidebar - left */}
          {showInfo && (
            <InfoSidebar
              replayId={replayId}
              duration={playbackState.totalDurationMs}
              playerCount={players.length}
              playerNames={players.map(p => p.playerName)}
              onClose={() => setShowInfo(false)}
              players={players}
              currentTimeMs={playbackState.currentTimeMs}
              totalDurationMs={playbackState.totalDurationMs}
              onLabelSubmit={onLabelSubmit}
              labeled={labeled}
            />
          )}

          {/* Player sidebar - right */}
          <PlayerSidebar
            players={players}
            followingPlayer={playbackState.followingPlayer}
            expandedPlayer={expandedPlayer}
            onFollowPlayer={handleFollowPlayer}
            onExpandPlayer={handleExpandPlayer}
            currentTimeMs={playbackState.currentTimeMs}
            visible={controlsVisible}
            selectedPlayerState={selectedPlayerState}
            atlas={atlasInfo}
          />

          {/* Timeline controls - bottom center */}
          <TimelineControls
            state={playbackState}
            onPlay={handlePlay}
            onPause={handlePause}
            onSeek={handleSeek}
            onRateChange={handleRateChange}
            visible={controlsVisible}
          />
        </>
      )}
    </div>
  );
};
