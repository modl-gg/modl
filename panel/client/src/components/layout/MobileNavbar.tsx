import type { ReactNode } from 'react';
import { Link, useLocation } from 'wouter';
import { FileText, Home, Search, Settings, Ticket } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { isNavPathActive } from '@/lib/nav-active';
import { LOOKUP_PATH } from '@/lib/player-links';
import { SETTINGS_ROOT_PATH } from '@/lib/settings-access';
import { usePermissions, PERMISSIONS } from '@/hooks/use-permissions';
import { cn } from '@/lib/utils';

interface MobileNavItem {
  readonly icon: ReactNode;
  readonly label: string;
  readonly path: string;
  readonly permission?: string;
}

function NavItem({ item, isActive }: { readonly item: MobileNavItem; readonly isActive: boolean }) {
  return (
    <Link
      href={item.path}
      className={cn('flex flex-col items-center justify-center', isActive ? 'text-primary' : 'text-muted-foreground')}
      aria-current={isActive ? 'page' : undefined}
    >
      <div className="mb-0.5">{item.icon}</div>
      <span className="text-[10px]">{item.label}</span>
    </Link>
  );
}

const MobileNavbar = () => {
  const { t } = useTranslation();
  const [location] = useLocation();
  const { hasPermission, isPermissionsLoading } = usePermissions();

  const allNavItems: readonly MobileNavItem[] = [
    { icon: <Home className="h-5 w-5" />, label: t('nav.home'), path: '/panel' },
    { icon: <Search className="h-5 w-5" />, label: t('nav.lookup'), path: LOOKUP_PATH },
    { icon: <Ticket className="h-5 w-5" />, label: t('nav.tickets'), path: '/panel/tickets' },
    { icon: <FileText className="h-5 w-5" />, label: t('nav.audit'), path: '/panel/audit', permission: PERMISSIONS.ADMIN_AUDIT_VIEW },
    { icon: <Settings className="h-5 w-5" />, label: t('nav.settings'), path: SETTINGS_ROOT_PATH },
  ];
  const navItems = allNavItems.filter((item) => item.permission === undefined || (!isPermissionsLoading && hasPermission(item.permission)));

  return (
    <nav className="fixed bottom-0 left-0 right-0 z-50 border-t border-border bg-background pb-safe shadow-md">
      <div className="grid h-16" style={{ gridTemplateColumns: `repeat(${String(navItems.length)}, minmax(0, 1fr))` }}>
        {navItems.map((item) => (
          <NavItem key={item.path} item={item} isActive={isNavPathActive(item.path, location)} />
        ))}
      </div>
    </nav>
  );
};

export default MobileNavbar;
