import React from 'react';
import ReactMarkdown from 'react-markdown';
import { useTranslation } from 'react-i18next';

interface ArticleDisplayProps {
  title: string;
  content: string;
  updatedAt: string;
}

const ArticleDisplay: React.FC<ArticleDisplayProps> = ({ title, content, updatedAt }) => {
  const { t } = useTranslation();
  return (
    <article data-modl="article" className="prose max-w-none prose-headings:text-foreground prose-p:text-foreground prose-strong:text-foreground prose-em:text-foreground prose-li:text-foreground prose-blockquote:text-foreground prose-code:text-foreground prose-pre:bg-muted prose-pre:text-foreground dark:prose-invert">
      <h1 data-modl="article-title" className="text-2xl font-semibold mb-1 text-foreground">{title}</h1>
      {updatedAt !== '' && (
        <p data-modl="article-updated" className="not-prose text-sm text-muted-foreground mb-6">
          {t('article.lastUpdated')}: {new Date(updatedAt).toLocaleDateString()}
        </p>
      )}
      <div data-modl="article-body">
        <ReactMarkdown>{content}</ReactMarkdown>
      </div>
    </article>
  );
};

export default ArticleDisplay;
