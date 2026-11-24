import type { ReactNode } from 'react';

export function isRenderable(node: ReactNode): boolean {
  return node !== null && node !== undefined && typeof node !== 'boolean' && node !== '';
}
