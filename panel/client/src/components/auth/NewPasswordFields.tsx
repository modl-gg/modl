import { useId, type KeyboardEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import type { NewPasswordFieldsControl } from '@/hooks/use-new-password';
import { isMismatchShown, shownPasswordProblem } from '@/lib/new-password-entry';
import type { PasswordProblem } from '@/lib/password-policy';
import { InsecurePasswordNotice } from './InsecurePasswordNotice';

const PROBLEM_KEYS: Record<PasswordProblem, string> = {
  'too-short': 'auth.passwordTooShort',
  'too-long': 'auth.passwordTooLong',
};

interface NewPasswordFieldsProps {
  control: NewPasswordFieldsControl;
  account?: string | undefined;
}

export function NewPasswordFields({ control, account }: NewPasswordFieldsProps) {
  const { t } = useTranslation();
  const id = useId();
  const { entry, dispatch } = control;
  const problem = shownPasswordProblem(entry);
  const mismatch = isMismatchShown(entry);
  const reportEnter = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key === 'Enter') dispatch({ type: 'enter-pressed' });
  };

  return (
    <div className="space-y-3">
      {account !== undefined && <input type="hidden" name="username" autoComplete="username" value={account} readOnly />}
      <InsecurePasswordNotice />
      <div className="space-y-2">
        <Label htmlFor={`${id}-password`}>{t('auth.password')}</Label>
        <Input
          id={`${id}-password`}
          type="password"
          autoComplete="new-password"
          value={entry.password}
          onChange={(event) => { dispatch({ type: 'password-changed', value: event.target.value }); }}
          onBlur={() => { dispatch({ type: 'password-left' }); }}
          onKeyDown={reportEnter}
          aria-describedby={`${id}-rules`}
          aria-invalid={problem !== null}
        />
        <p id={`${id}-rules`} aria-live="polite" className={problem === null ? 'text-xs text-muted-foreground' : 'text-xs text-destructive-text'}>
          {t(problem === null ? 'auth.passwordRules' : PROBLEM_KEYS[problem])}
        </p>
      </div>
      <div>
        <div className="space-y-2">
          <Label htmlFor={`${id}-confirmation`}>{t('auth.confirmPassword')}</Label>
          <Input
            id={`${id}-confirmation`}
            type="password"
            autoComplete="new-password"
            value={entry.confirmation}
            onChange={(event) => { dispatch({ type: 'confirmation-changed', value: event.target.value }); }}
            onFocus={() => { dispatch({ type: 'confirmation-entered' }); }}
            onBlur={() => { dispatch({ type: 'confirmation-left' }); }}
            onKeyDown={reportEnter}
            aria-describedby={mismatch ? `${id}-mismatch` : undefined}
            aria-invalid={mismatch}
          />
        </div>
        <div aria-live="polite">
          {mismatch && <p id={`${id}-mismatch`} className="mt-2 text-xs text-destructive-text">{t('auth.passwordMismatch')}</p>}
        </div>
      </div>
    </div>
  );
}
