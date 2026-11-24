import { useTranslation } from 'react-i18next';
import { Label } from '@/components/ui/label';
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group';
import type { SignInMethodId } from '@/hooks/data/sign-in';

const METHOD_LABEL_KEYS: Record<SignInMethodId, string> = {
  'email-code': 'settings.signIn.emailCode',
  password: 'settings.signIn.password',
};

const METHOD_DESCRIPTION_KEYS: Record<SignInMethodId, string> = {
  'email-code': 'pages.setup.account.emailCodeDescription',
  password: 'pages.setup.account.passwordDescription',
};

const METHODS = ['email-code', 'password'] as const satisfies readonly SignInMethodId[];

interface SignInMethodFieldProps {
  value: SignInMethodId;
  onChange: (method: SignInMethodId) => void;
  disabled?: boolean;
  described?: boolean;
}

export function SignInMethodField({ value, onChange, disabled = false, described = false }: SignInMethodFieldProps) {
  const { t } = useTranslation();

  const select = (selected: string) => {
    const method = METHODS.find((candidate) => candidate === selected);
    if (method !== undefined) onChange(method);
  };

  return (
    <RadioGroup value={value} onValueChange={select} disabled={disabled} aria-label={t('settings.signIn.title')}>
      {METHODS.map((method) => {
        const itemId = `sign-in-method-${method}`;
        const descriptionId = `${itemId}-description`;
        return (
          <div key={method} className={described ? 'flex items-start gap-2' : 'flex items-center gap-2'}>
            <RadioGroupItem
              id={itemId}
              value={method}
              className={described ? 'mt-0.5' : undefined}
              aria-describedby={described ? descriptionId : undefined}
            />
            <div className="space-y-1">
              <Label htmlFor={itemId}>{t(METHOD_LABEL_KEYS[method])}</Label>
              {described && <p id={descriptionId} className="text-sm text-muted-foreground">{t(METHOD_DESCRIPTION_KEYS[method])}</p>}
            </div>
          </div>
        );
      })}
    </RadioGroup>
  );
}
