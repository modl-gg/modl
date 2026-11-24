import type { ReactNode } from 'react';
import { DndProvider, useDragLayer } from 'react-dnd';
import { HTML5Backend } from 'react-dnd-html5-backend';
import { SortAnnouncerProvider } from '@/hooks/use-sort-announcer';
import { useHoldSaveBarWhile } from './settings-save-scope';

function SaveBarDragHold() {
  const dragging = useDragLayer((monitor) => monitor.isDragging());
  useHoldSaveBarWhile(dragging);
  return null;
}

export function SettingsDndProvider({ children }: { children: ReactNode }) {
  return (
    <DndProvider backend={HTML5Backend}>
      <SaveBarDragHold />
      <SortAnnouncerProvider>{children}</SortAnnouncerProvider>
    </DndProvider>
  );
}
