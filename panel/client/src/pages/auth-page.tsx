import { useState, useEffect, useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useLocation, useSearch } from 'wouter';
import { z } from 'zod';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Loader2 } from 'lucide-react';
import { useAuth, type PasskeyLoginOptions } from '@/hooks/use-auth';
import { usePublicSettings } from '@/hooks/use-public-settings';
import { useSetupStatus } from '@/hooks/data/setup';
import { PANEL_HOME_PATH, readReturnPath } from '@/lib/return-path';
import { setupPath } from '@/lib/setup-steps';
import { arePasskeysAvailable } from '@/lib/passkeys';
import { Notice } from '@/components/ui/notice';
import { SignInBrand } from '@/components/brand/BrandLogo';
import { PasswordLoginForm } from '@/components/auth/PasswordLoginForm';
import { SecondFactorChallenge } from '@/components/auth/SecondFactorChallenge';
import { signInEmail } from '@/lib/form-schemas';
import { type PublicKeyCredentialRequestOptionsJSON } from '@simplewebauthn/browser';

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  Form,
  FormControl,
  FormDescription,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/components/ui/form";
import {
  Card,
  CardContent,
} from "@/components/ui/card";
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { codeDeliveryHintKey } from '@/lib/code-delivery';

interface LoginFormValues {
  email: string;
  code?: string | undefined;
}

interface PasskeyChallenge {
  challengeId: string;
  options: PublicKeyCredentialRequestOptionsJSON;
}

const NO_PASSKEYS: PasskeyLoginOptions = { hasPasskeys: false };

const AuthPage = () => {
  const [, setLocation] = useLocation();
  const { t } = useTranslation();
  const { data: publicSettings } = usePublicSettings();
  const { data: setupStatus } = useSetupStatus();
  const setupPhase = setupStatus?.phase;
  const search = useSearch();
  const landingPath = readReturnPath(search) ?? PANEL_HOME_PATH;
  const passkeysAvailable = arePasskeysAvailable();
  const emailDelivery = publicSettings?.emailDelivery === true;
  const [loginStep, setLoginStep] = useState<'email' | 'verification'>('email');
  const [verifyMethod, setVerifyMethod] = useState<'code' | 'passkey'>('code');
  const [passkeyAvailable, setPasskeyAvailable] = useState(false);
  const [passkeyChallenge, setPasskeyChallenge] = useState<PasskeyChallenge | null>(null);
  const [passkeyLoading, setPasskeyLoading] = useState(false);

  const loginSchema = useMemo(() => z.object({
    email: signInEmail(t('auth.invalidEmail')),
    code: z.string().optional(),
  }), [t]);
  const loginForm = useForm<LoginFormValues>({
    resolver: zodResolver(loginSchema),
    defaultValues: {
      email: "",
      code: "",
    },
  });
  const isSubmitting = loginForm.formState.isSubmitting;

  const { login, user, challenge, requestEmailVerification, checkPasskeyOptions, loginWithPasskey, loginWithDiscoverablePasskey } = useAuth();
  const [discoverableLoading, setDiscoverableLoading] = useState(false);
  const [resendLoading, setResendLoading] = useState(false);

  useEffect(() => {
    if (user) {
      setLocation(landingPath);
    }
  }, [user, setLocation, landingPath]);

  const handlePasskeyLogin = async () => {
    if (!passkeyChallenge) return;
    setPasskeyLoading(true);
    try {
      const success = await loginWithPasskey(passkeyChallenge.challengeId, passkeyChallenge.options);
      if (success) {
        setLocation(landingPath);
      }
    } finally {
      setPasskeyLoading(false);
    }
  };

  const handleDiscoverablePasskey = async () => {
    setDiscoverableLoading(true);
    try {
      const success = await loginWithDiscoverablePasskey();
      if (success) {
        setLocation(landingPath);
      }
    } finally {
      setDiscoverableLoading(false);
    }
  };

  const onLoginSubmit = async (values: LoginFormValues) => {
    if (loginStep === 'email') {
      // Check passkeys and send email code in parallel
      const [passkeyResult, emailResult] = await Promise.all([
        passkeysAvailable ? checkPasskeyOptions(values.email) : Promise.resolve(NO_PASSKEYS),
        requestEmailVerification(values.email),
      ]);

      if (emailResult) {
        if (passkeyResult.hasPasskeys) {
          setPasskeyAvailable(true);
          setPasskeyChallenge({ challengeId: passkeyResult.challengeId, options: passkeyResult.options });
          setVerifyMethod('passkey');
        } else {
          setPasskeyAvailable(false);
          setPasskeyChallenge(null);
          setVerifyMethod('code');
        }
        setLoginStep('verification');
      }
    } else {
      if (!values.code) {
        loginForm.setError('code', { message: t('auth.codeRequired') });
        return;
      }

      const outcome = await login(values.email, values.code);

      if (outcome === 'signed-in') {
        setLocation(landingPath);
      } else if (outcome === 'failed') {
        loginForm.setValue('code', '');
        loginForm.setError('code', { message: t('auth.invalidCode') });
        requestAnimationFrame(() => loginForm.setFocus('code'));
      } else {
        loginForm.setValue('code', '');
      }
    }
  };

  const handleResendCode = async () => {
    setResendLoading(true);
    try {
      const sent = await requestEmailVerification(loginForm.getValues().email);
      if (sent) {
        loginForm.clearErrors('code');
        loginForm.setValue('code', '');
      }
    } finally {
      setResendLoading(false);
    }
  };

  const loginFormContent = (
    <Form {...loginForm}>
      <form onSubmit={(event) => { void loginForm.handleSubmit(onLoginSubmit)(event); }} className="space-y-4">
        {loginStep === 'email' ? (
          <>
            <FormField
              control={loginForm.control}
              name="email"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>{t('auth.email')}</FormLabel>
                  <FormControl>
                    <Input
                      {...field}
                      type="email"
                      autoComplete="username"
                      placeholder={t('auth.emailPlaceholder')}
                      disabled={isSubmitting}
                    />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />

            <Button type="submit" className="w-full" disabled={isSubmitting || discoverableLoading}>
              {isSubmitting ? (
                <>
                  <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                  {t('auth.sending')}
                </>
              ) : (
                t('auth.sendCode')
              )}
            </Button>

            {passkeysAvailable && (
              <>
                <div className="relative">
                  <div className="absolute inset-0 flex items-center">
                    <span className="w-full border-t" />
                  </div>
                  <div className="relative flex justify-center text-xs uppercase">
                    <span className="bg-card px-2 text-muted-foreground">{t('auth.or')}</span>
                  </div>
                </div>

                <Button
                  type="button"
                  variant="outline"
                  className="w-full"
                  onClick={() => { void handleDiscoverablePasskey(); }}
                  disabled={isSubmitting || discoverableLoading}
                >
                  {discoverableLoading ? (
                    <>
                      <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                      {t('auth.authenticating')}
                    </>
                  ) : (
                    t('auth.signInPasskey')
                  )}
                </Button>
              </>
            )}
          </>
        ) : (
          <>
            <div className="flex items-center justify-between gap-2 text-sm">
              <span className="min-w-0 truncate">{loginForm.getValues().email}</span>
              <Button
                variant="link"
                size="sm"
                type="button"
                onClick={() => {
                  setLoginStep('email');
                  loginForm.setValue('code', '');
                  setPasskeyAvailable(false);
                  setPasskeyChallenge(null);
                  setVerifyMethod('code');
                }}
                className="h-auto p-0"
                disabled={isSubmitting || passkeyLoading}
              >
                {t('auth.change')}
              </Button>
            </div>

            {passkeyAvailable && (
              <Tabs value={verifyMethod} onValueChange={(value) => { if (value === 'passkey' || value === 'code') setVerifyMethod(value); }}>
                <TabsList className="grid w-full grid-cols-2">
                  <TabsTrigger value="passkey">{t('auth.passkeyTab')}</TabsTrigger>
                  <TabsTrigger value="code">{t('auth.emailCodeTab')}</TabsTrigger>
                </TabsList>
              </Tabs>
            )}

            {verifyMethod === 'code' ? (
              <>
                <FormField
                  control={loginForm.control}
                  name="code"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>{t('auth.verificationCode')}</FormLabel>
                      <FormControl>
                        <Input
                          {...field}
                          placeholder={t('auth.verificationCodePlaceholder')}
                          className="font-mono tracking-widest"
                          inputMode="numeric"
                          autoComplete="one-time-code"
                          pattern="[0-9]*"
                          maxLength={6}
                          disabled={isSubmitting}
                        />
                      </FormControl>
                      <FormDescription>
                        {t(codeDeliveryHintKey(emailDelivery))}
                      </FormDescription>
                      <FormMessage />
                    </FormItem>
                  )}
                />

                <Button type="submit" className="w-full" disabled={isSubmitting || resendLoading}>
                  {isSubmitting ? (
                    <>
                      <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                      {t('auth.verifying')}
                    </>
                  ) : (
                    t('auth.verifyLogin')
                  )}
                </Button>

                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  className="w-full"
                  onClick={() => { void handleResendCode(); }}
                  disabled={isSubmitting || resendLoading}
                >
                  {resendLoading ? (
                    <>
                      <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                      {t('auth.sending')}
                    </>
                  ) : (
                    t('auth.resendCode')
                  )}
                </Button>
              </>
            ) : (
              <div className="space-y-4">
                <Button
                  type="button"
                  className="w-full"
                  onClick={() => { void handlePasskeyLogin(); }}
                  disabled={passkeyLoading}
                >
                  {passkeyLoading ? (
                    <>
                      <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                      {t('auth.authenticating')}
                    </>
                  ) : (
                    t('auth.signInPasskey')
                  )}
                </Button>
              </div>
            )}
          </>
        )}
      </form>
    </Form>
  );

  const openPanel = () => setLocation(landingPath);
  const signInContent = challenge ? (
    <SecondFactorChallenge key={challenge.challenge} challenge={challenge} onSignedIn={openPanel} />
  ) : publicSettings?.signInMethod === 'password' ? (
    <PasswordLoginForm onSignedIn={openPanel} />
  ) : loginFormContent;

  return (
    <div className="min-h-screen flex items-center justify-center bg-background">
      <div className="max-w-md w-full p-4 md:p-10">
        <div className="flex flex-col justify-center">
          <h1 className="sr-only">{t('pageTitles.staffLogin')}</h1>
          <SignInBrand />

          <Card className="shadow-card">
            <CardContent className="pt-6 space-y-4">
              {setupPhase === 'unclaimed' ? (
                <Notice variant="warning" title={t('auth.notSetUpTitle')}>
                  {t('auth.notSetUpDesc')}
                </Notice>
              ) : (
                signInContent
              )}
              {setupPhase === 'owner-sign-in' && (
                <Notice variant="info" title={t('auth.ownerSignInTitle')}>
                  {t('auth.ownerSignInDesc')}
                </Notice>
              )}
              {(setupPhase === 'unclaimed' || setupPhase === 'owner-sign-in') && (
                <Button asChild variant={setupPhase === 'unclaimed' ? 'default' : 'outline'} className="w-full">
                  <Link href={setupPath()}>
                    {t('auth.openSetup')}
                  </Link>
                </Button>
              )}
            </CardContent>
          </Card>

          {setupPhase !== 'unclaimed' && (
            <p className="mt-6 text-center text-xs text-muted-foreground">{t('auth.inGameLoginHint')}</p>
          )}
        </div>
      </div>
    </div>
  );
};

export default AuthPage;
