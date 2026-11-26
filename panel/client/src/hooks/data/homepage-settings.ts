import { create, type MessageInitShape } from '@bufbuild/protobuf';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  HomepageCardActionType as WireCardActionType,
  HomepageFooterLinkKind,
  HomepageSettingsEnvelopeSchema,
  PublicHomepageResponseSchema,
  UpdateHomepageSettingsRequestSchema,
  type HomepageCardDraft as HomepageCardDraftMessage,
  type HomepageFooterLink as HomepageFooterLinkMessage,
  type HomepageSettings as HomepageSettingsMessage,
  type HomepageCardDraftSchema,
  type HomepageSettingsSchema,
} from '@proto/modl/v1/settings_pb.ts';
import { optionalField } from '@/lib/defined-fields';
import { isConflictError } from '@/lib/http-error';
import { protoFetch, protoSend } from '@/lib/proto-fetch';
import { RATE_LIMIT_AWARE_RETRY } from '@/lib/query-retry';
import { PUBLIC_HOMEPAGE_CARDS_QUERY_KEY, type HomepageCardActionType } from './homepage';
import type { SettingsWrite, VersionedSettings } from './settings';

export const FOOTER_LINK_KINDS = ['discord', 'store', 'website', 'vote', 'other'] as const;

export type FooterLinkKind = (typeof FOOTER_LINK_KINDS)[number];

export const MAX_FOOTER_LINKS = 8;
export const MAX_TAGLINE_LENGTH = 160;
export const MAX_SERVER_ADDRESS_LENGTH = 255;
export const MAX_FOOTER_LINK_LABEL_LENGTH = 48;
export const MAX_FOOTER_LINK_URL_LENGTH = 2048;

export interface FooterLink {
  label: string;
  url: string;
  kind: FooterLinkKind;
}

export interface HomepageCardDraft {
  id: string;
  title: string;
  description: string;
  icon: string;
  actionType: HomepageCardActionType;
  actionUrl: string;
  categoryId: string;
  categoryName: string;
  enabled: boolean;
}

export interface HomepageSettings {
  tagline: string;
  serverAddress: string;
  showPlayerCount: boolean;
  footerLinks: FooterLink[];
  cards: HomepageCardDraft[];
}

export interface PublicHomepage {
  tagline: string;
  serverAddress: string;
  onlinePlayers?: number;
  footerLinks: FooterLink[];
}

export const DEFAULT_HOMEPAGE_SETTINGS: HomepageSettings = {
  tagline: '',
  serverAddress: '',
  showPlayerCount: false,
  footerLinks: [],
  cards: [],
};

export const MAX_HOMEPAGE_CARDS = 50;

const PANEL_HOMEPAGE_SETTINGS_PATH = '/v1/panel/settings/homepage';
const PUBLIC_HOMEPAGE_PATH = '/v1/public/homepage';
const PUBLIC_HOMEPAGE_QUERY_KEY = [PUBLIC_HOMEPAGE_PATH] as const;
const PLAYER_COUNT_REFRESH_MS = 30_000;

const KINDS: readonly (readonly [HomepageFooterLinkKind, FooterLinkKind])[] = [
  [HomepageFooterLinkKind.DISCORD, 'discord'],
  [HomepageFooterLinkKind.STORE, 'store'],
  [HomepageFooterLinkKind.WEBSITE, 'website'],
  [HomepageFooterLinkKind.VOTE, 'vote'],
  [HomepageFooterLinkKind.OTHER, 'other'],
];

function kindOf(wire: HomepageFooterLinkKind): FooterLinkKind {
  return KINDS.find(([candidate]) => candidate === wire)?.[1] ?? 'other';
}

function wireKindOf(kind: FooterLinkKind): HomepageFooterLinkKind {
  return KINDS.find(([, candidate]) => candidate === kind)?.[0] ?? HomepageFooterLinkKind.OTHER;
}

function toFooterLink(link: HomepageFooterLinkMessage): FooterLink {
  return { label: link.label, url: link.url, kind: kindOf(link.kind) };
}

function toCardDraft(card: HomepageCardDraftMessage): HomepageCardDraft {
  return {
    id: card.id,
    title: card.title,
    description: card.description,
    icon: card.icon,
    actionType: card.actionType === WireCardActionType.CATEGORY ? 'category_dropdown' : 'url',
    actionUrl: card.actionUrl,
    categoryId: card.categoryId,
    categoryName: card.categoryName,
    enabled: card.enabled,
  };
}

function toCardDraftInit(card: HomepageCardDraft): MessageInitShape<typeof HomepageCardDraftSchema> {
  return {
    id: card.id,
    title: card.title,
    description: card.description,
    icon: card.icon,
    actionType: card.actionType === 'category_dropdown' ? WireCardActionType.CATEGORY : WireCardActionType.LINK,
    actionUrl: card.actionType === 'url' ? card.actionUrl : '',
    categoryId: card.actionType === 'category_dropdown' ? card.categoryId : '',
    enabled: card.enabled,
  };
}

function toHomepageSettings(settings: HomepageSettingsMessage | undefined): HomepageSettings {
  if (settings === undefined) return DEFAULT_HOMEPAGE_SETTINGS;
  return {
    tagline: settings.tagline,
    serverAddress: settings.serverAddress,
    showPlayerCount: settings.showPlayerCount,
    footerLinks: settings.footerLinks.map(toFooterLink),
    cards: settings.cards.map(toCardDraft),
  };
}

function toHomepageSettingsInit(settings: HomepageSettings): MessageInitShape<typeof HomepageSettingsSchema> {
  return {
    tagline: settings.tagline,
    serverAddress: settings.serverAddress,
    showPlayerCount: settings.showPlayerCount,
    footerLinks: settings.footerLinks.map((link) => ({ label: link.label, url: link.url, kind: wireKindOf(link.kind) })),
    cards: settings.cards.map(toCardDraftInit),
  };
}

export function useHomepageSettings() {
  return useQuery({
    queryKey: [PANEL_HOMEPAGE_SETTINGS_PATH],
    queryFn: async (): Promise<VersionedSettings<HomepageSettings>> => {
      const envelope = await protoFetch(HomepageSettingsEnvelopeSchema, PANEL_HOMEPAGE_SETTINGS_PATH);
      return { value: toHomepageSettings(envelope.data), version: Number(envelope.meta?.version ?? 0n) };
    },
    refetchOnWindowFocus: false,
  });
}

export function useSaveHomepageSettings() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ expectedVersion, value }: SettingsWrite<HomepageSettings>): Promise<VersionedSettings<HomepageSettings>> => {
      const envelope = await protoSend('PUT', PANEL_HOMEPAGE_SETTINGS_PATH, UpdateHomepageSettingsRequestSchema,
        create(UpdateHomepageSettingsRequestSchema, { expectedVersion: BigInt(expectedVersion), settings: toHomepageSettingsInit(value) }),
        HomepageSettingsEnvelopeSchema);
      return { value: toHomepageSettings(envelope.data), version: Number(envelope.meta?.version ?? 0n) };
    },
    onSuccess: async (saved) => {
      queryClient.setQueryData([PANEL_HOMEPAGE_SETTINGS_PATH], saved);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: PUBLIC_HOMEPAGE_QUERY_KEY }),
        queryClient.invalidateQueries({ queryKey: PUBLIC_HOMEPAGE_CARDS_QUERY_KEY }),
      ]);
    },
    onError: async (error) => {
      if (isConflictError(error)) await queryClient.invalidateQueries({ queryKey: [PANEL_HOMEPAGE_SETTINGS_PATH] });
    },
  });
}

export function usePublicHomepage() {
  return useQuery({
    queryKey: PUBLIC_HOMEPAGE_QUERY_KEY,
    queryFn: async (): Promise<PublicHomepage> => {
      const response = await protoFetch(PublicHomepageResponseSchema, PUBLIC_HOMEPAGE_PATH);
      return {
        tagline: response.tagline,
        serverAddress: response.serverAddress,
        ...optionalField('onlinePlayers', response.onlinePlayers),
        footerLinks: response.footerLinks.map(toFooterLink),
      };
    },
    staleTime: PLAYER_COUNT_REFRESH_MS,
    refetchInterval: (query) => (query.state.data?.onlinePlayers === undefined ? false : PLAYER_COUNT_REFRESH_MS),
    ...RATE_LIMIT_AWARE_RETRY,
  });
}
