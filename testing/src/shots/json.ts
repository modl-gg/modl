import { jsonField } from "../lib/http";

export function findInJson(value: unknown, matches: (candidate: unknown) => boolean): unknown {
  if (matches(value)) return value;
  const children = Array.isArray(value) ? value : typeof value === "object" && value !== null ? Object.keys(value).map((key) => jsonField(value, key)) : [];
  for (const child of children) {
    const found = findInJson(child, matches);
    if (found !== undefined) return found;
  }
  return undefined;
}
