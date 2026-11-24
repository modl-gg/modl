import { useTranslation } from 'react-i18next';
import { Textarea } from '@/components/ui/textarea';
import { MAX_PUBLIC_REPLY_CHARACTERS } from '@/lib/public-reply';

interface PublicReplyInputProps {
  id: string;
  value: string;
  onChange: (value: string) => void;
}

export function PublicReplyInput({ id, value, onChange }: PublicReplyInputProps) {
  const { t } = useTranslation();
  return (
    <div className="space-y-1">
      <Textarea
        id={id}
        value={value}
        maxLength={MAX_PUBLIC_REPLY_CHARACTERS}
        onChange={(event) => { onChange(event.target.value); }}
        className="min-h-[100px]"
      />
      <p className="text-right text-xs text-muted-foreground">
        {t('publicReply.characterCount', { used: value.length, max: MAX_PUBLIC_REPLY_CHARACTERS })}
      </p>
    </div>
  );
}
