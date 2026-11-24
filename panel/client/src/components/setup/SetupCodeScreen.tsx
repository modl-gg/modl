import { type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Loader2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import type { SetupPhaseId } from '@/hooks/data/setup';
import { SETUP_CODE_SCREEN } from '@/lib/setup-steps';
import { SetupShell } from './SetupShell';
import { SetupStepper } from './SetupStepper';
import { setupCodeIntroKey } from './setup-title';

export const SETUP_CODE_FORM_ID = 'setup-code-form';

interface SetupCodeScreenProps {
  phase: SetupPhaseId;
  submit: ReactNode;
  children: ReactNode;
}

export function SetupCodeScreen({ phase, submit, children }: SetupCodeScreenProps) {
  const { t } = useTranslation();
  return (
    <SetupShell
      screen={SETUP_CODE_SCREEN}
      title={t('pages.setup.code.title')}
      intro={[t('pages.setup.code.introWizard'), t(setupCodeIntroKey(phase))]}
      stepper={<SetupStepper progress={[]} current={SETUP_CODE_SCREEN} />}
      footer={submit}
    >
      <div className="max-w-md">{children}</div>
    </SetupShell>
  );
}

export function SetupCodeSubmit({ pending, disabled }: { pending: boolean; disabled: boolean }) {
  const { t } = useTranslation();

  return (
    <div className="flex justify-end">
      <Button type="submit" form={SETUP_CODE_FORM_ID} disabled={pending || disabled}>
        {pending && <Loader2 className="mr-2 h-4 w-4 animate-spin" aria-hidden="true" />}
        {t('pages.setup.nav.continue')}
      </Button>
    </div>
  );
}
