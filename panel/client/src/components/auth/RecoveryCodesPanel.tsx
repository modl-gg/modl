import { useTranslation } from 'react-i18next';
import { Button } from '@/components/ui/button';
import { CopyButton } from '@/components/ui/copy-button';

export function RecoveryCodesPanel({ codes, onSaved }: { codes: readonly string[]; onSaved: () => void }) {
  const { t } = useTranslation();

  return (
    <div className="space-y-3 rounded-md border p-3">
      <p className="text-sm font-medium">{t('settings.authenticator.codesTitle')}</p>
      <p className="text-sm text-muted-foreground">{t('settings.authenticator.codesDesc')}</p>
      <ul className="grid grid-cols-2 gap-1 font-mono text-sm">
        {codes.map((code) => <li key={code}>{code}</li>)}
      </ul>
      <div className="flex items-center gap-2">
        <CopyButton
          value={codes.join('\n')}
          label={t('settings.authenticator.copyCodes')}
          failureMessage={t('settings.authenticator.copyFailed')}
        />
        <Button type="button" size="sm" onClick={onSaved}>{t('settings.authenticator.codesSaved')}</Button>
      </div>
    </div>
  );
}
