import { roleGrantsFor, type CatalogEntry, type RoleGrants } from './role-grants.ts';

export interface CatalogCategory<Category extends string, Permission> {
  readonly category: Category;
  readonly permissions: readonly Permission[];
  readonly parents: readonly Permission[];
}

export interface RoleCatalog<Category extends string, Permission> {
  readonly grants: RoleGrants;
  readonly categories: readonly CatalogCategory<Category, Permission>[];
}

export function indexRoleCatalog<Permission extends CatalogEntry & { readonly category: string }>(
  catalog: readonly Permission[],
  categoryOrder: readonly Permission['category'][],
): RoleCatalog<Permission['category'], Permission> {
  const categories = categoryOrder.flatMap((category) => {
    const permissions = catalog.filter((permission) => permission.category === category);
    if (permissions.length === 0) return [];
    return [{ category, permissions, parents: permissions.filter((permission) => permission.parentId === null) }];
  });
  return { grants: roleGrantsFor(catalog), categories };
}
