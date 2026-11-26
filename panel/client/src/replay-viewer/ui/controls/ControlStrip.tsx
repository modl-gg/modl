import { useTranslation } from 'react-i18next';
import type { CameraMode, LightingMode, PlaybackState, ReplayMarker } from '../../engine/ReplayPlayer';
import { BUTTON_HEIGHT, GuiButton } from '../gui/GuiButton';
import { useGuiEnvironment } from '../gui/GuiCanvas';
import type { GuiScaleChoice } from '../gui/gui-scale';
import type { PixelIconName } from '../gui/pixel-icons';
import { formatSpeed, SHORT_SEEK_MS, TICK_MS } from '../stage/shortcuts';
import { TIME_LABEL_GAP, Timeline } from './Timeline';

export const STRIP_PADDING = 4;
const GAP = 2;
const SPEED_WIDTH = 30;
const LEFT_BUTTONS = 5;
const RIGHT_ICON_BUTTONS = 6;
const MIN_INLINE_TIMELINE = 120;
const LEFT_WIDTH = LEFT_BUTTONS * BUTTON_HEIGHT + (LEFT_BUTTONS - 1) * GAP;
const RIGHT_WIDTH = SPEED_WIDTH + RIGHT_ICON_BUTTONS * (BUTTON_HEIGHT + GAP);

const CAMERA_ICONS: Readonly<Record<CameraMode, PixelIconName>> = {
  follow: 'cameraFollow',
  'first-person': 'cameraFirstPerson',
  free: 'cameraFree',
};

const CAMERA_LABELS: Readonly<Record<CameraMode, string>> = {
  follow: 'replayViewer.camera.follow',
  'first-person': 'replayViewer.camera.firstPerson',
  free: 'replayViewer.camera.free',
};

const LIGHTING_LABELS: Readonly<Record<LightingMode, string>> = {
  game: 'replayViewer.lighting.game',
  'full-bright': 'replayViewer.lighting.fullBright',
};

export interface StripLayout {
  readonly rows: 1 | 2 | 3;
  readonly labelWidth: number;
  readonly height: number;
  readonly timelineWidth: number;
}

function heightFor(rows: number): number {
  return BUTTON_HEIGHT * rows + GAP * (rows - 1) + STRIP_PADDING * 2;
}

export function stripLayout(width: number, labelWidth: number): StripLayout {
  const available = width - STRIP_PADDING * 2;
  const timeLabel = labelWidth + TIME_LABEL_GAP;
  const inline = available - LEFT_WIDTH - RIGHT_WIDTH - GAP * 2 - timeLabel;
  if (inline >= MIN_INLINE_TIMELINE) return { rows: 1, labelWidth, height: heightFor(1), timelineWidth: inline };
  const rows = LEFT_WIDTH + RIGHT_WIDTH + GAP <= available ? 2 : 3;
  return { rows, labelWidth, height: heightFor(rows), timelineWidth: available - timeLabel };
}

export interface StripToggles {
  readonly playersOpen: boolean;
  readonly inventoryOpen: boolean;
  readonly fullscreen: boolean;
  readonly fullscreenAvailable: boolean;
  readonly guiScale: GuiScaleChoice;
}

export interface StripActions {
  readonly togglePlay: () => void;
  readonly seekBy: (deltaMs: number) => void;
  readonly seek: (timeMs: number) => void;
  readonly cycleSpeed: (direction: 1 | -1) => void;
  readonly cycleCamera: () => void;
  readonly toggleLighting: () => void;
  readonly togglePlayers: () => void;
  readonly toggleInventory: () => void;
  readonly cycleGuiScale: () => void;
  readonly toggleFullscreen: () => void;
}

interface ControlStripProps {
  readonly layout: StripLayout;
  readonly width: number;
  readonly state: PlaybackState;
  readonly markers: readonly ReplayMarker[];
  readonly toggles: StripToggles;
  readonly actions: StripActions;
  readonly canInventory: boolean;
}

export function ControlStrip({ layout, width, state, markers, toggles, actions, canInventory }: ControlStripProps) {
  const { t } = useTranslation();
  const { scale } = useGuiEnvironment();
  const gap = GAP * scale;
  const ended = state.timeMs >= state.durationMs;

  const transport = (
    <div className="flex" style={{ gap }}>
      <GuiButton label={t('replayViewer.back', { seconds: SHORT_SEEK_MS / 1000 })} icon="skipBack" onPress={() => { actions.seekBy(-SHORT_SEEK_MS); }} />
      <GuiButton label={t('replayViewer.stepBack')} icon="stepBack" onPress={() => { actions.seekBy(-TICK_MS); }} />
      <GuiButton label={state.playing ? t('replayViewer.pause') : t('replayViewer.play')} icon={state.playing ? 'pause' : 'play'} onPress={actions.togglePlay} />
      <GuiButton label={t('replayViewer.stepForward')} icon="stepForward" disabled={ended} onPress={() => { actions.seekBy(TICK_MS); }} />
      <GuiButton label={t('replayViewer.forward', { seconds: SHORT_SEEK_MS / 1000 })} icon="skipForward" disabled={ended} onPress={() => { actions.seekBy(SHORT_SEEK_MS); }} />
    </div>
  );

  const options = (
    <div className="flex" style={{ gap }}>
      <GuiButton
        label={t('replayViewer.speed', { speed: formatSpeed(state.speed) })}
        text={formatSpeed(state.speed)}
        width={SPEED_WIDTH}
        onPress={() => { actions.cycleSpeed(1); }}
        onAlternatePress={() => { actions.cycleSpeed(-1); }}
      />
      <GuiButton label={t(CAMERA_LABELS[state.cameraMode])} icon={CAMERA_ICONS[state.cameraMode]} onPress={actions.cycleCamera} />
      <GuiButton
        label={t(LIGHTING_LABELS[state.lighting])}
        icon={state.lighting === 'game' ? 'lightingGame' : 'lightingFullBright'}
        onPress={actions.toggleLighting}
      />
      <GuiButton label={t('replayViewer.players')} icon="players" pressed={toggles.playersOpen} onPress={actions.togglePlayers} />
      <GuiButton label={t('replayViewer.inventory')} icon="inventory" pressed={toggles.inventoryOpen} disabled={!canInventory} onPress={actions.toggleInventory} />
      <GuiButton
        label={t('replayViewer.guiScale', { scale: toggles.guiScale === 'auto' ? t('replayViewer.guiScaleAuto') : String(toggles.guiScale) })}
        icon="guiScale"
        onPress={actions.cycleGuiScale}
      />
      <GuiButton
        label={toggles.fullscreen ? t('replayViewer.exitFullscreen') : t('replayViewer.fullscreen')}
        icon={toggles.fullscreen ? 'exitFullscreen' : 'fullscreen'}
        disabled={!toggles.fullscreenAvailable}
        onPress={actions.toggleFullscreen}
      />
    </div>
  );

  const timeline = <Timeline width={layout.timelineWidth} labelWidth={layout.labelWidth} durationMs={state.durationMs} timeMs={state.timeMs} markers={markers} onSeek={actions.seek} onSeekBy={actions.seekBy} />;

  return (
    <div
      className="flex flex-col bg-black/50"
      style={{ width: width * scale, height: layout.height * scale, padding: STRIP_PADDING * scale, gap }}
    >
      {layout.rows === 1 && (
        <div className="flex items-center" style={{ gap }}>
          {transport}
          {timeline}
          {options}
        </div>
      )}
      {layout.rows === 2 && (
        <>
          {timeline}
          <div className="flex justify-between">
            {transport}
            {options}
          </div>
        </>
      )}
      {layout.rows === 3 && (
        <>
          {timeline}
          <div className="flex justify-center">{transport}</div>
          <div className="flex justify-center">{options}</div>
        </>
      )}
    </div>
  );
}
