import { useCallback, useEffect, useMemo, useState, type PointerEvent } from 'react';
import { useTranslation } from 'react-i18next';
import type { ReplayPlayerInfo } from '../../engine/ReplayPlayer';
import { GUI_COLORS } from '../gui/colors';
import { useDrawnValue } from '../gui/drawn-value';
import { GuiCanvas, useGuiEnvironment } from '../gui/GuiCanvas';
import { GuiTooltipPanel } from '../gui/GuiTooltip';
import type { GuiPainter } from '../gui/painter';
import { BODY_HEIGHT, BODY_WIDTH, paintBodyFront, useSkin } from '../gui/skins';
import { itemDisplayName } from './game-text';
import { guiFeatures } from './gui-features';
import { INVENTORY_HEIGHT, INVENTORY_WIDTH, inventoryContents, sameInventoryContents, stackAt, type PlacedStack } from './inventory-layout';

const MODEL_BOX = { x: 26, y: 8, width: 49, height: 70 } as const;
const PORTRAIT_REFRESH_MS = 250;
const MODEL_PIXEL = 2;
const HOVER_FILL = 0x80ffffff;
const TOOLTIP_OFFSET = 12;
const RECIPE_BUTTON = { x: 104, y: 61 } as const;

interface Pointer {
  readonly x: number;
  readonly y: number;
}

export type PortraitRenderer = (width: number, height: number) => ImageBitmap | null;

interface InventoryScreenProps {
  readonly player: ReplayPlayerInfo;
  readonly renderPortrait: PortraitRenderer;
  readonly playing: boolean;
}

function useRefreshTick(active: boolean): number {
  const [tick, setTick] = useState(0);
  useEffect(() => {
    if (!active) return undefined;
    const timer = window.setInterval(() => { setTick((value) => value + 1); }, PORTRAIT_REFRESH_MS);
    return () => { window.clearInterval(timer); };
  }, [active]);
  return tick;
}

interface PortraitProps {
  readonly player: ReplayPlayerInfo;
  readonly skin: ImageBitmap | null;
  readonly renderPortrait: PortraitRenderer;
  readonly playing: boolean;
}

function Portrait({ player, skin, renderPortrait, playing }: PortraitProps) {
  const { scale, deviceScale } = useGuiEnvironment();
  const tick = useRefreshTick(playing);
  const slim = player.slim;
  const paint = useCallback((painter: GuiPainter) => {
    const portrait = renderPortrait(MODEL_BOX.width * deviceScale, MODEL_BOX.height * deviceScale);
    if (portrait === null) {
      paintBodyFront(
        painter,
        skin,
        slim,
        Math.floor((MODEL_BOX.width - BODY_WIDTH * MODEL_PIXEL) / 2),
        Math.floor((MODEL_BOX.height - BODY_HEIGHT * MODEL_PIXEL) / 2),
        MODEL_PIXEL,
      );
      return;
    }
    painter.image(portrait, 0, 0, portrait.width, portrait.height, 0, 0, MODEL_BOX.width, MODEL_BOX.height);
    portrait.close();
  }, [deviceScale, renderPortrait, skin, slim]);
  return (
    <GuiCanvas
      width={MODEL_BOX.width}
      height={MODEL_BOX.height}
      paint={paint}
      revision={playing ? tick : player}
      className="absolute"
      style={{ left: MODEL_BOX.x * scale, top: MODEL_BOX.y * scale }}
    />
  );
}

export function InventoryScreen({ player, renderPortrait, playing }: InventoryScreenProps) {
  const { t } = useTranslation();
  const { assets, scale } = useGuiEnvironment();
  const skin = useSkin(assets, player.skinHash, player.slim);
  const features = useMemo(() => guiFeatures(assets.version), [assets.version]);
  const contents = useDrawnValue(inventoryContents(player, features.offhand), sameInventoryContents);
  const [pointer, setPointer] = useState<Pointer | null>(null);
  const hovered = pointer === null ? null : stackAt(contents.stacks, pointer.x, pointer.y);

  const paint = useCallback((painter: GuiPainter) => {
    painter.inventoryBackground(0, 0);
    for (const hint of contents.hints) painter.spriteOrTexture(hint.sprite, hint.texture, hint.x, hint.y);
    if (features.recipeBook) painter.sprite('recipe_book/button', RECIPE_BUTTON.x, RECIPE_BUTTON.y);
    const title = assets.lang('container.crafting');
    if (title !== undefined) painter.text(title, features.recipeBook ? 97 : 86, features.recipeBook ? 8 : 16, { color: GUI_COLORS.containerTitle, shadow: false });
    for (const placed of contents.stacks) painter.item(placed.stack.item, placed.x, placed.y, placed.stack.count, placed.stack.enchanted);
    if (hovered !== null) painter.fill(hovered.x, hovered.y, 16, 16, HOVER_FILL);
  }, [assets, contents, features, hovered]);

  const trackPointer = (event: PointerEvent<HTMLDivElement>) => {
    const bounds = event.currentTarget.getBoundingClientRect();
    setPointer({ x: Math.floor((event.clientX - bounds.left) / scale), y: Math.floor((event.clientY - bounds.top) / scale) });
  };

  return (
    <div
      role="group"
      aria-label={t('replayViewer.inventoryOf', { name: player.name })}
      className="relative"
      style={{ width: INVENTORY_WIDTH * scale, height: INVENTORY_HEIGHT * scale }}
      onPointerMove={trackPointer}
      onPointerLeave={() => { setPointer(null); }}
    >
      <GuiCanvas width={INVENTORY_WIDTH} height={INVENTORY_HEIGHT} paint={paint} />
      <Portrait player={player} skin={skin} renderPortrait={renderPortrait} playing={playing} />
      {hovered !== null && pointer !== null && <ItemTooltip placed={hovered} pointer={pointer} />}
      <ul className="sr-only">
        {contents.stacks.map((placed) => (
          <li key={`${String(placed.x)}:${String(placed.y)}`}>{`${itemDisplayName(assets, placed.stack.item)} ${String(placed.stack.count)}`}</li>
        ))}
      </ul>
    </div>
  );
}

function ItemTooltip({ placed, pointer }: { readonly placed: PlacedStack; readonly pointer: Pointer }) {
  const { assets, scale } = useGuiEnvironment();
  const lines = useMemo(() => [{
    text: itemDisplayName(assets, placed.stack.item),
    color: placed.stack.enchanted ? GUI_COLORS.aqua : GUI_COLORS.white,
  }], [assets, placed]);
  return (
    <div className="pointer-events-none absolute z-10" style={{ left: (pointer.x + TOOLTIP_OFFSET - 4) * scale, top: (pointer.y - TOOLTIP_OFFSET - 4) * scale }}>
      <GuiTooltipPanel lines={lines} />
    </div>
  );
}
