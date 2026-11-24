import { useQuery } from '@tanstack/react-query';
import { create } from '@bufbuild/protobuf';
import {
  CreateCardRequestSchema,
  HomepageCardMutationResponseSchema,
  PanelHomepageCardResponseSchema,
  PanelHomepageCardsResponseSchema,
  PublicHomepageCardsResponseSchema,
  UpdateCardRequestSchema,
  type HomepageCardResponse,
  type PanelHomepageCardResponse,
} from '@proto/modl/v1/homepage_pb.ts';
import { optionalField } from '@/lib/defined-fields';
import { ReorderRequestSchema } from '@proto/modl/v1/knowledgebase_pb.ts';
import { protoFetch, protoSend } from '@/lib/proto-fetch';
import { useInvalidatingMutation } from '@/hooks/data/invalidating-mutation';
import { mapPublicArticleStub, type PublicKnowledgebaseArticleStub } from './knowledgebase';

export type HomepageCardActionType = 'url' | 'category_dropdown';

export interface HomepageCard {
  id: string;
  title: string;
  description: string;
  icon: string;
  iconColor: string;
  actionType: HomepageCardActionType;
  actionUrl: string;
  actionButtonText: string;
  categoryId: string;
  backgroundColor: string;
  isEnabled: boolean;
  ordinal: number;
  category?: {
    id: string;
    name: string;
    slug: string;
  };
}

export const HOMEPAGE_CARDS_QUERY_KEY = ['homepageCards'] as const;

function homepageCardActionType(actionType: string): HomepageCardActionType {
  return actionType === 'category_dropdown' ? 'category_dropdown' : 'url';
}

function mapHomepageCard(card: PanelHomepageCardResponse): HomepageCard {
  return {
    id: card.id,
    title: card.title,
    description: card.description,
    icon: card.icon,
    iconColor: card.iconColor,
    actionType: homepageCardActionType(card.actionType),
    actionUrl: card.actionUrl,
    actionButtonText: card.actionButtonText,
    categoryId: card.categoryId,
    backgroundColor: card.backgroundColor,
    isEnabled: card.isEnabled,
    ordinal: card.ordinal,
    ...optionalField('category', card.category && { id: card.category.id, name: card.category.name, slug: card.category.slug }),
  };
}

const homepageCardsPath = '/v1/panel/homepage-cards';

const homepageCardPath = (cardId: string): string => `${homepageCardsPath}/${encodeURIComponent(cardId)}`;

async function fetchHomepageCards(): Promise<HomepageCard[]> {
  const response = await protoFetch(PanelHomepageCardsResponseSchema, homepageCardsPath);
  return response.cards.map(mapHomepageCard);
}

export function useHomepageCards() {
  return useQuery<HomepageCard[]>({
    queryKey: HOMEPAGE_CARDS_QUERY_KEY,
    queryFn: fetchHomepageCards,
    staleTime: 5 * 60 * 1000,
    refetchOnWindowFocus: false,
  });
}

export interface PublicHomepageCardCategory {
  id: string;
  name: string;
  slug: string;
  description: string;
  articles: PublicKnowledgebaseArticleStub[];
}

export interface PublicHomepageCard {
  id: string;
  title: string;
  description: string;
  icon: string;
  iconColor: string;
  actionType: HomepageCardActionType;
  actionUrl: string;
  actionButtonText: string;
  ordinal: number;
  category?: PublicHomepageCardCategory;
}

function mapPublicHomepageCard(card: HomepageCardResponse): PublicHomepageCard {
  return {
    id: card.id,
    title: card.title,
    description: card.description,
    icon: card.icon,
    iconColor: card.iconColor,
    actionType: homepageCardActionType(card.actionType),
    actionUrl: card.actionUrl,
    actionButtonText: card.actionButtonText,
    ordinal: card.ordinal,
    ...optionalField('category', card.category && {
      id: card.category.id,
      name: card.category.name,
      slug: card.category.slug,
      description: card.category.description,
      articles: card.category.articles.map(mapPublicArticleStub),
    }),
  };
}

const PUBLIC_HOMEPAGE_CARDS_PATH = '/v1/public/homepage-cards';

async function fetchPublicHomepageCards(): Promise<PublicHomepageCard[]> {
  const response = await protoFetch(PublicHomepageCardsResponseSchema, PUBLIC_HOMEPAGE_CARDS_PATH);
  return response.cards.map(mapPublicHomepageCard);
}

export function usePublicHomepageCards() {
  return useQuery<PublicHomepageCard[]>({
    queryKey: [PUBLIC_HOMEPAGE_CARDS_PATH],
    queryFn: fetchPublicHomepageCards,
    staleTime: 5 * 60 * 1000,
    refetchOnWindowFocus: false,
    retry: false,
  });
}

export type HomepageCardInput = Omit<HomepageCard, 'id' | 'ordinal' | 'category'>;

export interface HomepageCardUpdate {
  id: string;
  card: HomepageCardInput;
}

export function useCreateHomepageCard() {
  return useInvalidatingMutation(HOMEPAGE_CARDS_QUERY_KEY, async (card: HomepageCardInput) => protoSend(
    'POST',
    homepageCardsPath,
    CreateCardRequestSchema,
    create(CreateCardRequestSchema, card),
    PanelHomepageCardResponseSchema,
  ));
}

export function useUpdateHomepageCard() {
  return useInvalidatingMutation(HOMEPAGE_CARDS_QUERY_KEY, async ({ id, card }: HomepageCardUpdate) => protoSend(
    'PUT',
    homepageCardPath(id),
    UpdateCardRequestSchema,
    create(UpdateCardRequestSchema, card),
    PanelHomepageCardResponseSchema,
  ));
}

export function useDeleteHomepageCard() {
  return useInvalidatingMutation(HOMEPAGE_CARDS_QUERY_KEY, async (cardId: string) =>
    protoFetch(HomepageCardMutationResponseSchema, homepageCardPath(cardId), { method: 'DELETE' }));
}

export function useReorderHomepageCards() {
  return useInvalidatingMutation(HOMEPAGE_CARDS_QUERY_KEY, async (orderedCardIds: string[]) => protoSend(
    'PUT',
    `${homepageCardsPath}/reorder`,
    ReorderRequestSchema,
    create(ReorderRequestSchema, { ids: orderedCardIds }),
    HomepageCardMutationResponseSchema,
  ));
}
