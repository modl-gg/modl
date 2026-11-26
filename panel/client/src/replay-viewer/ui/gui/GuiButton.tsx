import { useCallback, useState, type MouseEvent } from 'react';
import { GUI_COLORS, shadowOf } from './colors';
import { GuiCanvas, useGuiEnvironment } from './GuiCanvas';
import { GuiTooltip } from './GuiTooltip';
import type { GuiPainter } from './painter';
import { PIXEL_ICONS, type PixelIconName } from './pixel-icons';
import { drawPixelArt } from './pixels';

export const BUTTON_HEIGHT = 20;

interface GuiButtonProps {
  readonly label: string;
  readonly icon?: PixelIconName | undefined;
  readonly text?: string | undefined;
  readonly width?: number | undefined;
  readonly disabled?: boolean | undefined;
  readonly pressed?: boolean | undefined;
  readonly onPress: () => void;
  readonly onAlternatePress?: (() => void) | undefined;
}

function spriteFor(disabled: boolean, highlighted: boolean): string {
  if (disabled) return 'widget/button_disabled';
  return highlighted ? 'widget/button_highlighted' : 'widget/button';
}

function paintIcon(painter: GuiPainter, icon: PixelIconName, width: number, offsetY: number, color: number): void {
  const art = PIXEL_ICONS[icon];
  const x = Math.floor((width - art.width) / 2);
  const y = Math.floor((BUTTON_HEIGHT - art.height) / 2) + offsetY;
  drawPixelArt(painter.context, art, x + 1, y + 1, shadowOf(color));
  drawPixelArt(painter.context, art, x, y, color);
}

export function GuiButton({ label, icon, text, width = BUTTON_HEIGHT, disabled = false, pressed, onPress, onAlternatePress }: GuiButtonProps) {
  const { scale } = useGuiEnvironment();
  const [hovered, setHovered] = useState(false);
  const [focused, setFocused] = useState(false);
  const [held, setHeld] = useState(false);
  const highlighted = !disabled && (hovered || focused || pressed === true);
  const offsetY = held && !disabled ? 1 : 0;

  const paint = useCallback((painter: GuiPainter) => {
    const color = disabled ? GUI_COLORS.disabledText : GUI_COLORS.white;
    painter.stretched(spriteFor(disabled, highlighted), 0, 0, width, BUTTON_HEIGHT);
    if (icon !== undefined) paintIcon(painter, icon, width, offsetY, color);
    if (text !== undefined) painter.centeredText(text, width / 2, Math.floor((BUTTON_HEIGHT - 8) / 2) + offsetY, { color });
  }, [disabled, highlighted, icon, offsetY, text, width]);

  const handleContextMenu = (event: MouseEvent<HTMLButtonElement>) => {
    if (onAlternatePress === undefined || disabled) return;
    event.preventDefault();
    onAlternatePress();
  };

  return (
    <GuiTooltip text={label}>
      <button
        type="button"
        aria-label={label}
        aria-pressed={pressed}
        aria-disabled={disabled}
        className="block shrink-0 cursor-pointer outline-none aria-disabled:cursor-default"
        style={{ width: width * scale, height: BUTTON_HEIGHT * scale }}
        onClick={() => { if (!disabled) onPress(); }}
        onContextMenu={handleContextMenu}
        onPointerEnter={() => { setHovered(true); }}
        onPointerLeave={() => { setHovered(false); setHeld(false); }}
        onMouseDown={(event) => { event.preventDefault(); }}
        onPointerDown={() => { setHeld(true); }}
        onPointerUp={() => { setHeld(false); }}
        onFocus={(event) => { setFocused(event.currentTarget.matches(':focus-visible')); }}
        onBlur={() => { setFocused(false); }}
      >
        <GuiCanvas width={width} height={BUTTON_HEIGHT} paint={paint} />
      </button>
    </GuiTooltip>
  );
}

