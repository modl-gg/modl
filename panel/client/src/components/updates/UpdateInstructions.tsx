import { useTranslation } from 'react-i18next';
import { CopyButton } from '@/components/ui/copy-button';
import { updateInstructions } from '@/lib/update-instructions';
import type { AvailableUpdate, UpdateDeployment } from '@/hooks/data/updates';

interface UpdateInstructionsProps {
  deployment: UpdateDeployment;
  release: AvailableUpdate;
}

export function UpdateInstructions({ deployment, release }: UpdateInstructionsProps) {
  const { t } = useTranslation();
  const steps = updateInstructions(deployment, release);
  if (steps.length === 0) return null;
  return (
    <ol className="space-y-3 text-sm">
      {steps.map((step) => (
        <li key={step.key} className="space-y-1">
          <p>{t(`settings.updates.instructions.${step.key}`, step.values)}</p>
          {step.command !== null && (
            <div className="flex items-center gap-2">
              <code className="flex-1 rounded bg-muted px-2 py-1 font-mono text-xs break-all">{step.command}</code>
              <CopyButton value={step.command} label={t('settings.updates.copyCommand')} failureMessage={t('settings.updates.copyFailed')} />
            </div>
          )}
        </li>
      ))}
    </ol>
  );
}
