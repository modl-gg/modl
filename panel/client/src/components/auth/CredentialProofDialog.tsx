import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Notice } from '@/components/ui/notice';
import type { CredentialProofRequest } from '@/hooks/use-credential-proof';
import type { ProofKind } from '@/lib/reauthentication';

const PROOF_COPY: Record<ProofKind, { descriptionKey: string; labelKey: string; autoComplete: string }> = {
  password: { descriptionKey: 'settings.reauth.password', labelKey: 'settings.password.current', autoComplete: 'current-password' },
  code: { descriptionKey: 'settings.reauth.code', labelKey: 'pages.secondFactor.codeLabel', autoComplete: 'one-time-code' },
  'email-code': { descriptionKey: 'settings.reauth.emailCode', labelKey: 'settings.emailCodePlaceholder', autoComplete: 'one-time-code' },
};

function ProofForm({ request }: { request: CredentialProofRequest }) {
  const { t } = useTranslation();
  const [value, setValue] = useState('');
  const copy = PROOF_COPY[request.kind];

  return (
    <form
      className="space-y-4"
      onSubmit={(event) => {
        event.preventDefault();
        void request.confirm(value);
      }}
    >
      <DialogHeader>
        <DialogTitle>{t('settings.reauth.title')}</DialogTitle>
        <DialogDescription>{t(copy.descriptionKey)}</DialogDescription>
      </DialogHeader>
      <Input
        type={request.kind === 'password' ? 'password' : 'text'}
        autoComplete={copy.autoComplete}
        aria-label={t(copy.labelKey)}
        value={value}
        onChange={(event) => setValue(event.target.value)}
      />
      {request.rejection !== null && <Notice variant="error" announce>{request.rejection}</Notice>}
      <p className="text-xs text-muted-foreground">{t('settings.reauth.signInAgain')}</p>
      <DialogFooter>
        <Button type="button" variant="outline" onClick={request.cancel}>{t('common.cancel')}</Button>
        <Button type="submit" disabled={request.confirming || value === ''}>{t('pages.secondFactor.submit')}</Button>
      </DialogFooter>
    </form>
  );
}

export function CredentialProofDialog({ request }: { request: CredentialProofRequest | null }) {
  return (
    <Dialog open={request !== null} onOpenChange={(open) => { if (!open) request?.cancel(); }}>
      <DialogContent>{request !== null && <ProofForm request={request} />}</DialogContent>
    </Dialog>
  );
}
