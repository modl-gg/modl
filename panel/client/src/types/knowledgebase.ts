export interface KnowledgebaseCategory {
  id: string;
  name: string;
  slug: string;
  description?: string;
  ordinal: number;
  articles: KnowledgebaseArticleStub[];
}

export interface KnowledgebaseArticleStub {
  id: string;
  title: string;
  slug: string;
  ordinal: number;
  isVisible: boolean;
  categoryId: string;
}

export interface KnowledgebaseArticle extends KnowledgebaseArticleStub {
  content: string;
  createdAt: string;
  updatedAt: string;
}
