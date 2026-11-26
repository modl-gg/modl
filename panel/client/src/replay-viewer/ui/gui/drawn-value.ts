import { useState } from 'react';

export function useDrawnValue<T>(value: T, same: (drawn: T, next: T) => boolean): T {
  const [drawn, setDrawn] = useState(value);
  if (drawn === value || same(drawn, value)) return drawn;
  setDrawn(value);
  return value;
}
