import { create, type MessageInitShape } from '@bufbuild/protobuf';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  PublicColorScheme,
  PublicCustomCssEnvelopeSchema,
  PublicThemeBackgroundResponseSchema,
  PublicThemeEnvelopeSchema,
  PublicThemeFont,
  PublicThemeRadius,
  UpdatePublicCustomCssRequestSchema,
  UpdatePublicThemeRequestSchema,
  type PublicTheme as PublicThemeMessage,
  type PublicThemeSchema,
} from '@proto/modl/v1/settings_pb.ts';
import { isConflictError } from '@/lib/http-error';
import { protoFetch, protoSend } from '@/lib/proto-fetch';
import { PUBLIC_SETTINGS_QUERY_KEY } from '@/lib/public-queries';
import {
  DEFAULT_PUBLIC_THEME,
  type PublicFontId,
  type PublicRadiusId,
  type PublicSchemeDefault,
  type PublicTheme,
} from '@/lib/public-theme';
import type { SettingsWrite, VersionedSettings } from './settings';

export const PUBLIC_THEME_PATH = '/v1/panel/settings/public-theme';
export const PUBLIC_CUSTOM_CSS_PATH = '/v1/panel/settings/public-css';
export const PUBLIC_STYLESHEET_PATH = '/v1/public/theme.css';
const PUBLIC_THEME_BACKGROUND_PATH = `${PUBLIC_THEME_PATH}/background`;

const FONTS: readonly (readonly [PublicThemeFont, PublicFontId])[] = [
  [PublicThemeFont.SYSTEM, 'system'],
  [PublicThemeFont.INTER, 'inter'],
  [PublicThemeFont.NUNITO, 'nunito'],
  [PublicThemeFont.LORA, 'lora'],
  [PublicThemeFont.SPACE_GROTESK, 'space-grotesk'],
];

const RADII: readonly (readonly [PublicThemeRadius, PublicRadiusId])[] = [
  [PublicThemeRadius.NONE, 'none'],
  [PublicThemeRadius.SMALL, 'small'],
  [PublicThemeRadius.MEDIUM, 'medium'],
  [PublicThemeRadius.LARGE, 'large'],
];

const SCHEMES: readonly (readonly [PublicColorScheme, PublicSchemeDefault])[] = [
  [PublicColorScheme.SYSTEM, 'system'],
  [PublicColorScheme.LIGHT, 'light'],
  [PublicColorScheme.DARK, 'dark'],
];

function idOf<Wire, Id>(pairs: readonly (readonly [Wire, Id])[], wire: Wire, fallback: Id): Id {
  return pairs.find(([candidate]) => candidate === wire)?.[1] ?? fallback;
}

function wireOf<Wire, Id>(pairs: readonly (readonly [Wire, Id])[], id: Id, fallback: Wire): Wire {
  return pairs.find(([, candidate]) => candidate === id)?.[0] ?? fallback;
}

export function toPublicTheme(theme: PublicThemeMessage | undefined): PublicTheme {
  if (theme === undefined) return DEFAULT_PUBLIC_THEME;
  return {
    primaryColor: theme.primaryColor,
    accentColor: theme.accentColor,
    backgroundColor: theme.backgroundColor,
    backgroundImageUrl: theme.backgroundImageUrl,
    font: idOf(FONTS, theme.font, DEFAULT_PUBLIC_THEME.font),
    radius: idOf(RADII, theme.radius, DEFAULT_PUBLIC_THEME.radius),
    colorScheme: idOf(SCHEMES, theme.colorScheme, DEFAULT_PUBLIC_THEME.colorScheme),
  };
}

function toPublicThemeInit(theme: PublicTheme): MessageInitShape<typeof PublicThemeSchema> {
  return {
    primaryColor: theme.primaryColor,
    accentColor: theme.accentColor,
    backgroundColor: theme.backgroundColor,
    backgroundImageUrl: theme.backgroundImageUrl,
    font: wireOf(FONTS, theme.font, PublicThemeFont.SYSTEM),
    radius: wireOf(RADII, theme.radius, PublicThemeRadius.MEDIUM),
    colorScheme: wireOf(SCHEMES, theme.colorScheme, PublicColorScheme.SYSTEM),
  };
}

export function usePublicThemeSettings() {
  return useQuery({
    queryKey: [PUBLIC_THEME_PATH],
    queryFn: async (): Promise<VersionedSettings<PublicTheme>> => {
      const envelope = await protoFetch(PublicThemeEnvelopeSchema, PUBLIC_THEME_PATH);
      return { value: toPublicTheme(envelope.data), version: Number(envelope.meta?.version ?? 0n) };
    },
    refetchOnWindowFocus: false,
  });
}

function useVersionedSave<Value>(path: string, send: (write: SettingsWrite<Value>) => Promise<VersionedSettings<Value>>) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: send,
    onSuccess: async (saved) => {
      queryClient.setQueryData([path], saved);
      await queryClient.invalidateQueries({ queryKey: PUBLIC_SETTINGS_QUERY_KEY });
    },
    onError: async (error) => {
      if (isConflictError(error)) await queryClient.invalidateQueries({ queryKey: [path] });
    },
  });
}

export function useSavePublicTheme() {
  return useVersionedSave<PublicTheme>(PUBLIC_THEME_PATH, async ({ expectedVersion, value }) => {
    const envelope = await protoSend('PUT', PUBLIC_THEME_PATH, UpdatePublicThemeRequestSchema,
      create(UpdatePublicThemeRequestSchema, { expectedVersion: BigInt(expectedVersion), theme: toPublicThemeInit(value) }),
      PublicThemeEnvelopeSchema);
    return { value: toPublicTheme(envelope.data), version: Number(envelope.meta?.version ?? 0n) };
  });
}

export function useUploadThemeBackground() {
  return useMutation({
    mutationFn: async (file: File): Promise<string> => {
      const body = new FormData();
      body.append('image', file);
      return (await protoFetch(PublicThemeBackgroundResponseSchema, PUBLIC_THEME_BACKGROUND_PATH, { method: 'POST', body })).url;
    },
  });
}

export function usePublicCustomCss() {
  return useQuery({
    queryKey: [PUBLIC_CUSTOM_CSS_PATH],
    queryFn: async (): Promise<VersionedSettings<string>> => {
      const envelope = await protoFetch(PublicCustomCssEnvelopeSchema, PUBLIC_CUSTOM_CSS_PATH);
      return { value: envelope.data?.css ?? '', version: Number(envelope.meta?.version ?? 0n) };
    },
    refetchOnWindowFocus: false,
  });
}

export function useSavePublicCustomCss() {
  return useVersionedSave<string>(PUBLIC_CUSTOM_CSS_PATH, async ({ expectedVersion, value }) => {
    const envelope = await protoSend('PUT', PUBLIC_CUSTOM_CSS_PATH, UpdatePublicCustomCssRequestSchema,
      create(UpdatePublicCustomCssRequestSchema, { expectedVersion: BigInt(expectedVersion), css: value }),
      PublicCustomCssEnvelopeSchema);
    return { value: envelope.data?.css ?? '', version: Number(envelope.meta?.version ?? 0n) };
  });
}
