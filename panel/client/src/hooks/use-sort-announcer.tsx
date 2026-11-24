import { createContext, use, useCallback, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';

export interface SortMove {
  item: string;
  position: number;
  total: number;
}

type AnnounceSortMove = (move: SortMove) => void;

const SortAnnouncerContext = createContext<AnnounceSortMove>(() => undefined);

export function SortAnnouncerProvider({ children }: { children: ReactNode }) {
  const { t } = useTranslation();
  const [move, setMove] = useState<SortMove | null>(null);
  const announce = useCallback((next: SortMove) => { setMove(next); }, []);
  const message = move === null ? '' : t('common.itemMoved', { ...move });

  return (
    <SortAnnouncerContext value={announce}>
      {children}
      <span className="sr-only" role="status" aria-live="polite">{message}</span>
    </SortAnnouncerContext>
  );
}

export function useSortAnnouncer(): AnnounceSortMove {
  return use(SortAnnouncerContext);
}
