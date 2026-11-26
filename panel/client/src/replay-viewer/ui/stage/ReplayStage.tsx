import { useCallback, useEffect, useEffectEvent, useMemo, useState, type RefObject } from 'react';
import { useTranslation } from 'react-i18next';
import type { AssetLibrary } from '../../assets/AssetLibrary';
import type { CameraMode, ReplayMarker, ReplayPlayer, ReplayPlayerInfo } from '../../engine/ReplayPlayer';
import { ControlStrip, stripLayout, type StripActions } from '../controls/ControlStrip';
import { timeLabelWidth } from '../controls/timeline-math';
import { GuiEnvironmentProvider } from '../gui/GuiCanvas';
import { guiFrame, nextGuiScaleChoice, type GuiScaleChoice } from '../gui/gui-scale';
import { Crosshair } from '../hud/Crosshair';
import { HudLayer } from '../hud/HudLayer';
import { playerHudSubject } from '../hud/hud-visibility';
import { InventoryScreen } from '../hud/InventoryScreen';
import { INVENTORY_HEIGHT, INVENTORY_WIDTH } from '../hud/inventory-layout';
import { PlayerTabList } from '../hud/PlayerTabList';
import { FailureNotice } from '../FailureNotice';
import { hasEntity, usePlayback, viewedPlayer } from './playback-store';
import { ignoresShortcut, nextSpeed, shortcutFor, type ShortcutAction } from './shortcuts';
import { fullscreenAvailable, readGuiScaleChoice, storeGuiScaleChoice, useIsFullscreen, type MeasuredViewport } from './use-viewport';

const CAMERA_CYCLE: readonly CameraMode[] = ['follow', 'first-person', 'free'];
const OVERLAY_MARGIN = 2;
const WIDE_PLAYER_LIST = 360;

interface ReplayStageProps {
  readonly root: RefObject<HTMLDivElement | null>;
  readonly shortcutScope: RefObject<HTMLElement | null>;
  readonly viewport: MeasuredViewport;
  readonly player: ReplayPlayer;
  readonly assets: AssetLibrary;
  readonly markerMs: number | null;
  readonly onRetry: () => void;
}

function withCaptureMarker(markers: readonly ReplayMarker[], captureMs: number | null): readonly ReplayMarker[] {
  if (captureMs === null || markers.some((marker) => marker.kind === 'capture')) return markers;
  return [...markers, { timeMs: captureMs, kind: 'capture' }];
}

function defaultFollowTarget(players: readonly ReplayPlayerInfo[]): number | null {
  const followable = players.filter(hasEntity);
  return (followable.find((info) => info.isTarget) ?? followable.find((info) => info.present))?.entityId ?? null;
}

export function ReplayStage({ root, shortcutScope, viewport, player, assets, markerMs, onRetry }: ReplayStageProps) {
  const { t } = useTranslation();
  const snapshot = usePlayback(player);
  const { state, players } = snapshot;
  const [scaleChoice, setScaleChoice] = useState<GuiScaleChoice>(readGuiScaleChoice);
  const frame = guiFrame(viewport, scaleChoice);
  const labelWidth = useMemo(() => {
    const font = assets.font();
    return timeLabelWidth((text) => font.measure(text), player.durationMs);
  }, [assets, player]);
  const strip = stripLayout(frame.width, labelWidth);
  const [playersOpen, setPlayersOpen] = useState(frame.width >= WIDE_PLAYER_LIST);
  const [inventoryOpen, setInventoryOpen] = useState(false);
  const fullscreen = useIsFullscreen();
  const canFullscreen = fullscreenAvailable();
  const viewed = viewedPlayer(snapshot);
  const hudPlayer = playerHudSubject(state.cameraMode, viewed);
  const markers = useMemo(() => withCaptureMarker(player.markers(), markerMs), [markerMs, player]);
  const viewedEntity = viewed !== null && hasEntity(viewed) ? viewed.entityId : null;
  const renderPortrait = useCallback(
    (width: number, height: number) => (viewedEntity === null ? null : player.renderPlayerPortrait(viewedEntity, width, height)),
    [player, viewedEntity],
  );
  const environment = useMemo(() => ({ assets, scale: frame.cssScale, deviceScale: frame.deviceScale }), [assets, frame.cssScale, frame.deviceScale]);

  const seek = (timeMs: number) => {
    player.seek(Math.min(player.durationMs, Math.max(0, timeMs)));
  };

  const cycleCamera = () => {
    const live = player.state();
    const next = CAMERA_CYCLE[(CAMERA_CYCLE.indexOf(live.cameraMode) + 1) % CAMERA_CYCLE.length] ?? 'follow';
    if (next !== 'free' && live.followedEntityId === null) player.follow(defaultFollowTarget(player.players()));
    player.setCameraMode(next);
  };

  const selectPlayer = (entityId: number) => {
    const live = player.state();
    if (entityId === live.followedEntityId && live.cameraMode !== 'free') {
      player.setCameraMode('free');
      return;
    }
    player.follow(entityId);
    if (live.cameraMode === 'free') player.setCameraMode('follow');
  };

  const actions: StripActions = {
    togglePlay: () => {
      const live = player.state();
      if (live.playing) {
        player.pause();
        return;
      }
      if (live.timeMs >= live.durationMs) player.seek(0);
      player.play();
    },
    seek,
    seekBy: (deltaMs) => { seek(player.state().timeMs + deltaMs); },
    cycleSpeed: (direction) => { player.setSpeed(nextSpeed(player.state().speed, direction)); },
    cycleCamera,
    toggleLighting: () => { player.setLighting(player.state().lighting === 'game' ? 'full-bright' : 'game'); },
    togglePlayers: () => { setPlayersOpen((open) => !open); },
    toggleInventory: () => {
      if (viewed !== null) setInventoryOpen((open) => !open);
    },
    cycleGuiScale: () => {
      const next = nextGuiScaleChoice(scaleChoice, viewport);
      setScaleChoice(next);
      storeGuiScaleChoice(next);
    },
    toggleFullscreen: () => {
      const element = root.current;
      if (!canFullscreen) return;
      if (document.fullscreenElement instanceof Element) {
        void document.exitFullscreen().catch(() => undefined);
      } else if (element !== null) {
        void element.requestFullscreen().catch(() => undefined);
      }
    },
  };

  const run = (action: ShortcutAction) => {
    switch (action.kind) {
      case 'toggle-play':
        actions.togglePlay();
        return;
      case 'seek-by':
        actions.seekBy(action.deltaMs);
        return;
      case 'seek-to':
        actions.seek(action.edge === 'start' ? 0 : player.durationMs);
        return;
      case 'speed':
        player.setSpeed(action.speed);
        return;
      case 'fullscreen':
        actions.toggleFullscreen();
        return;
      case 'inventory':
        actions.toggleInventory();
        return;
      case 'camera':
        actions.cycleCamera();
    }
  };

  const onShortcut = useEffectEvent((event: KeyboardEvent) => {
    const action = shortcutFor(event);
    if (action === null || !(event.target instanceof Element) || ignoresShortcut(event.target, event.key)) return;
    event.preventDefault();
    run(action);
  });

  const captureInFreeCamera = () => {
    if (player.state().cameraMode !== 'free') player.setCameraMode('free');
    player.captureMouse();
  };

  const onWorldDoubleClick = useEffectEvent((event: MouseEvent) => {
    const element = root.current;
    if (element === null || !(event.target instanceof HTMLCanvasElement) || event.target.parentElement !== element) return;
    if (document.fullscreenElement instanceof Element) {
      actions.toggleFullscreen();
      return;
    }
    if (!canFullscreen) {
      captureInFreeCamera();
      return;
    }
    void element.requestFullscreen().then(captureInFreeCamera, captureInFreeCamera);
  });

  useEffect(() => {
    const element = root.current;
    if (element === null) return undefined;
    const listener = (event: MouseEvent) => { onWorldDoubleClick(event); };
    element.addEventListener('dblclick', listener);
    return () => {
      element.removeEventListener('dblclick', listener);
    };
  }, [root]);

  useEffect(() => {
    const scope = shortcutScope.current;
    if (scope === null) return undefined;
    const listener = (event: Event) => {
      if (event instanceof KeyboardEvent) onShortcut(event);
    };
    scope.addEventListener('keydown', listener);
    return () => {
      scope.removeEventListener('keydown', listener);
    };
  }, [shortcutScope]);

  const inventoryTop = Math.max(OVERLAY_MARGIN, Math.floor((frame.height - strip.height - INVENTORY_HEIGHT) / 2));
  const inventoryLeft = Math.max(OVERLAY_MARGIN, frame.width - INVENTORY_WIDTH - OVERLAY_MARGIN * 4);
  const place = (x: number, y: number) => ({ left: x * frame.cssScale, top: y * frame.cssScale });

  if (state.error !== null) return <FailureNotice message={t('replayViewer.failure.world')} onRetry={onRetry} />;

  return (
    <GuiEnvironmentProvider value={environment}>
      <Crosshair player={player} />
      <div className="pointer-events-none absolute inset-0" style={{ transform: `translate(${String(viewport.snapX)}px, ${String(viewport.snapY)}px)` }}>
        <HudLayer player={player} hudPlayer={hudPlayer} timeMs={state.timeMs} width={frame.width} height={frame.height} bottomInset={strip.height} />
        {playersOpen && players.length > 0 && (
          <div className="pointer-events-auto absolute" style={place(OVERLAY_MARGIN, OVERLAY_MARGIN)}>
            <PlayerTabList players={players} followedEntityId={state.cameraMode === 'free' ? null : state.followedEntityId} maxHeight={frame.height - strip.height - OVERLAY_MARGIN * 2} onSelect={selectPlayer} />
          </div>
        )}
        {inventoryOpen && viewed !== null && (
          <div className="pointer-events-auto absolute" style={place(inventoryLeft, inventoryTop)}>
            <InventoryScreen player={viewed} renderPortrait={renderPortrait} playing={state.playing} />
          </div>
        )}
        <div className="pointer-events-auto absolute left-0" style={{ top: (frame.height - strip.height) * frame.cssScale }}>
          <ControlStrip
            layout={strip}
            width={frame.width}
            state={state}
            markers={markers}
            canInventory={viewed !== null}
            toggles={{ playersOpen, inventoryOpen, fullscreen, fullscreenAvailable: canFullscreen, guiScale: scaleChoice === 'auto' ? 'auto' : frame.deviceScale }}
            actions={actions}
          />
        </div>
      </div>
    </GuiEnvironmentProvider>
  );
}
