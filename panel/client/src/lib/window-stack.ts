export type WindowStack = readonly string[];

export const EMPTY_WINDOW_STACK: WindowStack = [];

export function raiseWindow(stack: WindowStack, windowId: string): WindowStack {
  if (stack.at(-1) === windowId) return stack;
  return [...stack.filter((id) => id !== windowId), windowId];
}

export function dropWindow(stack: WindowStack, windowId: string): WindowStack {
  return stack.includes(windowId) ? stack.filter((id) => id !== windowId) : stack;
}

export function windowLayer(stack: WindowStack, windowId: string): number {
  return stack.indexOf(windowId) + 1;
}

export function topWindow(stack: WindowStack): string | null {
  return stack.at(-1) ?? null;
}
