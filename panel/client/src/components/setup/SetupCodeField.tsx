import { type ChangeEvent, type Ref } from 'react';
import { useTranslation } from 'react-i18next';
import { FormControl, FormItem, FormMessage } from '@/components/ui/form';
import { Input } from '@/components/ui/input';
import { SETUP_CODE_INPUT_MAX_LENGTH } from '@/lib/setup-code';
import { SETUP_TITLE_ID } from './setup-title';

interface SetupCodeInputBinding {
  name: string;
  value: string;
  onChange: (event: ChangeEvent<HTMLInputElement>) => void;
  onBlur: () => void;
  ref: Ref<HTMLInputElement>;
}

interface SetupCodeFieldProps {
  field: SetupCodeInputBinding;
  disabled?: boolean;
}

export function SetupCodeField({ field, disabled }: SetupCodeFieldProps) {
  const { t } = useTranslation();

  return (
    <FormItem>
      <FormControl>
        <Input
          {...field}
          disabled={disabled}
          aria-labelledby={SETUP_TITLE_ID}
          autoComplete="one-time-code"
          autoCapitalize="characters"
          autoCorrect="off"
          spellCheck={false}
          enterKeyHint="go"
          maxLength={SETUP_CODE_INPUT_MAX_LENGTH}
          placeholder={t('pages.setup.code.placeholder')}
          className="font-mono uppercase tracking-widest"
        />
      </FormControl>
      <FormMessage />
    </FormItem>
  );
}
