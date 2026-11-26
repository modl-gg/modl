import { useCallback, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { ReplayPlayerInfo } from '../../engine/ReplayPlayer';
import { GUI_COLORS } from '../gui/colors';
import { useDrawnValue } from '../gui/drawn-value';
import { GuiCanvas, useGuiEnvironment } from '../gui/GuiCanvas';
import type { GuiPainter } from '../gui/painter';
import { paintHead, useSkin } from '../gui/skins';
import { sameTabRows, tabRows, type TabRow } from './tab-rows';

const ROW_HEIGHT = 9;
const ENTRY_HEIGHT = 8;
const HEAD_SIZE = 8;
const NAME_OFFSET = 9;
const SCORE_GAP = 6;
const PADDING = 1;
const ENTRY_FILL = 0x20ffffff;
const HIGHLIGHT_FILL = 0x50ffffff;
const BACKGROUND_FILL = 0x80000000;
const ABSENT_ALPHA = 0x90 / 0xff;
const MAX_ROWS = 20;
const COLUMN_GAP = 5;

function entryOrigin(index: number, perColumn: number, entryWidth: number): { readonly x: number; readonly y: number } {
  return {
    x: PADDING + Math.floor(index / perColumn) * (entryWidth + COLUMN_GAP),
    y: PADDING + (index % perColumn) * ROW_HEIGHT,
  };
}

interface PlayerTabListProps {
  readonly players: readonly ReplayPlayerInfo[];
  readonly followedEntityId: number | null;
  readonly maxHeight: number;
  readonly onSelect: (entityId: number) => void;
}

function TabHead({ row }: { readonly row: TabRow }) {
  const { assets } = useGuiEnvironment();
  const skin = useSkin(assets, row.skinHash, row.slim);
  const present = row.present;
  const paint = useCallback((painter: GuiPainter) => {
    painter.context.save();
    painter.context.globalAlpha = present ? 1 : ABSENT_ALPHA;
    paintHead(painter, skin, 0, 0, HEAD_SIZE);
    painter.context.restore();
  }, [present, skin]);
  return <GuiCanvas width={HEAD_SIZE} height={HEAD_SIZE} paint={paint} />;
}

export function PlayerTabList({ players, followedEntityId, maxHeight, onSelect }: PlayerTabListProps) {
  const { t } = useTranslation();
  const { assets, scale } = useGuiEnvironment();
  const [hovered, setHovered] = useState<string | null>(null);
  const rows = useDrawnValue(tabRows(players), sameTabRows);
  const entryWidth = useMemo(() => {
    const font = assets.font();
    const nameWidth = Math.max(0, ...rows.map((row) => font.measure(row.name)));
    const scoreWidth = Math.max(0, ...rows.map((row) => font.measure(row.score)));
    return NAME_OFFSET + nameWidth + (scoreWidth > 0 ? SCORE_GAP + scoreWidth : 0) + 1;
  }, [assets, rows]);
  const rowsThatFit = Math.max(1, Math.floor((maxHeight - PADDING * 2 + 1) / ROW_HEIGHT));
  const columns = Math.ceil(rows.length / Math.min(MAX_ROWS, rowsThatFit));
  const perColumn = Math.ceil(rows.length / columns);
  const width = columns * entryWidth + (columns - 1) * COLUMN_GAP + PADDING * 2;
  const height = perColumn * ROW_HEIGHT - 1 + PADDING * 2;

  const paint = useCallback((painter: GuiPainter) => {
    painter.fill(0, 0, width, height, BACKGROUND_FILL);
    rows.forEach((row, index) => {
      const { x, y } = entryOrigin(index, perColumn, entryWidth);
      const highlighted = (row.entityId !== null && row.entityId === followedEntityId) || row.uuid === hovered;
      painter.fill(x, y, entryWidth, ENTRY_HEIGHT, highlighted ? HIGHLIGHT_FILL : ENTRY_FILL);
      painter.context.save();
      painter.context.globalAlpha = row.present ? 1 : ABSENT_ALPHA;
      painter.text(row.name, x + NAME_OFFSET, y);
      painter.context.restore();
      if (row.score !== '') painter.text(row.score, x + entryWidth - 1 - painter.textWidth(row.score), y, { color: GUI_COLORS.scoreboardNumber });
    });
  }, [entryWidth, followedEntityId, height, hovered, perColumn, rows, width]);

  return (
    <div className="relative" style={{ width: width * scale, height: height * scale }}>
      <GuiCanvas width={width} height={height} paint={paint} />
      <ul className="absolute inset-0" aria-label={t('replayViewer.players')}>
        {rows.map((row, index) => {
          const origin = entryOrigin(index, perColumn, entryWidth);
          return (
            <li key={row.uuid} className="absolute" style={{ left: origin.x * scale, top: origin.y * scale, width: entryWidth * scale, height: ENTRY_HEIGHT * scale }}>
              {row.entityId === null ? (
                <div className="flex h-full w-full items-center">
                  <TabHead row={row} />
                  <span className="sr-only">{row.name}</span>
                </div>
              ) : (
                <TabButton row={row} entityId={row.entityId} followed={row.entityId === followedEntityId} onSelect={onSelect} onHover={setHovered} />
              )}
            </li>
          );
        })}
      </ul>
    </div>
  );
}

interface TabButtonProps {
  readonly row: TabRow;
  readonly entityId: number;
  readonly followed: boolean;
  readonly onSelect: (entityId: number) => void;
  readonly onHover: (uuid: string | null) => void;
}

function TabButton({ row, entityId, followed, onSelect, onHover }: TabButtonProps) {
  const { t } = useTranslation();
  return (
    <button
      type="button"
      aria-pressed={followed}
      aria-label={t('replayViewer.follow', { name: row.name })}
      className="flex h-full w-full cursor-pointer items-center outline-none focus-visible:outline-1 focus-visible:outline-white"
      onClick={() => { onSelect(entityId); }}
      onMouseDown={(event) => { event.preventDefault(); }}
      onPointerEnter={() => { onHover(row.uuid); }}
      onPointerLeave={() => { onHover(null); }}
    >
      <TabHead row={row} />
    </button>
  );
}
