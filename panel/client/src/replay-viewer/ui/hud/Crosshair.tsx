import { useEffect, useRef } from 'react';
import type { ReplayPlayer, ScreenPoint } from '../../engine/ReplayPlayer';
import { GuiCanvas, useGuiEnvironment } from '../gui/GuiCanvas';
import type { GuiPainter } from '../gui/painter';
import { crosshairOrigin, CROSSHAIR_SIZE } from './crosshair-layout';

const CROSSHAIR_SPRITE = 'hud/crosshair';
const FALLBACK_COLOR = 0xffffffff;

function paintCrosshair(painter: GuiPainter): void {
  if (painter.assets.guiSprite(CROSSHAIR_SPRITE) !== null) {
    painter.sprite(CROSSHAIR_SPRITE, 0, 0);
    return;
  }
  const middle = Math.floor(CROSSHAIR_SIZE / 2);
  painter.fill(middle, 3, 1, CROSSHAIR_SIZE - 6, FALLBACK_COLOR);
  painter.fill(3, middle, CROSSHAIR_SIZE - 6, 1, FALLBACK_COLOR);
}

export function Crosshair({ player }: { readonly player: ReplayPlayer }) {
  const { scale } = useGuiEnvironment();
  const boxRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const box = boxRef.current;
    if (box === null) return undefined;
    const place = (point: ScreenPoint | null) => {
      if (point === null) {
        box.style.visibility = 'hidden';
        return;
      }
      const origin = crosshairOrigin(point, scale);
      box.style.transform = `translate(${String(origin.x)}px, ${String(origin.y)}px)`;
      box.style.visibility = 'visible';
    };
    return player.subscribeAim(place);
  }, [player, scale]);

  return (
    <div ref={boxRef} className="pointer-events-none invisible absolute left-0 top-0 mix-blend-difference">
      <GuiCanvas width={CROSSHAIR_SIZE} height={CROSSHAIR_SIZE} paint={paintCrosshair} />
    </div>
  );
}
