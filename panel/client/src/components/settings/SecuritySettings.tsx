import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Loader2, LogOut } from 'lucide-react';
import { LoadingSpinner } from '@/components/ui/loading-spinner';
import { Button } from '@/components/ui/button';
import { IconAction } from '@/components/ui/icon-action';
import { StatusBadge } from '@/components/ui/status-badge';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog';
import { useToast } from '@/hooks/use-toast';
import { useAuth } from '@/hooks/use-auth';
import { usePanelSessions, useRevokePanelSession, type PanelSession } from '@/hooks/data/account';
import PasskeySettings from './PasskeySettings';
import { AuthenticatorCard } from './AuthenticatorCard';
import { PasswordCard } from './PasswordCard';
import { SettingsSections } from './layout/SettingsSections';

function detectBrowser(ua: string): string | null {
  if (ua.includes('Edg')) return 'Edge';
  if (ua.includes('Chrome')) return 'Chrome';
  if (ua.includes('Firefox')) return 'Firefox';
  if (ua.includes('Safari')) return 'Safari';
  return null;
}

function detectOperatingSystem(ua: string): string | null {
  if (ua.includes('Windows')) return 'Windows';
  if (ua.includes('Mac')) return 'macOS';
  if (ua.includes('Linux')) return 'Linux';
  if (ua.includes('Android')) return 'Android';
  if (/iPhone|iPad/.test(ua)) return 'iOS';
  return null;
}

function SessionsSection() {
  const { t } = useTranslation();
  const { toast } = useToast();
  const { logout, signOutAllSessions } = useAuth();
  const { data: sessions = [], isLoading: loading, isError: loadError } = usePanelSessions();
  const revokeSession = useRevokePanelSession();
  const [pendingSession, setPendingSession] = useState<PanelSession | null>(null);
  const [signAllOutOpen, setSignAllOutOpen] = useState(false);
  const removingId = revokeSession.isPending ? revokeSession.variables : null;

  const confirmRemoveSession = () => {
    const session = pendingSession;
    setPendingSession(null);
    if (!session) return;

    if (session.isCurrent) {
      logout();
      return;
    }

    revokeSession.mutate(session.id, {
      onError: () => {
        toast({ title: t('settings.sessions.removeFailed'), variant: 'destructive' });
      },
    });
  };

  return (
    <section className="space-y-3">
      <h3 className="text-sm font-semibold">{t('settings.sessions.title')}</h3>

      {loading ? (
        <LoadingSpinner className="h-5 w-5 text-muted-foreground" />
      ) : loadError ? (
        <p className="text-sm text-destructive-text">{t('settings.sessions.loadFailed')}</p>
      ) : sessions.length === 0 ? (
        <p className="text-sm text-muted-foreground">{t('settings.sessions.empty')}</p>
      ) : (
        <div className="space-y-2">
          {sessions.map((s) => {
            const ua = s.userAgent ?? '';
            const browser = detectBrowser(ua) ?? t('settings.sessions.unknownBrowser');
            const os = detectOperatingSystem(ua) ?? t('settings.sessions.unknownOs');
            const added = s.createdAt
              ? new Date(s.createdAt).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' })
              : '-';
            return (
              <div key={s.id} className="flex items-center gap-3 rounded-md border px-3 py-2">
                <div className="flex-1 min-w-0">
                  <div className="flex flex-wrap items-center gap-2">
                    <p className="text-sm font-medium">{t('settings.sessions.deviceSummary', { browser, os })}</p>
                    {s.isCurrent && <StatusBadge intent="info">{t('settings.sessions.current')}</StatusBadge>}
                  </div>
                  <p className="text-xs text-muted-foreground truncate">
                    {t('settings.sessions.sessionDetails', { ip: s.ipAddress ?? t('settings.sessions.unknownIp'), added })}
                  </p>
                </div>
                <IconAction
                  icon={removingId === s.id ? Loader2 : LogOut}
                  iconClassName={removingId === s.id ? 'animate-spin' : undefined}
                  label={t('settings.sessions.signOutSession')}
                  destructive
                  className="shrink-0"
                  disabled={removingId === s.id}
                  onClick={() => { setPendingSession(s); }}
                />
              </div>
            );
          })}
        </div>
      )}

      <Button
        variant="outline"
        size="sm"
        onClick={() => setSignAllOutOpen(true)}
        disabled={loading || sessions.length === 0}
      >
        {t('settings.sessions.signAllOut')}
      </Button>

      <AlertDialog open={pendingSession !== null} onOpenChange={(open) => { if (!open) setPendingSession(null); }}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t('settings.sessions.removeTitle')}</AlertDialogTitle>
            <AlertDialogDescription>
              {pendingSession?.isCurrent
                ? t('settings.sessions.removeCurrentDescription')
                : t('settings.sessions.removeDescription')}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>{t('common.cancel')}</AlertDialogCancel>
            <AlertDialogAction onClick={confirmRemoveSession}>{t('settings.sessions.removeAction')}</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      <AlertDialog open={signAllOutOpen} onOpenChange={setSignAllOutOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t('settings.sessions.signAllOutTitle')}</AlertDialogTitle>
            <AlertDialogDescription>{t('settings.sessions.signAllOutDescription')}</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>{t('common.cancel')}</AlertDialogCancel>
            <AlertDialogAction onClick={() => signOutAllSessions()}>{t('settings.sessions.signAllOut')}</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </section>
  );
}

export default function SecuritySettings() {
  return (
    <SettingsSections>
      <PasswordCard />
      <AuthenticatorCard />
      <PasskeySettings />
      <SessionsSection />
    </SettingsSections>
  );
}
