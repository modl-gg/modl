import type { RoleGrants } from './role-grants.ts';

export interface CatalogPermission {
  readonly id: string;
  readonly parentId: string | null;
}

export type CategoryGrant = 'none' | 'some' | 'all';

export interface PermissionRun<T> {
  leaf: boolean;
  items: T[];
}

export function permissionRuns<T>(permissions: readonly T[], isLeaf: (permission: T) => boolean): PermissionRun<T>[] {
  const runs: PermissionRun<T>[] = [];
  for (const permission of permissions) {
    const leaf = isLeaf(permission);
    const last = runs.at(-1);
    if (last?.leaf === leaf) last.items.push(permission);
    else runs.push({ leaf, items: [permission] });
  }
  return runs;
}

export function categoryGrant(
  category: readonly CatalogPermission[],
  held: readonly string[],
  grants: RoleGrants,
  canGrant: (permissionId: string) => boolean,
): CategoryGrant {
  const grantable = category.filter((permission) => canGrant(permission.id));
  const counted = grantable.length > 0 ? grantable : category;
  const granted = counted.filter((permission) => grants(held, permission.id)).length;
  if (granted === 0) return 'none';
  return granted === counted.length ? 'all' : 'some';
}

export function toggledCategory(
  category: readonly CatalogPermission[],
  held: readonly string[],
  grants: RoleGrants,
  canGrant: (permissionId: string) => boolean,
): string[] {
  const grantableIds = new Set(category.filter((permission) => canGrant(permission.id)).map((permission) => permission.id));
  const kept = held.filter((permissionId) => !grantableIds.has(permissionId));
  return categoryGrant(category, held, grants, canGrant) === 'all' ? kept : [...kept, ...grantableIds];
}

export function toggledPermission(permissionId: string, held: readonly string[]): string[] {
  return held.includes(permissionId) ? held.filter((heldId) => heldId !== permissionId) : [...held, permissionId];
}
