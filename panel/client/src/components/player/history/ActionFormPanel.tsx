import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Button } from '@/components/ui/button';

interface ActionFormPanelProps {
  readonly title: string;
  readonly submitLabel: ReactNode;
  readonly canSubmit: boolean;
  readonly onSubmit: () => void;
  readonly onCancel: () => void;
  readonly children: ReactNode;
}

export function ActionFormPanel({ title, submitLabel, canSubmit, onSubmit, onCancel, children }: ActionFormPanelProps) {
  const { t } = useTranslation();
  return (
    <div className="space-y-2 rounded-lg border p-3">
      <p className="text-sm font-medium">{title}</p>
      {children}
      <div className="flex justify-end gap-2">
        <Button variant="outline" size="sm" onClick={onCancel}>
          {t('common.cancel')}
        </Button>
        <Button size="sm" disabled={!canSubmit} onClick={onSubmit}>
          {submitLabel}
        </Button>
      </div>
    </div>
  );
}
