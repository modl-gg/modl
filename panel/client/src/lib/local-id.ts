let localIdSequence = 0;

export function createLocalId(prefix: string): string {
  localIdSequence += 1;
  return `${prefix}-${String(localIdSequence)}`;
}
