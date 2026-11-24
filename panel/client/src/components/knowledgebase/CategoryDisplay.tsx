import React from 'react';
import { Link } from 'wouter';
import { articlePath } from '@/utils/routes';
import type { PublicKnowledgebaseArticleStub } from '@/hooks/data/knowledgebase';

interface CategoryDisplayProps {
  name: string;
  slug: string;
  description: string;
  articles: PublicKnowledgebaseArticleStub[];
}

const CategoryDisplay: React.FC<CategoryDisplayProps> = ({ name, description, articles, slug }) => {
  return (
    <section data-modl="kb-category" className="space-y-2 border-t pt-6">
      <h2 data-modl="kb-category-title" id={slug} className="text-base font-semibold">{name}</h2>
      {description !== '' && <p data-modl="kb-category-description" className="text-sm text-muted-foreground">{description}</p>}
      <ul data-modl="article-list" className="space-y-1">
        {articles.map(article => (
          <li key={article.id}>
            <Link data-modl="article-link" href={articlePath(article.slug)} className="text-sm text-primary hover:underline">
              {article.title}
            </Link>
          </li>
        ))}
      </ul>
    </section>
  );
};

export default CategoryDisplay;
