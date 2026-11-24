export interface RankedRole {
  readonly id: string;
  readonly order: number;
}

export interface RoleEditor {
  readonly superAdmin: boolean;
  readonly order: number;
}

export interface RoleOrderPosition {
  id: string;
  order: number;
}

export function canEditRole(role: RankedRole, editor: RoleEditor): boolean {
  return editor.superAdmin || editor.order < role.order;
}

export function editableRoleOrder(ordered: readonly RankedRole[], editor: RoleEditor): RoleOrderPosition[] {
  if (editor.superAdmin) return ordered.map((role, index) => ({ id: role.id, order: index + 1 }));
  return ordered
    .filter((role) => canEditRole(role, editor))
    .map((role, index) => ({ id: role.id, order: editor.order + 1 + index }));
}
