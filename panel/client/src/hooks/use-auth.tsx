import { createContext, type ReactNode, use, useCallback, useEffect, useMemo, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { z } from "zod";
import { PanelAuthResponseSchema, type PanelAuthResponse } from "@proto/modl/v1/auth_pb.ts";
import { useLocation } from "wouter";
import { useToast } from "@/hooks/use-toast";
import { apiFetch } from "@/lib/api";
import { decodeProtoResponse } from "@/lib/proto-fetch";
import { signInOutcomeOf, type SignInChallenge, type SignInOutcome } from "@/lib/sign-in-outcome";
import { sendPasswordLogin, sendSecondFactorCode, toSignInChallenge } from "@/hooks/data/sign-in";
import {
  invalidateSignedInProfile,
  markSignedOut,
  refetchSignedInProfile,
  useSignedInProfile,
  type SignedInUser,
} from "@/hooks/data/signed-in-profile";
import { subscribeToSessionKeyChanges } from "@/lib/panel-session";
import { DEFAULT_DATE_FORMAT, setDateLocale, setDateFormat } from "@/utils/date-utils";
import { startAuthentication, type PublicKeyCredentialRequestOptionsJSON } from "@simplewebauthn/browser";
import { isWebAuthnCancellation, publicKeyRequestOptionsSchema } from "@/utils/webauthn";
import i18n from "@/lib/i18n";
import { isRateLimitedError, readResponseErrorMessage } from "@/lib/http-error";
import { signedInStatusOf } from "@/lib/signed-in-status";

export interface PasskeyLoginChallenge {
  challengeId: string;
  options: PublicKeyCredentialRequestOptionsJSON;
}

export type PasskeyLoginOptions = { hasPasskeys: false } | ({ hasPasskeys: true } & PasskeyLoginChallenge);

const passkeyLoginChallengeSchema = z.object({
  challengeId: z.string().min(1),
  options: publicKeyRequestOptionsSchema,
});

const passkeyLoginOptionsSchema = z.discriminatedUnion('hasPasskeys', [
  z.object({ hasPasskeys: z.literal(false) }),
  passkeyLoginChallengeSchema.extend({ hasPasskeys: z.literal(true) }),
]);

const NO_PASSKEYS: PasskeyLoginOptions = { hasPasskeys: false };

interface AuthContextType {
  user: SignedInUser | null;
  isLoading: boolean;
  profileUnavailable: boolean;
  retryProfile: () => void;
  refreshUser: () => Promise<SignedInUser | null>;
  challenge: SignInChallenge | null;
  cancelChallenge: () => void;
  receiveSignInResponse: (response: PanelAuthResponse) => Promise<SignInOutcome>;
  loginWithPassword: (email: string, password: string) => Promise<SignInOutcome>;
  completeSecondFactor: (challenge: string, code: string) => Promise<SignInOutcome>;
  login: (email: string, code: string) => Promise<SignInOutcome>;
  logout: () => void;
  signOutAllSessions: () => void;
  requestEmailVerification: (email: string) => Promise<boolean>;
  checkPasskeyOptions: (email: string) => Promise<PasskeyLoginOptions>;
  loginWithPasskey: (challengeId: string, optionsJson: PublicKeyCredentialRequestOptionsJSON) => Promise<boolean>;
  loginWithDiscoverablePasskey: () => Promise<boolean>;
}

const AuthContext = createContext<AuthContextType | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [, navigate] = useLocation();
  const { toast } = useToast();
  const queryClient = useQueryClient();
  const profile = useSignedInProfile();
  const status = signedInStatusOf(profile);
  const user = profile.data ?? null;
  const isLoading = status === 'loading';
  const profileUnavailable = status === 'unavailable';
  const { refetch: refetchProfile } = profile;
  const retryProfile = useCallback(() => { void refetchProfile(); }, [refetchProfile]);
  const [challenge, setChallenge] = useState<SignInChallenge | null>(null);

  const refreshUser = useCallback(async () => refetchSignedInProfile(queryClient), [queryClient]);

  const resyncAfterFailedSignOut = useCallback(async () => {
    await refetchSignedInProfile(queryClient).catch(() => null);
  }, [queryClient]);

  useEffect(() => subscribeToSessionKeyChanges((sessionKey) => {
    if (sessionKey === null) {
      markSignedOut(queryClient);
      return;
    }
    void invalidateSignedInProfile(queryClient);
  }), [queryClient]);

  useEffect(() => {
    const lang = user?.language || 'en';
    const dateFormat = user?.dateFormat ?? DEFAULT_DATE_FORMAT;
    setDateLocale(lang);
    setDateFormat(dateFormat);
  }, [user?.language, user?.dateFormat]);

  const requestEmailVerification = useCallback(async (email: string): Promise<boolean> => {
    try {
      const response = await apiFetch('/v1/panel/auth/send-email-code', {
        method: 'POST',
        body: { email },
      });
      if (!response.ok) {
        const serverMessage = await readResponseErrorMessage(response);
        toast({
          title: i18n.t('toast.error'),
          description: serverMessage ?? i18n.t('toast.sendCodeFailed'),
          variant: "destructive",
        });
        return false;
      }
      return true;
    } catch (error) {
      if (isRateLimitedError(error)) return false;
      toast({
        title: i18n.t('toast.networkError'),
        description: i18n.t('toast.networkErrorDesc'),
        variant: "destructive",
      });
      return false;
    }
  }, [toast]);

  const completeSignIn = useCallback(async (): Promise<boolean> => {
    const authenticatedUser = await refreshUser();
    if (!authenticatedUser) {
      toast({
        title: i18n.t('toast.loginError'),
        description: i18n.t('toast.loginErrorDesc'),
        variant: "destructive",
      });
      return false;
    }

    setChallenge(null);
    return true;
  }, [refreshUser, toast]);

  const receiveSignInResponse = useCallback(async (response: PanelAuthResponse): Promise<SignInOutcome> => {
    const pending = toSignInChallenge(response);
    if (pending !== null) {
      setChallenge(pending);
      return 'challenge';
    }
    return signInOutcomeOf(response.success && await completeSignIn(), null);
  }, [completeSignIn]);

  const cancelChallenge = useCallback(() => setChallenge(null), []);

  const loginWithPassword = useCallback(
    async (email: string, password: string) => receiveSignInResponse(await sendPasswordLogin(email, password)),
    [receiveSignInResponse],
  );

  const completeSecondFactor = useCallback(
    async (token: string, code: string) => receiveSignInResponse(await sendSecondFactorCode(token, code)),
    [receiveSignInResponse],
  );

  const login = useCallback(async (email: string, code: string): Promise<SignInOutcome> => {
    try {
      const response = await apiFetch('/v1/panel/auth/verify-email-code', {
        method: 'POST',
        body: { email, code },
      });

      if (!response.ok) {
        return 'failed';
      }

      return await receiveSignInResponse(await decodeProtoResponse(PanelAuthResponseSchema, response));
    } catch (error) {
      if (isRateLimitedError(error)) return 'failed';
      toast({
        title: i18n.t('toast.loginError'),
        description: i18n.t('toast.loginErrorDesc'),
        variant: "destructive",
      });
      return 'failed';
    }
  }, [receiveSignInResponse, toast]);

  const checkPasskeyOptions = useCallback(async (email: string): Promise<PasskeyLoginOptions> => {
    try {
      const response = await apiFetch('/v1/panel/auth/webauthn/login/options', {
        method: 'POST',
        body: { email },
      });
      if (!response.ok) {
        return NO_PASSKEYS;
      }
      const parsed = passkeyLoginOptionsSchema.safeParse(await response.json());
      return parsed.success ? parsed.data : NO_PASSKEYS;
    } catch {
      return NO_PASSKEYS;
    }
  }, []);

  const verifyPasskeyAssertion = useCallback(async (challengeId: string, optionsJson: PublicKeyCredentialRequestOptionsJSON): Promise<boolean> => {
    const assertionResponse = await startAuthentication({ optionsJSON: optionsJson });
    const response = await apiFetch('/v1/panel/auth/webauthn/login/verify', {
      method: 'POST',
      body: { challengeId, response: JSON.stringify(assertionResponse) },
    });

    if (!response.ok) {
      const serverMessage = await readResponseErrorMessage(response);
      toast({
        title: i18n.t('toast.loginFailed'),
        description: serverMessage ?? i18n.t('toast.passkeyFailed'),
        variant: 'destructive',
      });
      return false;
    }

    return completeSignIn();
  }, [completeSignIn, toast]);

  const reportPasskeyError = useCallback((error: unknown) => {
    if (isWebAuthnCancellation(error) || isRateLimitedError(error)) return;
    toast({
      title: i18n.t('toast.loginError'),
      description: i18n.t('toast.passkeyFailed'),
      variant: 'destructive',
    });
  }, [toast]);

  const loginWithPasskey = useCallback(async (challengeId: string, optionsJson: PublicKeyCredentialRequestOptionsJSON): Promise<boolean> => {
    try {
      return await verifyPasskeyAssertion(challengeId, optionsJson);
    } catch (error) {
      reportPasskeyError(error);
      return false;
    }
  }, [verifyPasskeyAssertion, reportPasskeyError]);

  const loginWithDiscoverablePasskey = useCallback(async (): Promise<boolean> => {
    try {
      const startRes = await apiFetch('/v1/panel/auth/webauthn/login/start', { method: 'POST' });
      if (!startRes.ok) {
        toast({
          title: i18n.t('toast.loginError'),
          description: i18n.t('toast.passkeyStartFailed'),
          variant: 'destructive',
        });
        return false;
      }
      const { challengeId, options } = passkeyLoginChallengeSchema.parse(await startRes.json());
      return await verifyPasskeyAssertion(challengeId, options);
    } catch (error) {
      reportPasskeyError(error);
      return false;
    }
  }, [verifyPasskeyAssertion, reportPasskeyError, toast]);

  const finalizeSignOut = useCallback(async (invalidate: () => Promise<Response>) => {
    const reportFailure = (description: string) => {
      toast({ title: i18n.t('toast.logoutError'), description, variant: "destructive" });
    };

    let response: Response;
    try {
      response = await invalidate();
    } catch {
      await resyncAfterFailedSignOut();
      reportFailure(i18n.t('toast.logoutErrorDesc'));
      return;
    }

    if (!response.ok) {
      const serverMessage = await readResponseErrorMessage(response);
      await resyncAfterFailedSignOut();
      reportFailure(serverMessage ?? i18n.t('toast.logoutErrorDesc'));
      return;
    }

    markSignedOut(queryClient);
    toast({ title: i18n.t('toast.logoutSuccess') });
    navigate('/auth');
  }, [queryClient, resyncAfterFailedSignOut, navigate, toast]);

  const logout = useCallback(() => {
    void finalizeSignOut(async () => apiFetch('/v1/panel/auth/logout', { method: 'POST' }));
  }, [finalizeSignOut]);

  const signOutAllSessions = useCallback(() => {
    void finalizeSignOut(async () => apiFetch('/v1/panel/auth/sessions', { method: 'DELETE' }));
  }, [finalizeSignOut]);

  const contextValue = useMemo<AuthContextType>(() => ({
    user,
    isLoading,
    profileUnavailable,
    retryProfile,
    refreshUser,
    challenge,
    cancelChallenge,
    receiveSignInResponse,
    loginWithPassword,
    completeSecondFactor,
    login,
    logout,
    signOutAllSessions,
    requestEmailVerification,
    checkPasskeyOptions,
    loginWithPasskey,
    loginWithDiscoverablePasskey,
  }), [
    user,
    isLoading,
    profileUnavailable,
    retryProfile,
    refreshUser,
    challenge,
    cancelChallenge,
    receiveSignInResponse,
    loginWithPassword,
    completeSecondFactor,
    login,
    logout,
    signOutAllSessions,
    requestEmailVerification,
    checkPasskeyOptions,
    loginWithPasskey,
    loginWithDiscoverablePasskey,
  ]);

  return (
    <AuthContext value={contextValue}>
      {children}
    </AuthContext>
  );
}

export function useAuth() {
  const context = use(AuthContext);
  if (!context) {
    throw new Error("useAuth must be used within an AuthProvider");
  }
  return context;
}
