export type SignedInStatus = 'loading' | 'signed-in' | 'signed-out' | 'unavailable';

export interface SignedInAnswer {
  readonly data: unknown;
  readonly isError: boolean;
}

export function signedInStatusOf({ data, isError }: SignedInAnswer): SignedInStatus {
  if (data === null) return 'signed-out';
  if (data !== undefined) return 'signed-in';
  return isError ? 'unavailable' : 'loading';
}
