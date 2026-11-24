import { keepPreviousData, skipToken, useQuery } from '@tanstack/react-query';
import { create } from '@bufbuild/protobuf';
import {
  CreateArticleRequestSchema,
  CreateCategoryRequestSchema,
  KnowledgebaseArticleResponseSchema,
  KnowledgebaseCategoryResponseSchema,
  KnowledgebaseMessageResponseSchema,
  KnowledgebaseSearchResponseSchema,
  PanelKnowledgebaseCategoriesResponseSchema,
  PublicKnowledgebaseCategoriesResponseSchema,
  ReorderRequestSchema,
  UpdateArticleRequestSchema,
  UpdateCategoryRequestSchema,
  type KnowledgebaseArticleResponse,
  type KnowledgebaseArticleStubResponse,
  type PanelKnowledgebaseCategoryWithArticlesResponse,
  type PublicKnowledgebaseCategoryWithArticlesResponse,
} from '@proto/modl/v1/knowledgebase_pb.ts';
import { protoFetch, protoFetchOrNull, protoSend } from '@/lib/proto-fetch';
import { useInvalidatingMutation } from '@/hooks/data/invalidating-mutation';
import { tsToIso } from '@/lib/proto-ui';
import type { KnowledgebaseArticle, KnowledgebaseArticleStub, KnowledgebaseCategory } from '@/types/knowledgebase';

export type PanelKnowledgebaseArticle = KnowledgebaseArticle;

export interface PublicKnowledgebaseArticleStub {
  id: string;
  title: string;
  slug: string;
  ordinal: number;
}

export interface PublicKnowledgebaseCategory {
  id: string;
  name: string;
  slug: string;
  description: string;
  ordinal: number;
  articles: PublicKnowledgebaseArticleStub[];
}

const mapArticleStub = (article: KnowledgebaseArticleResponse): KnowledgebaseArticleStub => ({
  id: article.id,
  title: article.title,
  slug: article.slug,
  ordinal: article.ordinal,
  isVisible: article.isVisible,
  categoryId: article.categoryId,
});

const mapArticle = (article: KnowledgebaseArticleResponse): PanelKnowledgebaseArticle => ({
  ...mapArticleStub(article),
  content: article.content,
  createdAt: tsToIso(article.createdAt) ?? '',
  updatedAt: tsToIso(article.updatedAt) ?? '',
});

const mapCategory = (category: PanelKnowledgebaseCategoryWithArticlesResponse): KnowledgebaseCategory => ({
  id: category.id,
  name: category.name,
  slug: category.slug,
  description: category.description,
  ordinal: category.ordinal,
  articles: category.articles.map(mapArticleStub),
});

export const KNOWLEDGEBASE_CATEGORIES_QUERY_KEY = ['knowledgebaseCategories'] as const;

const knowledgebaseCategoriesPath = '/v1/panel/knowledgebase/categories';

async function fetchKnowledgebaseCategories(): Promise<KnowledgebaseCategory[]> {
  const response = await protoFetch(PanelKnowledgebaseCategoriesResponseSchema, knowledgebaseCategoriesPath);
  return response.categories.map(mapCategory);
}

const knowledgebaseCategoryPath = (categoryId: string): string =>
  `${knowledgebaseCategoriesPath}/${encodeURIComponent(categoryId)}`;

const knowledgebaseArticlesPath = (categoryId: string): string => `${knowledgebaseCategoryPath(categoryId)}/articles`;

const knowledgebaseArticlePath = (categoryId: string, articleId: string): string =>
  `${knowledgebaseArticlesPath(categoryId)}/${encodeURIComponent(articleId)}`;

export async function fetchPanelKnowledgebaseArticle(categoryId: string, articleId: string): Promise<PanelKnowledgebaseArticle> {
  return mapArticle(await protoFetch(KnowledgebaseArticleResponseSchema, knowledgebaseArticlePath(categoryId, articleId)));
}

export function useKnowledgebaseCategories() {
  return useQuery<KnowledgebaseCategory[]>({
    queryKey: KNOWLEDGEBASE_CATEGORIES_QUERY_KEY,
    queryFn: fetchKnowledgebaseCategories,
    staleTime: 5 * 60 * 1000,
    refetchOnWindowFocus: false,
  });
}

function useKnowledgebaseMutation<TInput>(send: (input: TInput) => Promise<unknown>) {
  return useInvalidatingMutation(KNOWLEDGEBASE_CATEGORIES_QUERY_KEY, send);
}

export interface KnowledgebaseCategoryInput {
  name: string;
  description?: string;
}

export interface KnowledgebaseCategoryUpdate extends KnowledgebaseCategoryInput {
  id: string;
}

export interface KnowledgebaseArticleInput {
  categoryId: string;
  title: string;
  content: string;
  isVisible: boolean;
}

export interface KnowledgebaseArticleUpdate extends KnowledgebaseArticleInput {
  articleId: string;
}

export interface KnowledgebaseArticleRef {
  categoryId: string;
  articleId: string;
}

export interface KnowledgebaseArticleOrder {
  categoryId: string;
  orderedArticleIds: string[];
}

export function useCreateKnowledgebaseCategory() {
  return useKnowledgebaseMutation(async ({ name, description }: KnowledgebaseCategoryInput) => protoSend(
    'POST',
    knowledgebaseCategoriesPath,
    CreateCategoryRequestSchema,
    create(CreateCategoryRequestSchema, { name, description }),
    KnowledgebaseCategoryResponseSchema,
  ));
}

export function useUpdateKnowledgebaseCategory() {
  return useKnowledgebaseMutation(async ({ id, name, description }: KnowledgebaseCategoryUpdate) => protoSend(
    'PUT',
    knowledgebaseCategoryPath(id),
    UpdateCategoryRequestSchema,
    create(UpdateCategoryRequestSchema, { name, description }),
    KnowledgebaseCategoryResponseSchema,
  ));
}

export function useDeleteKnowledgebaseCategory() {
  return useKnowledgebaseMutation(async (categoryId: string) =>
    protoFetch(KnowledgebaseMessageResponseSchema, knowledgebaseCategoryPath(categoryId), { method: 'DELETE' }));
}

export function useReorderKnowledgebaseCategories() {
  return useKnowledgebaseMutation(async (orderedCategoryIds: string[]) => protoSend(
    'PUT',
    `${knowledgebaseCategoriesPath}/reorder`,
    ReorderRequestSchema,
    create(ReorderRequestSchema, { ids: orderedCategoryIds }),
    KnowledgebaseMessageResponseSchema,
  ));
}

export function useCreateKnowledgebaseArticle() {
  return useKnowledgebaseMutation(async ({ categoryId, title, content, isVisible }: KnowledgebaseArticleInput) => protoSend(
    'POST',
    knowledgebaseArticlesPath(categoryId),
    CreateArticleRequestSchema,
    create(CreateArticleRequestSchema, { title, content, isVisible }),
    KnowledgebaseArticleResponseSchema,
  ));
}

export function useUpdateKnowledgebaseArticle() {
  return useKnowledgebaseMutation(async ({ categoryId, articleId, title, content, isVisible }: KnowledgebaseArticleUpdate) => protoSend(
    'PUT',
    knowledgebaseArticlePath(categoryId, articleId),
    UpdateArticleRequestSchema,
    create(UpdateArticleRequestSchema, { title, content, isVisible }),
    KnowledgebaseArticleResponseSchema,
  ));
}

export function useDeleteKnowledgebaseArticle() {
  return useKnowledgebaseMutation(async ({ categoryId, articleId }: KnowledgebaseArticleRef) =>
    protoFetch(KnowledgebaseMessageResponseSchema, knowledgebaseArticlePath(categoryId, articleId), { method: 'DELETE' }));
}

export function useReorderKnowledgebaseArticles() {
  return useKnowledgebaseMutation(async ({ categoryId, orderedArticleIds }: KnowledgebaseArticleOrder) => protoSend(
    'PUT',
    `${knowledgebaseArticlesPath(categoryId)}/reorder`,
    ReorderRequestSchema,
    create(ReorderRequestSchema, { ids: orderedArticleIds }),
    KnowledgebaseMessageResponseSchema,
  ));
}

const PUBLIC_KNOWLEDGEBASE_PATH = '/v1/public/knowledgebase';
const PUBLIC_CATEGORIES_PATH = `${PUBLIC_KNOWLEDGEBASE_PATH}/categories`;
const PUBLIC_SEARCH_PATH = `${PUBLIC_KNOWLEDGEBASE_PATH}/search`;
const PUBLIC_ARTICLES_PATH = `${PUBLIC_KNOWLEDGEBASE_PATH}/articles`;

export const MIN_KNOWLEDGEBASE_SEARCH_LENGTH = 2;

export const mapPublicArticleStub = (
  article: Pick<KnowledgebaseArticleStubResponse, 'id' | 'title' | 'slug' | 'ordinal'>,
): PublicKnowledgebaseArticleStub => ({
  id: article.id,
  title: article.title,
  slug: article.slug,
  ordinal: article.ordinal,
});

const mapPublicCategory = (category: PublicKnowledgebaseCategoryWithArticlesResponse): PublicKnowledgebaseCategory => ({
  id: category.id,
  name: category.name,
  slug: category.slug,
  description: category.description,
  ordinal: category.ordinal,
  articles: category.articles.map(mapPublicArticleStub),
});

async function fetchPublicKnowledgebaseCategories(): Promise<PublicKnowledgebaseCategory[]> {
  const response = await protoFetch(PublicKnowledgebaseCategoriesResponseSchema, PUBLIC_CATEGORIES_PATH);
  return response.categories.map(mapPublicCategory);
}

export function usePublicKnowledgebaseCategories() {
  return useQuery<PublicKnowledgebaseCategory[]>({
    queryKey: [PUBLIC_CATEGORIES_PATH],
    queryFn: fetchPublicKnowledgebaseCategories,
    staleTime: 5 * 60 * 1000,
    refetchOnWindowFocus: false,
  });
}

async function searchPublicKnowledgebase(query: string): Promise<PublicKnowledgebaseArticleStub[]> {
  const response = await protoFetch(
    KnowledgebaseSearchResponseSchema,
    `${PUBLIC_SEARCH_PATH}?q=${encodeURIComponent(query)}`,
  );
  return response.articles.map(mapPublicArticleStub);
}

export function usePublicKnowledgebaseSearch(query: string) {
  const trimmedQuery = query.trim();
  return useQuery<PublicKnowledgebaseArticleStub[]>({
    queryKey: [PUBLIC_SEARCH_PATH, trimmedQuery],
    queryFn: async () => searchPublicKnowledgebase(trimmedQuery),
    enabled: trimmedQuery.length >= MIN_KNOWLEDGEBASE_SEARCH_LENGTH,
    placeholderData: keepPreviousData,
    staleTime: 60 * 1000,
    retry: false,
  });
}

async function fetchPublicKnowledgebaseArticle(idOrSlug: string): Promise<KnowledgebaseArticle | null> {
  const article = await protoFetchOrNull(
    KnowledgebaseArticleResponseSchema,
    `${PUBLIC_ARTICLES_PATH}/${encodeURIComponent(idOrSlug)}`,
  );
  return article === null ? null : mapArticle(article);
}

export function usePublicKnowledgebaseArticle(idOrSlug: string | undefined) {
  return useQuery<KnowledgebaseArticle | null>({
    queryKey: [PUBLIC_ARTICLES_PATH, idOrSlug],
    queryFn: idOrSlug === undefined || idOrSlug === ''
      ? skipToken
      : async () => fetchPublicKnowledgebaseArticle(idOrSlug),
    retry: false,
  });
}
