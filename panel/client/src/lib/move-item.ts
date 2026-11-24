export type MovablePosition = (index: number) => boolean;

export const EVERY_POSITION_MOVABLE: MovablePosition = () => true;

const KEYBOARD_STEPS: Readonly<Record<string, number>> = { ArrowUp: -1, ArrowDown: 1 };

export function movedItem<T>(items: readonly T[], from: number, to: number): T[] {
  const next = [...items];
  const [moved] = next.splice(from, 1);
  if (moved !== undefined) next.splice(to, 0, moved);
  return next;
}

export function renumbered<Item extends { order: number }>(items: readonly Item[]): Item[] {
  return items.map((item, index) => ({ ...item, order: index }));
}

export function canMoveBetween(count: number, from: number, to: number, isMovable: MovablePosition): boolean {
  if (from === to || from < 0 || to < 0 || from >= count || to >= count) return false;
  const low = Math.min(from, to);
  const high = Math.max(from, to);
  for (let index = low; index <= high; index += 1) {
    if (!isMovable(index)) return false;
  }
  return true;
}

export function keyboardStep(key: string): number | undefined {
  return KEYBOARD_STEPS[key];
}

export function keyboardMoveTarget(index: number, step: number, canMove: (from: number, to: number) => boolean): number | null {
  const target = index + step;
  return canMove(index, target) ? target : null;
}
