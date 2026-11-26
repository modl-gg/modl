import { createContext, use, type ReactNode } from 'react';
import { createPortal } from 'react-dom';

const FooterSlotContext = createContext<HTMLElement | null>(null);

export function PublicFooterSlotProvider({ slot, children }: { slot: HTMLElement | null; children: ReactNode }) {
  return <FooterSlotContext value={slot}>{children}</FooterSlotContext>;
}

export function PublicFooterContent({ children }: { children: ReactNode }) {
  const slot = use(FooterSlotContext);
  return slot === null ? null : createPortal(children, slot);
}
