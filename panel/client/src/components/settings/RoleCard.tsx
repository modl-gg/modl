import { useTranslation } from 'react-i18next';
import { Pencil, Trash2 } from 'lucide-react';
import { GroupRow } from '@/components/ui/group-list';
import { IconAction } from '@/components/ui/icon-action';
import type { StaffPermission, StaffPermissionCategory, StaffRole } from '@/hooks/data/staff';
import { useSortable } from '@/hooks/use-sortable';
import type { RoleCatalog } from '@/lib/role-catalog';

const ROLE_DRAG_TYPE = 'role';

interface RoleCardProps {
  role: StaffRole;
  index: number;
  count: number;
  editable: boolean;
  catalog: RoleCatalog<StaffPermissionCategory, StaffPermission>;
  isSettling: () => boolean;
  canMove: (from: number, to: number) => boolean;
  onMove: (dragIndex: number, hoverIndex: number) => void;
  onEdit: (role: StaffRole) => void;
  onDelete: (role: StaffRole) => void;
}

export function RoleCard({ role, index, count, editable, catalog, isSettling, canMove, onMove, onEdit, onDelete }: RoleCardProps) {
  const { t } = useTranslation();
  const sortable = useSortable<HTMLDivElement>({
    type: ROLE_DRAG_TYPE,
    id: role.id,
    label: role.name,
    index,
    count,
    disabled: !editable,
    canMove,
    isSettling,
    onMove,
  });

  const summary = (
    <span className="flex flex-wrap gap-x-3">
      {catalog.categories.filter((entry) => entry.parents.length > 0).map(({ category, parents }) => (
        <span key={category}>
          {t('settings.roles.categorySummary', {
            category: t(`settings.roles.category.${category}`),
            granted: parents.filter((permission) => catalog.grants(role.permissions, permission.id)).length,
            total: parents.length,
          })}
        </span>
      ))}
    </span>
  );

  return (
    <GroupRow
      ref={sortable.ref}
      handle={sortable.handle ?? 'spacer'}
      dragging={sortable.isDragging}
      title={role.name}
      meta={<span className="shrink-0 text-xs text-muted-foreground">{t('settings.roles.usersCount', { count: role.userCount })}</span>}
      description={role.description}
      detail={summary}
      actions={editable && (
        <>
          <IconAction icon={Pencil} label={t('settings.roles.editRole')} onClick={() => { onEdit(role); }} />
          <IconAction icon={Trash2} label={t('settings.roles.deleteRole')} destructive onClick={() => { onDelete(role); }} />
        </>
      )}
    />
  );
}
