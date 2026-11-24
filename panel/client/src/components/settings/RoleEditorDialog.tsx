import { useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Button } from '@/components/ui/button';
import { Checkbox } from '@/components/ui/checkbox';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { useCreateRole, useUpdateRole, type StaffPermission, type StaffPermissionCategory, type StaffRole } from '@/hooks/data/staff';
import { useToast } from '@/hooks/use-toast';
import { describeError } from '@/lib/http-error';
import type { RoleCatalog } from '@/lib/role-catalog';
import type { RoleGrants } from '@/lib/role-grants';
import { categoryGrant, permissionRuns, toggledCategory, toggledPermission } from '@/lib/role-permissions';
import { cn } from '@/lib/utils';

interface PermissionCheckboxProps {
  permission: StaffPermission;
  checked: boolean;
  disabled: boolean;
  onToggle: () => void;
}

function PermissionCheckbox({ permission, checked, disabled, onToggle }: PermissionCheckboxProps) {
  const checkboxId = useId();

  return (
    <div className="flex items-start gap-2">
      <Checkbox id={checkboxId} checked={checked} disabled={disabled} onCheckedChange={onToggle} />
      <div className="grid gap-1.5 leading-none">
        <Label htmlFor={checkboxId} className={cn('text-sm font-medium leading-none', disabled && 'text-muted-foreground')}>
          {permission.name}
        </Label>
        <p className="text-xs text-muted-foreground">{permission.description}</p>
      </div>
    </div>
  );
}

interface CategoryPermissionsProps {
  category: StaffPermissionCategory;
  permissions: readonly StaffPermission[];
  grants: RoleGrants;
  held: readonly string[];
  canGrant: (permissionId: string) => boolean;
  onChange: (held: string[]) => void;
}

function CategoryPermissions({ category, permissions, grants, held, canGrant, onChange }: CategoryPermissionsProps) {
  const { t } = useTranslation();
  const checkboxId = useId();
  const grant = categoryGrant(permissions, held, grants, canGrant);
  const childrenOf = (parentId: string) => permissions.filter((permission) => permission.parentId === parentId);
  const toggle = (permissionId: string) => {
    onChange(toggledPermission(permissionId, held));
  };

  const renderParent = (parent: StaffPermission) => {
    const parentHeld = held.includes(parent.id);
    const children = childrenOf(parent.id);
    return (
      <div key={parent.id} className="space-y-2">
        <PermissionCheckbox permission={parent} checked={parentHeld} disabled={!canGrant(parent.id)} onToggle={() => { toggle(parent.id); }} />
        {children.length > 0 && (
          <div className="grid grid-cols-1 gap-2 pl-6 md:grid-cols-2">
            {children.map((child) => (
              <PermissionCheckbox
                key={child.id}
                permission={child}
                checked={parentHeld || held.includes(child.id)}
                disabled={parentHeld || !canGrant(child.id)}
                onToggle={() => { toggle(child.id); }}
              />
            ))}
          </div>
        )}
      </div>
    );
  };

  const parents = permissions.filter((permission) => permission.parentId === null);
  const runs = permissionRuns(parents, (parent) => childrenOf(parent.id).length === 0);

  return (
    <div className="space-y-3 py-4 first:pt-0 last:pb-0">
      <div className="flex items-center gap-2">
        <Checkbox
          id={checkboxId}
          checked={grant === 'some' ? 'indeterminate' : grant === 'all'}
          disabled={!permissions.some((permission) => canGrant(permission.id))}
          onCheckedChange={() => { onChange(toggledCategory(permissions, held, grants, canGrant)); }}
        />
        <Label htmlFor={checkboxId} className="text-sm font-medium">{t(`settings.roles.category.${category}`)}</Label>
      </div>
      <div className="space-y-3 pl-4">
        {runs.map((run) => (run.leaf ? (
          <div key={run.items[0]?.id} className="grid grid-cols-1 gap-3 md:grid-cols-2">{run.items.map(renderParent)}</div>
        ) : run.items.map(renderParent)))}
      </div>
    </div>
  );
}

interface RoleEditorDialogProps {
  role: StaffRole | null;
  catalog: RoleCatalog<StaffPermissionCategory, StaffPermission>;
  canGrant: (permissionId: string) => boolean;
  onClose: () => void;
}

export function RoleEditorDialog({ role, catalog, canGrant, onClose }: RoleEditorDialogProps) {
  const { t } = useTranslation();
  const { toast } = useToast();
  const fieldId = useId();
  const createRole = useCreateRole();
  const updateRole = useUpdateRole();
  const [name, setName] = useState(role?.name ?? '');
  const [description, setDescription] = useState(role?.description ?? '');
  const [permissions, setPermissions] = useState<string[]>(() => [...(role?.permissions ?? [])]);
  const saving = createRole.isPending || updateRole.isPending;
  const valid = name.trim() !== '';

  const save = () => {
    const input = { name: name.trim(), description: description.trim(), permissions };
    const callbacks = {
      onSuccess: onClose,
      onError: (error: Error) => {
        toast({ title: t('toast.error'), description: describeError(error, t('settings.roles.saveRoleFailed')), variant: 'destructive' });
      },
    };
    if (role === null) createRole.mutate(input, callbacks);
    else updateRole.mutate({ id: role.id, ...input }, callbacks);
  };

  return (
    <Dialog open onOpenChange={(open) => { if (!open) onClose(); }}>
      <DialogContent className="max-h-[80vh] max-w-4xl overflow-y-auto" aria-describedby={undefined}>
        <DialogHeader>
          <DialogTitle>{role === null ? t('settings.roles.createNewRole') : t('settings.roles.editRole')}</DialogTitle>
        </DialogHeader>

        <div className="space-y-6">
          <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
            <div className="space-y-2">
              <Label htmlFor={`${fieldId}-name`}>{t('settings.roles.roleName')}</Label>
              <Input
                id={`${fieldId}-name`}
                value={name}
                placeholder={t('settings.roles.roleNamePlaceholder')}
                required
                onChange={(event) => { setName(event.target.value); }}
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor={`${fieldId}-description`}>{t('settings.tickets.descriptionOptional')}</Label>
              <Input
                id={`${fieldId}-description`}
                value={description}
                placeholder={t('settings.roles.roleDescriptionPlaceholder')}
                onChange={(event) => { setDescription(event.target.value); }}
              />
            </div>
          </div>

          <div className="space-y-4">
            <h4 className="font-medium">{t('settings.roles.permissions')}</h4>
            <div className="divide-y">
              {catalog.categories.map((entry) => (
                <CategoryPermissions
                  key={entry.category}
                  category={entry.category}
                  permissions={entry.permissions}
                  grants={catalog.grants}
                  held={permissions}
                  canGrant={canGrant}
                  onChange={setPermissions}
                />
              ))}
            </div>
          </div>
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{t('common.cancel')}</Button>
          <Button onClick={save} disabled={saving || !valid}>
            {saving ? t('common.saving') : role === null ? t('settings.roles.createRole') : t('common.save')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
