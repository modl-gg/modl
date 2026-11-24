import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Input } from '@/components/ui/input';
import { Form, FormControl, FormField, FormItem, FormLabel, FormMessage } from '@/components/ui/form';
import { useClaimSetup } from '@/hooks/data/setup-claim';
import { setupCode, signInEmail } from '@/lib/form-schemas';
import { SetupCodeField } from './SetupCodeField';
import { SessionNotKeptNotice, SetupCodeRejectionNotice } from './SetupCodeFeedback';
import { SETUP_CODE_FORM_ID, SetupCodeScreen, SetupCodeSubmit } from './SetupCodeScreen';
import { useSetupCodeSignIn } from './use-setup-code-sign-in';

export function ClaimForm() {
  const { t } = useTranslation();
  const claimSetup = useClaimSetup();
  const { signIn, shownRejection, sessionLost, coolingDown } = useSetupCodeSignIn();

  const claimSchema = useMemo(() => z.object({
    code: setupCode(t('pages.setup.code.format')),
    email: signInEmail(t('pages.setup.claim.invalidEmail')),
  }), [t]);

  type ClaimValues = z.infer<typeof claimSchema>;

  const form = useForm<ClaimValues>({
    resolver: zodResolver(claimSchema),
    defaultValues: { code: '', email: '' },
  });

  const onSubmit = async (values: ClaimValues) => {
    const rejection = await signIn(async () => claimSetup.mutateAsync(values));
    if (rejection?.kind === 'invalid-code') {
      form.setError('code', { message: t('pages.setup.code.invalid') }, { shouldFocus: true });
    }
  };

  if (sessionLost) {
    return (
      <SetupCodeScreen phase="unclaimed" submit={null}>
        <SessionNotKeptNotice titleKey="pages.setup.claim.claimedTitle" descriptionKey="pages.setup.claim.claimedSignIn" />
      </SetupCodeScreen>
    );
  }

  return (
    <SetupCodeScreen phase="unclaimed" submit={<SetupCodeSubmit pending={form.formState.isSubmitting} disabled={coolingDown || !form.formState.isValid} />}>
      <Form {...form}>
        <form id={SETUP_CODE_FORM_ID} onSubmit={(event) => { void form.handleSubmit(onSubmit)(event); }} className="space-y-4" noValidate>
          <FormField
            control={form.control}
            name="code"
            render={({ field }) => <SetupCodeField field={field} />}
          />
          <FormField
            control={form.control}
            name="email"
            render={({ field }) => (
              <FormItem>
                <FormLabel>{t('pages.setup.claim.ownerEmail')}</FormLabel>
                <FormControl>
                  <Input {...field} type="email" autoComplete="email" placeholder={t('auth.emailPlaceholder')} />
                </FormControl>
                <FormMessage />
              </FormItem>
            )}
          />
          {shownRejection && <SetupCodeRejectionNotice {...shownRejection} />}
        </form>
      </Form>
    </SetupCodeScreen>
  );
}
