import MailSettingsCard from '@/components/settings/MailSettingsCard';
import { useMailSettings } from '@/hooks/data/settings';
import { StepChoice, useStepChoice, type StepOption } from './StepChoice';
import type { SetupStepProps } from './step-props';

type EmailChoice = 'smtp' | 'none';

const OPTIONS: readonly StepOption<EmailChoice>[] = [
  { value: 'smtp', labelKey: 'pages.setup.email.optionSmtp' },
  { value: 'none', labelKey: 'pages.setup.email.optionNone' },
];

export function EmailStep({ done }: SetupStepProps) {
  const mail = useMailSettings();
  const saved = (mail.data?.settings ?? null) !== null ? 'smtp' : null;
  const choice = useStepChoice<EmailChoice>({ step: 'email', options: OPTIONS, saved, done, none: 'none', configured: true });

  return (
    <div className="space-y-6">
      <StepChoice options={OPTIONS} {...choice} />
      {choice.value === 'smtp' && <MailSettingsCard />}
    </div>
  );
}
