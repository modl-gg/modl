import { AiProviderSettings } from '@/components/settings/ai/AiProviderSettings';
import { useAiSettings } from '@/hooks/data/ai';
import { StepChoice, useStepChoice, type StepOption } from './StepChoice';
import type { SetupStepProps } from './step-props';

type AiChoice = 'on' | 'none';

const OPTIONS: readonly StepOption<AiChoice>[] = [
  { value: 'on', labelKey: 'pages.setup.ai.optionOn' },
  { value: 'none', labelKey: 'pages.setup.ai.optionNone' },
];

export function AiStep({ done }: SetupStepProps) {
  const settings = useAiSettings(true);
  const saved = (settings.data?.activeProvider ?? null) !== null ? 'on' : null;
  const choice = useStepChoice<AiChoice>({ step: 'ai', options: OPTIONS, saved, done, none: 'none', configured: true });

  return (
    <div className="space-y-6">
      <StepChoice options={OPTIONS} {...choice} />
      {choice.value === 'on' && <AiProviderSettings showLimits={false} />}
    </div>
  );
}
