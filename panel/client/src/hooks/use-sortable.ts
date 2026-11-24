import { useCallback, useLayoutEffect, useRef, type KeyboardEvent, type RefCallback } from 'react';
import { useDrag, useDrop } from 'react-dnd';
import { canMoveBetween, EVERY_POSITION_MOVABLE, keyboardMoveTarget, keyboardStep } from '@/lib/move-item';
import { useDndConnectorRef } from './use-dnd-connector-ref';
import { useSortAnnouncer } from './use-sort-announcer';

export interface SortableItem {
  id: string;
  index: number;
  group: string;
}

export interface SortHandle {
  ref: RefCallback<HTMLButtonElement>;
  itemLabel: string;
  groupLabel: string | null;
  onKeyDown: (event: KeyboardEvent<HTMLButtonElement>) => void;
}

interface SortableOptions {
  type: string;
  id: string;
  label: string;
  index: number;
  count: number;
  group?: string;
  groupLabel?: string | undefined;
  disabled?: boolean;
  canMove?: ((from: number, to: number) => boolean) | undefined;
  isSettling: () => boolean;
  onMove: (dragIndex: number, hoverIndex: number) => void;
  onDropFromOtherGroup?: (item: SortableItem) => void;
}

interface Sortable<T extends HTMLElement> {
  ref: RefCallback<T>;
  handle: SortHandle | undefined;
  isDragging: boolean;
}

function crossedMiddle(element: HTMLElement, pointerY: number, movingDown: boolean): boolean {
  const bounds = element.getBoundingClientRect();
  const middle = bounds.top + bounds.height / 2;
  return movingDown ? pointerY >= middle : pointerY <= middle;
}

export function useSortable<T extends HTMLElement>({
  type,
  id,
  label,
  index,
  count,
  group = '',
  groupLabel,
  disabled = false,
  canMove = (from, to) => canMoveBetween(count, from, to, EVERY_POSITION_MOVABLE),
  isSettling,
  onMove,
  onDropFromOtherGroup,
}: SortableOptions): Sortable<T> {
  const elementRef = useRef<T | null>(null);
  const handleRef = useRef<HTMLButtonElement | null>(null);
  const refocusTargetRef = useRef<number | null>(null);
  const announce = useSortAnnouncer();

  const [{ isDragging }, drag] = useDrag({
    type,
    item: (): SortableItem => ({ id, index, group }),
    canDrag: !disabled,
    collect: (monitor) => ({ isDragging: monitor.isDragging() }),
  });

  const [, drop] = useDrop<SortableItem>({
    accept: type,
    hover: (item, monitor) => {
      const element = elementRef.current;
      const pointer = monitor.getClientOffset();
      if (element === null || pointer === null) return;
      if (item.group !== group || !canMove(item.index, index) || isSettling()) return;
      if (!crossedMiddle(element, pointer.y, item.index < index)) return;
      onMove(item.index, index);
      item.index = index;
    },
    drop: (item) => {
      if (item.group !== group) onDropFromOtherGroup?.(item);
    },
  });

  useLayoutEffect(() => {
    const target = refocusTargetRef.current;
    if (target === null) return;
    refocusTargetRef.current = null;
    if (target !== index) return;
    handleRef.current?.focus();
    announce({ item: label, position: index + 1, total: count });
  });

  const connectDraggable = useDndConnectorRef<T>(drag, drop);
  const connectDropOnly = useDndConnectorRef<T>(drop);
  const connect = disabled ? connectDropOnly : connectDraggable;
  const ref = useCallback((node: T | null) => {
    elementRef.current = node;
    connect(node);
  }, [connect]);

  const connectHandle = useCallback((node: HTMLButtonElement | null) => {
    handleRef.current = node;
  }, []);

  const onKeyDown = (event: KeyboardEvent<HTMLButtonElement>) => {
    const step = keyboardStep(event.key);
    if (step === undefined) return;
    event.preventDefault();
    const target = keyboardMoveTarget(index, step, canMove);
    if (target === null) return;
    refocusTargetRef.current = target;
    onMove(index, target);
  };

  const handle = disabled ? undefined : { ref: connectHandle, itemLabel: label, groupLabel: groupLabel ?? null, onKeyDown };

  return { ref, handle, isDragging };
}
