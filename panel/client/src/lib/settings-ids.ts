const ID_BYTES = 12;

export function newSettingsEntryId(prefix: string): string {
  const bytes = crypto.getRandomValues(new Uint8Array(ID_BYTES));
  return `${prefix}_${Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('')}`;
}
