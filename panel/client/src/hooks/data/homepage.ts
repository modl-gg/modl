import { useQuery } from '@tanstack/react-query';
import { PublicHomepageCardsResponseSchema, type HomepageCardResponse } from '@proto/modl/v1/homepage_pb.ts';
import { optionalField } from '@/lib/defined-fields';
import { protoFetch } from '@/lib/proto-fetch';
import { mapPublicArticleStub, type PublicKnowledgebaseArticleStub } from './knowledgebase';

export type HomepageCardActionType = 'url' | 'category_dropdown';

function homepageCardActionType(actionType: string): HomepageCardActionType {
  return actionType === 'category_dropdown' ? 'category_dropdown' : 'url';
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
export const PUBLIC_HOMEPAGE_CARDS_QUERY_KEY = [PUBLIC_HOMEPAGE_CARDS_PATH] as const;

async function fetchPublicHomepageCards(): Promise<PublicHomepageCard[]> {
  const response = await protoFetch(PublicHomepageCardsResponseSchema, PUBLIC_HOMEPAGE_CARDS_PATH);
  return response.cards.map(mapPublicHomepageCard);
}

export function usePublicHomepageCards() {
  return useQuery<PublicHomepageCard[]>({
    queryKey: PUBLIC_HOMEPAGE_CARDS_QUERY_KEY,
    queryFn: fetchPublicHomepageCards,
    staleTime: 5 * 60 * 1000,
    refetchOnWindowFocus: false,
    retry: false,
  });
}
