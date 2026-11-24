import type React from 'react';
import { useTranslation } from 'react-i18next';

interface MarkdownEditorProps {
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
  rows?: number;
  className?: string;
}

const MarkdownEditor: React.FC<MarkdownEditorProps> = ({
  value,
  onChange,
  placeholder,
  rows = 10,
  className = "w-full p-2 border rounded",
}) => {
  const { t } = useTranslation();
  return (
    <div className="bg-background p-1 border rounded-md shadow-xs">
      <div className="flex space-x-1 p-1 border-b mb-1">
        <button type="button" className="text-xs p-1 hover:bg-muted rounded" title={t('markdown.bold')}>B</button>
        <button type="button" className="text-xs p-1 hover:bg-muted rounded" title={t('markdown.italic')}>I</button>
        <button type="button" className="text-xs p-1 hover:bg-muted rounded" title={t('markdown.link')}>{t('markdown.link')}</button>
      </div>
      <textarea
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder ?? t('markdown.enterContent')}
        rows={rows}
        className={`${className} focus:ring-0 focus:outline-hidden resize-y min-h-[150px]`}
        aria-label={t('markdown.editorLabel')}
      />
      <div className="text-xs text-muted-foreground p-1 border-t mt-1">
        {t('markdown.supportedNotice')}
      </div>
    </div>
  );
};

export default MarkdownEditor;