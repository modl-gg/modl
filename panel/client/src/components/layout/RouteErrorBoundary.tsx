import { Component, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { LoadFailure } from '@/components/ui/load-failure';
import { LoadingSpinner } from '@/components/ui/loading-spinner';
import { isChunkLoadError, reloadForStaleChunk } from '@/lib/stale-chunk-recovery';

interface RouteErrorBoundaryProps {
  readonly resetKey: string;
  readonly children: ReactNode;
}

type RouteFailure = 'none' | 'reloading' | 'failed';

interface RouteErrorBoundaryState {
  readonly failure: RouteFailure;
  readonly resetKey: string;
}

function RouteLoadFailure() {
  const { t } = useTranslation();
  return (
    <LoadFailure
      className="min-h-[200px] justify-center"
      message={t('pages.routeLoadFailed')}
      onRetry={() => { window.location.reload(); }}
    />
  );
}

export class RouteErrorBoundary extends Component<RouteErrorBoundaryProps, RouteErrorBoundaryState> {
  override state: RouteErrorBoundaryState = { failure: 'none', resetKey: this.props.resetKey };

  static getDerivedStateFromError(error: unknown): Partial<RouteErrorBoundaryState> {
    return { failure: isChunkLoadError(error) ? 'reloading' : 'failed' };
  }

  static getDerivedStateFromProps(props: RouteErrorBoundaryProps, state: RouteErrorBoundaryState): Partial<RouteErrorBoundaryState> | null {
    if (props.resetKey === state.resetKey) return null;
    return { failure: 'none', resetKey: props.resetKey };
  }

  override componentDidCatch(error: unknown): void {
    if (isChunkLoadError(error) && reloadForStaleChunk()) return;
    if (this.state.failure === 'reloading') this.setState({ failure: 'failed' });
  }

  override render(): ReactNode {
    switch (this.state.failure) {
      case 'none':
        return this.props.children;
      case 'reloading':
        return (
          <div className="flex min-h-[200px] items-center justify-center">
            <LoadingSpinner className="h-6 w-6 text-primary" />
          </div>
        );
      case 'failed':
        return <RouteLoadFailure />;
    }
  }
}
