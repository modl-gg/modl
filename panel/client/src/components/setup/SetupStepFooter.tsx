import { type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Loader2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Notice } from '@/components/ui/notice';

interface SetupStepFooterProps {
  busy: boolean;
  failed: boolean;
  onBack: (() => void) | null;
  children: ReactNode;
}

export function SetupStepFooter({ busy, failed, onBack, children }: SetupStepFooterProps) {
  const { t } = useTranslation();

  return (
    <div className="space-y-2">
      {failed && <Notice variant="error" announce>{t('pages.setup.nav.saveFailed')}</Notice>}
      <div className="flex items-center gap-2">
        {onBack && (
          <Button type="button" variant="ghost" onClick={onBack} disabled={busy}>
            {t('pages.setup.nav.back')}
          </Button>
        )}
        <div className="ml-auto flex items-center gap-2">{children}</div>
      </div>
    </div>
  );
}

interface SetupActionButtonProps {
  pending: boolean;
  busy: boolean;
  onClick: () => void;
  children: ReactNode;
}

export function SetupActionButton({ pending, busy, onClick, children }: SetupActionButtonProps) {
  return (
    <Button type="button" onClick={onClick} disabled={busy}>
      {pending && <Loader2 className="mr-2 h-4 w-4 animate-spin" aria-hidden="true" />}
      {children}
    </Button>
  );
}
