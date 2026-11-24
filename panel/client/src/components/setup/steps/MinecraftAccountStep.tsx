import MinecraftAccountSettings from '@/components/settings/MinecraftAccountSettings';
import { useAuth } from '@/hooks/use-auth';
import { StepChoice, useStepChoice, type StepOption } from './StepChoice';
import type { SetupStepProps } from './step-props';

type LinkChoice = 'link' | 'none';

const OPTIONS: readonly StepOption<LinkChoice>[] = [
  { value: 'link', labelKey: 'pages.setup.minecraftAccount.optionLink' },
  { value: 'none', labelKey: 'pages.setup.minecraftAccount.optionNone' },
];

export function MinecraftAccountStep({ done }: SetupStepProps) {
  const { user } = useAuth();
  const linked = (user?.minecraftAccount ?? null) !== null;
  const choice = useStepChoice<LinkChoice>({ step: 'minecraft-account', options: OPTIONS, saved: linked ? 'link' : null, done, none: 'none' });

  return (
    <div className="space-y-6">
      <StepChoice options={OPTIONS} {...choice} />
      {choice.value === 'link' && <MinecraftAccountSettings />}
    </div>
  );
}
