const MODAL_SURFACE_SELECTOR = '[role="dialog"], [role="alertdialog"]';

function selectionHostFor(origin: Element | null): HTMLElement {
  return origin?.closest<HTMLElement>(MODAL_SURFACE_SELECTOR) ?? document.body;
}

function createSelectionTarget(text: string): HTMLTextAreaElement {
  const textarea = document.createElement('textarea');
  textarea.value = text;
  textarea.readOnly = true;
  textarea.tabIndex = -1;
  textarea.setAttribute('aria-hidden', 'true');
  Object.assign(textarea.style, { position: 'fixed', top: '0', left: '0', opacity: '0', pointerEvents: 'none' });
  return textarea;
}

function copyWithSelection(text: string, origin: Element | null): boolean {
  const previouslyFocused = document.activeElement instanceof HTMLElement ? document.activeElement : null;
  const textarea = createSelectionTarget(text);
  selectionHostFor(origin ?? previouslyFocused).appendChild(textarea);
  try {
    textarea.focus({ preventScroll: true });
    textarea.select();
    textarea.setSelectionRange(0, text.length);
    return document.execCommand('copy');
  } catch {
    return false;
  } finally {
    textarea.remove();
    previouslyFocused?.focus({ preventScroll: true });
  }
}

async function copyWithClipboardApi(text: string): Promise<boolean> {
  if (!window.isSecureContext || !('clipboard' in navigator)) return false;
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    return false;
  }
}

export async function copyText(text: string, origin: Element | null = null): Promise<boolean> {
  return (await copyWithClipboardApi(text)) || copyWithSelection(text, origin);
}
