const URL_SYNTAX = /[/?#]/;

export function replayIdFromReference(reference: string | null | undefined, origin: string): string | null {
  const trimmed = reference?.trim();
  if (!trimmed) return null;
  if (!URL_SYNTAX.test(trimmed)) return trimmed;

  try {
    return new URL(trimmed, origin).searchParams.get('id') || null;
  } catch {
    return null;
  }
}

export function replayPagePath(replayId: string): string {
  return `/replay?id=${encodeURIComponent(replayId)}`;
}
