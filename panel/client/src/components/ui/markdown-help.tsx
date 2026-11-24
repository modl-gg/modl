import { useTranslation } from 'react-i18next';
import { Button } from '@/components/ui/button';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import MarkdownRenderer from './markdown-renderer';

const MarkdownHelp = () => {
  const { t } = useTranslation();
  const exampleMarkdown = t('markdown.helpExample');

  return (
    <Popover>
      <PopoverTrigger asChild>
        <Button type="button" variant="ghost" size="sm" className="h-7 px-2 text-xs text-muted-foreground">
          {t('markdown.formattingHelp')}
        </Button>
      </PopoverTrigger>
      <PopoverContent align="end" className="w-[min(36rem,calc(100vw-2rem))]">
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <pre className="max-h-64 overflow-auto rounded-md border bg-surface-2 p-2 text-xs">{exampleMarkdown}</pre>
          <div className="max-h-64 overflow-auto rounded-md border p-2">
            <MarkdownRenderer content={exampleMarkdown} className="prose-xs" />
          </div>
        </div>
      </PopoverContent>
    </Popover>
  );
};

export default MarkdownHelp;
