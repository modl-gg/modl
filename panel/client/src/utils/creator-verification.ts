export function generateCreatorIdentifier(): string {
  const timestamp = Date.now();
  const random = Math.random().toString(36).substring(2, 15);
  const userAgent = navigator.userAgent.substring(0, 50);
  const screenInfo = `${screen.width}x${screen.height}`;
  const timezone = Intl.DateTimeFormat().resolvedOptions().timeZone;

  const components = [timestamp, random, userAgent, screenInfo, timezone].join('|');

  let hash = 0;
  for (let i = 0; i < components.length; i++) {
    const char = components.charCodeAt(i);
    hash = ((hash << 5) - hash) + char;
    hash = hash & hash;
  }
  
  const hashStr = Math.abs(hash).toString(16);
  const suffix = Math.random().toString(36).substring(2, 8);
  
  return `creator_${hashStr}_${suffix}`;
}

const sessionFallbackIdentifiers = new Map<string, string>();

export function getCreatorIdentifier(ticketId: string): string {
  const storageKey = `ticket_creator_${ticketId}`;

  try {
    const existingId = localStorage.getItem(storageKey);
    if (existingId) {
      return existingId;
    }

    const newId = generateCreatorIdentifier();
    localStorage.setItem(storageKey, newId);

    return newId;
  } catch {
    const existing = sessionFallbackIdentifiers.get(ticketId);
    if (existing) {
      return existing;
    }
    const generated = generateCreatorIdentifier();
    sessionFallbackIdentifiers.set(ticketId, generated);
    return generated;
  }
}

export function isVerifiedCreator(ticketId: string, replyCreatorId?: string): boolean {
  if (!replyCreatorId) {
    return false;
  }

  return getCreatorIdentifier(ticketId) === replyCreatorId;
}

export function shouldShowUnverifiedBadge(ticketId: string, replyCreatorId?: string, isStaffPanel = false): boolean {
  if (!replyCreatorId || isStaffPanel) {
    return false;
  }
  return !isVerifiedCreator(ticketId, replyCreatorId);
}

export function getCurrentCreatorIdentifier(ticketId: string): string | null {
  const storageKey = `ticket_creator_${ticketId}`;
  
  try {
    return localStorage.getItem(storageKey);
  } catch {
    return sessionFallbackIdentifiers.get(ticketId) ?? null;
  }
}

function tryRemoveStoredItem(storageKey: string): boolean {
  try {
    localStorage.removeItem(storageKey);
    return true;
  } catch {
    return false;
  }
}

export function clearCreatorIdentifier(ticketId: string): void {
  tryRemoveStoredItem(`ticket_creator_${ticketId}`);
  sessionFallbackIdentifiers.delete(ticketId);
}
