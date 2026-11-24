import { useCallback, useMemo, useState } from 'react';
import { canMoveBetween, movedItem } from '@/lib/move-item';

export interface Orderable {
  id: string;
  ordinal: number;
}

export interface DraftOrder<T> {
  items: T[];
  isDirty: boolean;
  canMove: (from: number, to: number) => boolean;
  move: (dragIndex: number, hoverIndex: number) => void;
  clear: () => void;
}

const byOrdinal = (a: Orderable, b: Orderable): number => a.ordinal - b.ordinal;

const EVERY_ITEM_MOVABLE = (): boolean => true;

function applyDraft<T extends Orderable>(sorted: readonly T[], draft: readonly string[] | null): T[] {
  if (draft === null) return [...sorted];
  const byId = new Map(sorted.map((item) => [item.id, item]));
  const drafted = draft.flatMap((id) => {
    const item = byId.get(id);
    return item ? [item] : [];
  });
  const draftedIds = new Set(draft);
  return [...drafted, ...sorted.filter((item) => !draftedIds.has(item.id))];
}

function canMoveWithin<T>(items: readonly T[], from: number, to: number, isMovable: (item: T) => boolean): boolean {
  return canMoveBetween(items.length, from, to, (index) => {
    const item = items[index];
    return item !== undefined && isMovable(item);
  });
}

export function useDraftOrder<T extends Orderable>(source: readonly T[], isMovable: (item: T) => boolean = EVERY_ITEM_MOVABLE): DraftOrder<T> {
  const [draft, setDraft] = useState<readonly string[] | null>(null);
  const sorted = useMemo(() => [...source].sort(byOrdinal), [source]);
  const items = useMemo(() => applyDraft(sorted, draft), [sorted, draft]);
  const isDirty = items.some((item, index) => sorted[index]?.id !== item.id);

  const canMove = useCallback((from: number, to: number) => canMoveWithin(items, from, to, isMovable), [items, isMovable]);

  const move = useCallback((dragIndex: number, hoverIndex: number) => {
    setDraft((current) => {
      const ordered = applyDraft(sorted, current);
      if (!canMoveWithin(ordered, dragIndex, hoverIndex, isMovable)) return current;
      const next = movedItem(ordered.map((item) => item.id), dragIndex, hoverIndex);
      return next.every((id, index) => sorted[index]?.id === id) ? null : next;
    });
  }, [sorted, isMovable]);

  const clear = useCallback(() => {
    setDraft(null);
  }, []);

  return { items, isDirty, canMove, move, clear };
}
