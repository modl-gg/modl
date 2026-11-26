export function sameList<T>(left: readonly T[], right: readonly T[], same: (left: T, right: T) => boolean): boolean {
  return left.length === right.length && left.every((entry, index) => {
    const other = right[index];
    return other !== undefined && same(entry, other);
  });
}
