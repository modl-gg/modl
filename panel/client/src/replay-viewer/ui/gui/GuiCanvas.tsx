import { createContext, use, useLayoutEffect, useRef, type CSSProperties } from 'react';
import type { AssetLibrary } from '../../assets/AssetLibrary';
import { cn } from '@/lib/utils';
import { GuiPainter } from './painter';

export interface GuiEnvironment {
  readonly assets: AssetLibrary;
  readonly scale: number;
  readonly deviceScale: number;
}

const GuiEnvironmentContext = createContext<GuiEnvironment | null>(null);

export const GuiEnvironmentProvider = GuiEnvironmentContext.Provider;

export function useGuiEnvironment(): GuiEnvironment {
  const environment = use(GuiEnvironmentContext);
  if (environment === null) throw new Error('GuiEnvironmentProvider is missing');
  return environment;
}

export type Paint = (painter: GuiPainter) => void;

interface GuiCanvasProps {
  readonly width: number;
  readonly height: number;
  readonly paint: Paint;
  readonly revision?: unknown;
  readonly className?: string | undefined;
  readonly style?: CSSProperties | undefined;
}

export function GuiCanvas({ width, height, paint, revision, className, style }: GuiCanvasProps) {
  const { assets, scale, deviceScale } = useGuiEnvironment();
  const canvasRef = useRef<HTMLCanvasElement>(null);

  useLayoutEffect(() => {
    const canvas = canvasRef.current;
    const context = canvas?.getContext('2d') ?? null;
    if (canvas === null || context === null) return;
    context.setTransform(1, 0, 0, 1, 0, 0);
    context.clearRect(0, 0, canvas.width, canvas.height);
    context.setTransform(deviceScale, 0, 0, deviceScale, 0, 0);
    paint(new GuiPainter(context, assets));
  }, [assets, deviceScale, paint, revision, width, height]);

  return (
    <canvas
      ref={canvasRef}
      width={width * deviceScale}
      height={height * deviceScale}
      aria-hidden
      className={cn('pointer-events-none block [image-rendering:pixelated]', className)}
      style={{ width: width * scale, height: height * scale, ...style }}
    />
  );
}
