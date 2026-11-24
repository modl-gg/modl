import { useTranslation } from 'react-i18next';
import { SquareArrowOutUpRight } from 'lucide-react';
import { RUNNING_SOURCE_URL } from '@/lib/project-links';
import { cn } from '@/lib/utils';

export function SourceCodeLink({ className }: { className?: string }) {
  const { t } = useTranslation();

  return (
    <a
      href={RUNNING_SOURCE_URL}
      target="_blank"
      rel="noopener noreferrer"
      aria-label={t('footer.sourceCodeLink')}
      title={t('footer.sourceCode')}
      className={cn(
        'inline-flex items-center gap-1 rounded-sm text-[11px] text-muted-foreground/60 hover:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
        className,
      )}
    >
      {t('footer.sourceCodeShort')}
      <SquareArrowOutUpRight className="h-3 w-3" aria-hidden="true" />
    </a>
  );
}

export function PageEndSourceCodeLink() {
  return (
    <div className="py-4 text-center">
      <SourceCodeLink />
    </div>
  );
}
