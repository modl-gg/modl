import { clsx, type ClassValue } from "clsx"
import { twMerge } from "tailwind-merge"

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

export function safeExternalHref(url: string | undefined | null): string | undefined {
  if (!url || typeof url !== 'string') return undefined;
  const trimmed = url.trim();
  if (/[\t\n\r]/.test(trimmed)) return undefined;
  if (trimmed.startsWith('//') || trimmed.startsWith('/\\')) return undefined;
  if (trimmed.startsWith('/')) return trimmed;
  try {
    const parsed = new URL(trimmed);
    if (parsed.protocol === 'http:' || parsed.protocol === 'https:') return trimmed;
  } catch {
    return undefined;
  }
  return undefined;
}

export function absoluteWebHref(url: string | undefined | null): string | undefined {
  const safe = safeExternalHref(url);
  if (safe === undefined || safe.startsWith('/') || /\s/.test(safe)) return undefined;
  const parsed = new URL(safe);
  return parsed.hostname !== '' && parsed.username === '' && parsed.password === '' ? safe : undefined;
}

export function openExternalUrl(url: string | undefined | null): boolean {
  const safe = safeExternalHref(url);
  if (!safe) return false;
  const win = window.open(safe, '_blank', 'noopener,noreferrer');
  if (win) win.opener = null;
  return true;
}

export function toInAppPath(url: string): string {
  const parsed = new URL(url, window.location.origin);
  return `${parsed.pathname}${parsed.search}`;
}
