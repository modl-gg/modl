import { useEffectEvent, useLayoutEffect } from 'react';
import { navigate, useBrowserLocation } from 'wouter/use-browser-location';
import { navigationGuard } from './navigation-guard';

const guardedNavigate: typeof navigate = (to, options) => {
  if (navigationGuard.intercept(to.toString(), window.location.href)) return;
  navigate(to, options);
};

export function useGuardedBrowserLocation(options?: Parameters<typeof useBrowserLocation>[0]): [string, typeof navigate] {
  const [location] = useBrowserLocation(options);
  return [location, guardedNavigate];
}

export function installHistoryGuard(): void {
  window.addEventListener('popstate', () => {
    const restored = navigationGuard.restoreTarget(window.location.href);
    if (restored !== null) window.history.pushState(restored.state, '', restored.path);
  });
}

export function navigationAllowed(target: string): boolean {
  return !navigationGuard.intercept(target, window.location.href);
}

export function useNavigationBlocker(active: boolean, onBlocked: () => void): void {
  const notifyBlocked = useEffectEvent(onBlocked);

  useLayoutEffect(() => {
    if (!active) return undefined;
    const release = navigationGuard.activate({ path: `${window.location.pathname}${window.location.search}`, state: window.history.state, onBlocked: () => { notifyBlocked(); } });
    const confirmUnload = (event: BeforeUnloadEvent) => {
      event.preventDefault();
    };
    window.addEventListener('beforeunload', confirmUnload);
    return () => {
      release();
      window.removeEventListener('beforeunload', confirmUnload);
    };
  }, [active]);
}
