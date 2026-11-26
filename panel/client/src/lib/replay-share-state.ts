export interface ShareState {
  readonly id: string;
  readonly expiresAt: number | null;
  readonly revokedAt: number | null;
}

export function isShareActive(share: ShareState, now: number): boolean {
  return share.revokedAt === null && (share.expiresAt === null || share.expiresAt > now);
}

export function isShareRevoked(shareId: string, shares: readonly ShareState[] | undefined): boolean {
  return shares?.some((share) => share.id === shareId && share.revokedAt !== null) ?? false;
}
