import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Form, FormField } from '@/components/ui/form';
import { SecondFactorChallenge } from '@/components/auth/SecondFactorChallenge';
import { useOwnerSignInWithCode } from '@/hooks/data/setup-claim';
import { setupCode } from '@/lib/form-schemas';
import { SetupCodeField } from './SetupCodeField';
import { SessionNotKeptNotice, SetupCodeRejectionNotice } from './SetupCodeFeedback';
import { SETUP_CODE_FORM_ID, SetupCodeScreen, SetupCodeSubmit } from './SetupCodeScreen';
import { useSetupCodeSignIn } from './use-setup-code-sign-in';

export function OwnerSignInForm() {
  const { t } = useTranslation();
  const ownerSignIn = useOwnerSignInWithCode();
  const { signIn, shownRejection, sessionLost, coolingDown, challenge, continueSetup } = useSetupCodeSignIn();

  const signInSchema = useMemo(() => z.object({
    code: setupCode(t('pages.setup.code.format')),
  }), [t]);

  type SignInValues = z.infer<typeof signInSchema>;

  const form = useForm<SignInValues>({
    resolver: zodResolver(signInSchema),
    defaultValues: { code: '' },
  });

  const onSubmit = async (values: SignInValues) => {
    const rejection = await signIn(async () => ownerSignIn.mutateAsync(values.code));
    if (rejection?.kind === 'invalid-code') {
      form.setError('code', { message: t('pages.setup.code.invalid') }, { shouldFocus: true });
    }
  };

  if (sessionLost) {
    return (
      <SetupCodeScreen phase="owner-sign-in" submit={null}>
        <SessionNotKeptNotice titleKey="pages.setup.ownerSignIn.signedInTitle" descriptionKey="pages.setup.ownerSignIn.sessionLost" />
      </SetupCodeScreen>
    );
  }

  if (challenge) {
    return (
      <SetupCodeScreen phase="owner-sign-in" submit={null}>
        <SecondFactorChallenge challenge={challenge} onSignedIn={continueSetup} />
      </SetupCodeScreen>
    );
  }

  return (
    <SetupCodeScreen phase="owner-sign-in" submit={<SetupCodeSubmit pending={form.formState.isSubmitting} disabled={coolingDown || !form.formState.isValid} />}>
      <Form {...form}>
        <form id={SETUP_CODE_FORM_ID} onSubmit={(event) => { void form.handleSubmit(onSubmit)(event); }} className="space-y-4" noValidate>
          <FormField
            control={form.control}
            name="code"
            render={({ field }) => <SetupCodeField field={field} />}
          />
          {shownRejection && <SetupCodeRejectionNotice {...shownRejection} />}
        </form>
      </Form>
    </SetupCodeScreen>
  );
}
