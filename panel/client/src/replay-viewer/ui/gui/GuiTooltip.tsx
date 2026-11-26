import { useCallback, useMemo, type ReactNode } from 'react';
import * as TooltipPrimitive from '@radix-ui/react-tooltip';
import { GUI_COLORS } from './colors';
import { GuiCanvas, useGuiEnvironment } from './GuiCanvas';
import { drawTooltip, tooltipSize, type GuiPainter, type TooltipLine } from './painter';

export function GuiTooltipPanel({ lines }: { readonly lines: readonly TooltipLine[] }) {
  const { assets } = useGuiEnvironment();
  const size = useMemo(() => {
    const font = assets.font();
    return tooltipSize((text) => font.measure(text), lines);
  }, [assets, lines]);
  const paint = useCallback((painter: GuiPainter) => {
    drawTooltip(painter, lines);
  }, [lines]);
  return <GuiCanvas width={size.width} height={size.height} paint={paint} />;
}

interface GuiTooltipProps {
  readonly text: string;
  readonly children: ReactNode;
}

export function GuiTooltip({ text, children }: GuiTooltipProps) {
  const lines = useMemo(() => [{ text, color: GUI_COLORS.white }], [text]);
  return (
    <TooltipPrimitive.Root delayDuration={250} disableHoverableContent>
      <TooltipPrimitive.Trigger asChild>{children}</TooltipPrimitive.Trigger>
      <TooltipPrimitive.Content side="top" sideOffset={2} className="pointer-events-none z-50 select-none">
        <GuiTooltipPanel lines={lines} />
      </TooltipPrimitive.Content>
    </TooltipPrimitive.Root>
  );
}
