export interface CatalogEntry {
  readonly id: string;
  readonly parentId: string | null;
}

export type RoleGrants = (held: readonly string[], permissionId: string) => boolean;

export function roleGrantsFor(catalog: readonly CatalogEntry[]): RoleGrants {
  const parentOf = new Map(catalog.map((entry) => [entry.id, entry.parentId]));
  return (held, permissionId) => {
    const visited = new Set<string>();
    let current: string | null | undefined = permissionId;
    while (current !== null && current !== undefined && !visited.has(current)) {
      if (held.includes(current)) return true;
      visited.add(current);
      current = parentOf.get(current);
    }
    return false;
  };
}
