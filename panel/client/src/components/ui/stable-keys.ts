import { useState } from 'react';

export interface Keyed<T> {
  key: string;
  item: T;
}

export function keyByContent<T>(items: readonly T[], contentKeyOf: (item: T) => string): Keyed<T>[] {
  const occurrences = new Map<string, number>();
  return items.map((item) => {
    const contentKey = contentKeyOf(item);
    const occurrence = occurrences.get(contentKey) ?? 0;
    occurrences.set(contentKey, occurrence + 1);
    return { key: `${contentKey}#${String(occurrence)}`, item };
  });
}

let nextRowKey = 0;

function createRowKey(): string {
  nextRowKey += 1;
  return `row-${String(nextRowKey)}`;
}

export function useStableRowKeys(count: number) {
  const [keys, setKeys] = useState<string[]>(() => Array.from({ length: count }, createRowKey));
  let current = keys;
  if (keys.length !== count) {
    current = count < keys.length
      ? keys.slice(0, count)
      : [...keys, ...Array.from({ length: count - keys.length }, createRowKey)];
    setKeys(current);
  }
  const removeKeyAt = (index: number) => {
    setKeys(current.filter((_, keyIndex) => keyIndex !== index));
  };
  return { keys: current, removeKeyAt };
}
