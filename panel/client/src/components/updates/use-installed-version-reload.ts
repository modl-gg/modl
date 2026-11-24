import { useEffect, useRef } from 'react';
import type { UpdateStatus } from '@/hooks/data/updates';

export function useInstalledVersionReload(status: UpdateStatus | undefined) {
  const firstSeenRef = useRef<string | null>(null);
  const version = status?.currentVersion ?? null;
  useEffect(() => {
    if (version === null) return;
    if (firstSeenRef.current === null) {
      firstSeenRef.current = version;
      return;
    }
    if (firstSeenRef.current !== version) {
      window.location.reload();
    }
  }, [version]);
}
