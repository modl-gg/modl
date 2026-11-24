import { useEffect } from 'react';

const APP_ROOT_ID = 'root';
const INERT = 'inert';

function restoreAttribute(element: HTMLElement, name: string, previous: string | null): void {
  if (previous === null) {
    element.removeAttribute(name);
  } else {
    element.setAttribute(name, previous);
  }
}

export function useModalBackground(): void {
  useEffect(() => {
    const { body } = document;
    const root = document.getElementById(APP_ROOT_ID);
    const previousOverflow = body.style.overflow;
    const previousInert = root?.getAttribute(INERT) ?? null;
    body.style.overflow = 'hidden';
    root?.setAttribute(INERT, '');
    return () => {
      body.style.overflow = previousOverflow;
      if (root !== null) restoreAttribute(root, INERT, previousInert);
    };
  }, []);
}
