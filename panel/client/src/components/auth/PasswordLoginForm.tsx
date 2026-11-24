import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Loader2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Form, FormControl, FormField, FormItem, FormLabel, FormMessage } from '@/components/ui/form';
import { Input } from '@/components/ui/input';
import { Notice } from '@/components/ui/notice';
import { useAuth } from '@/hooks/use-auth';
import { useAuthFailureMessage } from '@/hooks/use-auth-failure-message';
import { usePasskeySignIn } from '@/hooks/use-passkey-sign-in';
import { signInEmail } from '@/lib/form-schemas';
import { arePasskeysAvailable } from '@/lib/passkeys';
import { InsecurePasswordNotice } from './InsecurePasswordNotice';

export function PasswordLoginForm({ onSignedIn }: { onSignedIn: () => void }) {
  const { t } = useTranslation();
  const { loginWithPassword } = useAuth();
  const failureMessage = useAuthFailureMessage();
  const passkey = usePasskeySignIn(onSignedIn);
  const [failure, setFailure] = useState<string | null>(null);
  const schema = useMemo(() => z.object({
    email: signInEmail(t('auth.invalidEmail')),
    password: z.string().min(1, { error: t('auth.passwordNeeded') }),
  }), [t]);

  type LoginValues = z.infer<typeof schema>;

  const form = useForm<LoginValues>({ resolver: zodResolver(schema), defaultValues: { email: '', password: '' } });

  const onSubmit = async ({ email, password }: LoginValues) => {
    setFailure(null);
    try {
      if (await loginWithPassword(email, password) === 'signed-in') onSignedIn();
    } catch (error) {
      setFailure(failureMessage(error));
      form.setValue('password', '');
    }
  };

  return (
    <Form {...form}>
      <form onSubmit={(event) => { void form.handleSubmit(onSubmit)(event); }} className="space-y-4" noValidate>
        <InsecurePasswordNotice />
        <FormField
          control={form.control}
          name="email"
          render={({ field }) => (
            <FormItem>
              <FormLabel>{t('auth.email')}</FormLabel>
              <FormControl>
                <Input {...field} type="email" autoComplete="username" placeholder={t('auth.emailPlaceholder')} />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />
        <FormField
          control={form.control}
          name="password"
          render={({ field }) => (
            <FormItem>
              <FormLabel>{t('auth.password')}</FormLabel>
              <FormControl>
                <Input {...field} type="password" autoComplete="current-password" />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />
        {failure !== null && <Notice variant="error" announce>{failure}</Notice>}
        <Button type="submit" className="w-full" disabled={form.formState.isSubmitting}>
          {form.formState.isSubmitting && <Loader2 className="mr-2 h-4 w-4 animate-spin" aria-hidden="true" />}
          {t('auth.signIn')}
        </Button>
        {arePasskeysAvailable() && (
          <Button
            type="button"
            variant="outline"
            className="w-full"
            disabled={passkey.pending}
            onClick={() => { void passkey.signIn(form.getValues().email.trim()); }}
          >
            {t('auth.signInPasskey')}
          </Button>
        )}
        <p className="text-xs text-muted-foreground">{t('auth.forgotPassword')}</p>
      </form>
    </Form>
  );
}
