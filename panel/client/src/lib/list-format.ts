export function formatList(items: readonly string[], language: string): string {
  return new Intl.ListFormat(language, { type: 'conjunction' }).format(items);
}
