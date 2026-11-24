export function arePasskeysAvailable(): boolean {
  return typeof window !== 'undefined' && window.isSecureContext;
}
