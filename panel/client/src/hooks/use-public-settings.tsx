import { useQuery } from '@tanstack/react-query';
import { PublicSettingsResponseSchema } from '@proto/modl/v1/settings_pb.ts';
import { protoFetch } from '@/lib/proto-fetch';
import { optionalField } from '@/lib/defined-fields';
import { toSignInMethod, type SignInMethodId } from '@/hooks/data/sign-in';
import { PUBLIC_SETTINGS_PATH, PUBLIC_SETTINGS_QUERY_KEY } from '@/lib/public-queries';
import type { PublicTheme } from '@/lib/public-theme';
import { toPublicTheme } from '@/hooks/data/public-theme';
import { RATE_LIMIT_AWARE_RETRY } from '@/lib/query-retry';

export interface PublicSettingsData {
  serverDisplayName: string;
  panelIconUrl: string;
  homepageIconUrl: string;
  ticketForms?: Record<string, unknown>;
  defaultLanguage: string;
  emailDelivery: boolean;
  signInMethod: SignInMethodId;
  theme: PublicTheme;
  customCssRevision: string;
}

export function usePublicSettings() {
  return useQuery<PublicSettingsData>({
    queryKey: PUBLIC_SETTINGS_QUERY_KEY,
    queryFn: async () => {
      const settings = await protoFetch(PublicSettingsResponseSchema, PUBLIC_SETTINGS_PATH);
      return {
        serverDisplayName: settings.serverDisplayName,
        panelIconUrl: settings.panelIconUrl,
        homepageIconUrl: settings.homepageIconUrl,
        ...optionalField('ticketForms', settings.ticketForms),
        defaultLanguage: settings.defaultLanguage,
        emailDelivery: settings.emailDelivery,
        signInMethod: toSignInMethod(settings.signInMethod),
        theme: toPublicTheme(settings.theme),
        customCssRevision: settings.customCssRevision,
      };
    },
    staleTime: 1000 * 30,
    gcTime: 1000 * 60 * 10,
    refetchOnWindowFocus: true,
    ...RATE_LIMIT_AWARE_RETRY,
  });
}

export function useSignInMethod(): SignInMethodId {
  const { data } = usePublicSettings();
  return data?.signInMethod ?? 'email-code';
}

export function useEmailDelivery(): boolean {
  const { data } = usePublicSettings();
  return data?.emailDelivery === true;
}
