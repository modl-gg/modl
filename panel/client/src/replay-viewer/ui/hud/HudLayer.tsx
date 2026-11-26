import { useCallback, useMemo } from 'react';
import type { ReplayPlayer, ReplayPlayerInfo } from '../../engine/ReplayPlayer';
import { GuiCanvas, useGuiEnvironment } from '../gui/GuiCanvas';
import type { GuiPainter } from '../gui/painter';
import { TICK_MS } from '../stage/shortcuts';
import { chatRows } from './chat-layout';
import { guiFeatures } from './gui-features';
import { paintHud } from './paint-hud';

const CHAT_GAP_ABOVE_HUD = 40;

interface HudLayerProps {
  readonly player: ReplayPlayer;
  readonly hudPlayer: ReplayPlayerInfo | null;
  readonly timeMs: number;
  readonly width: number;
  readonly height: number;
  readonly bottomInset: number;
}

export function HudLayer({ player, hudPlayer, timeMs, width, height, bottomInset }: HudLayerProps) {
  const { assets } = useGuiEnvironment();
  const lines = useMemo(() => player.chatUntil(timeMs), [player, timeMs]);
  const bottom = height - bottomInset;
  const offhandSlot = useMemo(() => guiFeatures(assets.version).offhand, [assets.version]);

  const paint = useCallback((painter: GuiPainter) => {
    const font = assets.font();
    paintHud(painter, {
      width,
      bottom,
      player: hudPlayer,
      chat: chatRows(lines, timeMs, (text, bold) => font.measure(text, bold)),
      chatBottom: bottom - CHAT_GAP_ABOVE_HUD,
      tick: Math.floor(timeMs / TICK_MS),
      offhandSlot,
    });
  }, [assets, bottom, hudPlayer, lines, offhandSlot, timeMs, width]);

  return <GuiCanvas width={width} height={height} paint={paint} className="absolute left-0 top-0" />;
}
