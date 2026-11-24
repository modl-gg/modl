import { useCallback, type RefCallback } from 'react';
import type { ConnectDragPreview, ConnectDragSource, ConnectDropTarget } from 'react-dnd';

type DndConnector = ConnectDragSource | ConnectDragPreview | ConnectDropTarget;

export function useDndConnectorRef<T extends Element>(connector: DndConnector, extraConnector?: DndConnector): RefCallback<T> {
  return useCallback((node: T | null) => {
    connector(node);
    extraConnector?.(node);
  }, [connector, extraConnector]);
}
