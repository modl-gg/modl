import { Suspense, lazy, type ReactNode, type RefObject } from 'react';
import { RouteErrorBoundary } from '@/components/layout/RouteErrorBoundary';
import { LoadingSpinner } from '@/components/ui/loading-spinner';
import type { ReplayLabeling } from '@/replay-viewer/ui/ReplayViewer';
import type { SessionSource } from '@/replay-viewer/ui/load/load-session';

const ReplayViewer = lazy(async () => import('@/replay-viewer/ui/ReplayViewer'));

export function DarkSurface({ children }: { readonly children: ReactNode }) {
  return <div className="flex h-full w-full flex-col items-center justify-center gap-3 bg-black px-6 text-center text-sm text-white/80">{children}</div>;
}

export function DarkSpinner() {
  return (
    <DarkSurface>
      <LoadingSpinner className="h-6 w-6 text-white/70" />
    </DarkSurface>
  );
}

interface ReplayViewerSurfaceProps {
  readonly attempt: number;
  readonly resetKey: string;
  readonly source: SessionSource;
  readonly shortcutScope: RefObject<HTMLElement | null>;
  readonly labeling: ReplayLabeling | null;
  readonly onRetry: () => void;
}

export function ReplayViewerSurface({ attempt, resetKey, source, shortcutScope, labeling, onRetry }: ReplayViewerSurfaceProps) {
  return (
    <RouteErrorBoundary resetKey={`${resetKey}:${String(attempt)}`}>
      <Suspense fallback={<DarkSpinner />}>
        <ReplayViewer key={attempt} source={source} shortcutScope={shortcutScope} labeling={labeling} onRetry={onRetry} />
      </Suspense>
    </RouteErrorBoundary>
  );
}
