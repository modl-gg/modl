import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Loader2 } from 'lucide-react';
import { QRCodeSVG } from 'qrcode.react';
import { Button } from '@/components/ui/button';
import { CopyButton } from '@/components/ui/copy-button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Notice } from '@/components/ui/notice';
import { useConfirmTotp, useStartTotpEnrollment, type TotpEnrollment as PendingEnrollment } from '@/hooks/data/sign-in';
import { useAuthFailureMessage } from '@/hooks/use-auth-failure-message';
import { useCredentialProof } from '@/hooks/use-credential-proof';
import type { ProofKind } from '@/lib/reauthentication';
import { authFailureOf } from '@/lib/auth-errors';
import { apiErrorCode } from '@/lib/http-error';
import { CredentialProofDialog } from './CredentialProofDialog';
import { RecoveryCodesPanel } from './RecoveryCodesPanel';

const QR_CODE_SIZE = 192;
const QR_QUIET_ZONE_MODULES = 4;

interface TotpEnrollmentProps {
  proofKind: ProofKind;
  startLabel: string;
  onDone: () => void;
}

export function TotpEnrollment({ proofKind, startLabel, onDone }: TotpEnrollmentProps) {
  const { t } = useTranslation();
  const failureMessage = useAuthFailureMessage();
  const start = useStartTotpEnrollment();
  const confirm = useConfirmTotp();
  const [enrollment, setEnrollment] = useState<PendingEnrollment | null>(null);
  const [code, setCode] = useState('');
  const [codes, setCodes] = useState<readonly string[] | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const { run, request } = useCredentialProof((error) => setFailure(failureMessage(error)));

  const begin = async () => {
    setFailure(null);
    await run(proofKind, async (proof) => {
      const started = await start.mutateAsync(proof);
      setEnrollment(started);
      setCode('');
    });
  };

  const finish = async () => {
    setFailure(null);
    try {
      const issued = await confirm.mutateAsync(code);
      setCodes(issued);
    } catch (error) {
      setFailure(failureMessage(error));
      if (authFailureOf(apiErrorCode(error)) === 'enrollment-expired') setEnrollment(null);
    }
  };

  if (codes !== null) {
    return (
      <RecoveryCodesPanel
        codes={codes}
        onSaved={() => {
          setCodes(null);
          setEnrollment(null);
          onDone();
        }}
      />
    );
  }

  return (
    <div className="space-y-3">
      {failure !== null && <Notice variant="error" announce>{failure}</Notice>}
      {enrollment === null ? (
        <Button type="button" variant="outline" size="sm" onClick={() => { void begin(); }} disabled={start.isPending}>
          {startLabel}
        </Button>
      ) : (
        <form
          className="space-y-3 rounded-md border p-3"
          onSubmit={(event) => {
            event.preventDefault();
            void finish();
          }}
        >
          <p className="text-sm text-muted-foreground">{t('settings.authenticator.scan')}</p>
          <QRCodeSVG
            value={enrollment.keyUri}
            size={QR_CODE_SIZE}
            marginSize={QR_QUIET_ZONE_MODULES}
            title={t('settings.authenticator.qrCode')}
            className="max-w-full rounded border"
          />
          <div className="flex items-center gap-2">
            <code className="flex-1 break-all rounded border bg-surface-2 px-3 py-2 font-mono text-sm" aria-label={t('settings.authenticator.key')}>
              {enrollment.secret}
            </code>
            <CopyButton
              value={enrollment.secret}
              label={t('settings.authenticator.copyKey')}
              failureMessage={t('settings.authenticator.copyFailed')}
            />
          </div>
          <a className="text-sm text-primary underline" href={enrollment.keyUri}>{t('settings.authenticator.openApp')}</a>
          <div className="space-y-2">
            <Label htmlFor="totp-confirmation">{t('settings.authenticator.confirmCode')}</Label>
            <Input
              id="totp-confirmation"
              inputMode="numeric"
              autoComplete="one-time-code"
              maxLength={16}
              value={code}
              onChange={(event) => setCode(event.target.value)}
            />
          </div>
          <div className="flex gap-2">
            <Button type="submit" size="sm" disabled={confirm.isPending || code.trim().length < 6}>
              {confirm.isPending && <Loader2 className="mr-2 h-4 w-4 animate-spin" aria-hidden="true" />}
              {t('pages.secondFactor.submit')}
            </Button>
            <Button type="button" size="sm" variant="ghost" onClick={() => setEnrollment(null)}>{t('common.cancel')}</Button>
          </div>
        </form>
      )}
      <CredentialProofDialog request={request} />
    </div>
  );
}
