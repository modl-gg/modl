import { useCallback, useEffect, useState } from 'react';
import { useLocation, useSearch } from 'wouter';

export function readSearchParam(search: string, name: string): string {
  return new URLSearchParams(search).get(name)?.trim() ?? '';
}

function pathWithoutSearchParam(location: string, search: string, name: string): string {
  const params = new URLSearchParams(search);
  params.delete(name);
  const remaining = params.toString();
  return remaining ? `${location}?${remaining}` : location;
}

export function useSearchParamRemover(name: string): () => void {
  const search = useSearch();
  const [location, navigate] = useLocation();
  return useCallback(() => {
    navigate(pathWithoutSearchParam(location, search, name), { replace: true });
  }, [location, navigate, name, search]);
}

export function useCapturedSearchParam(name: string): string {
  const search = useSearch();
  const removeFromAddressBar = useSearchParamRemover(name);
  const current = readSearchParam(search, name);
  const [captured, setCaptured] = useState(current);

  if (current && current !== captured) {
    setCaptured(current);
  }

  useEffect(() => {
    if (current) removeFromAddressBar();
  }, [current, removeFromAddressBar]);

  return captured;
}
