import { useTranslation } from 'react-i18next';
import { Check, Copy } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { useCopyToClipboard } from '@/hooks/use-copy-to-clipboard';
import { cn } from '@/lib/utils';

interface CopyButtonProps {
  value: string;
  label: string;
  failureMessage: string;
  className?: string | undefined;
}

function CopiedStatus({ copied }: { copied: boolean }) {
  const { t } = useTranslation();

  return <span className="sr-only" role="status">{copied ? t('common.copied') : ''}</span>;
}

interface CopyIconButtonProps {
  copied: boolean;
  label: string;
  onCopy: (origin: Element) => void;
  disabled?: boolean | undefined;
  size?: 'sm' | 'icon' | undefined;
  className?: string | undefined;
  onPointerEnter?: (() => void) | undefined;
  onFocus?: (() => void) | undefined;
}

export function CopyIconButton({ copied, label, onCopy, disabled, size = 'sm', className, onPointerEnter, onFocus }: CopyIconButtonProps) {
  return (
    <>
      <Button
        type="button"
        variant="outline"
        size={size}
        className={className}
        aria-label={label}
        title={label}
        disabled={disabled}
        onClick={(event) => { onCopy(event.currentTarget); }}
        onPointerEnter={onPointerEnter}
        onFocus={onFocus}
      >
        {copied ? <Check className="h-4 w-4" aria-hidden="true" /> : <Copy className="h-4 w-4" aria-hidden="true" />}
      </Button>
      <CopiedStatus copied={copied} />
    </>
  );
}

export function CopyButton({ value, label, failureMessage, className }: CopyButtonProps) {
  const { copy, copiedValue } = useCopyToClipboard(failureMessage);

  return (
    <CopyIconButton
      copied={copiedValue === value}
      label={label}
      className={className}
      onCopy={(origin) => { void copy(value, origin); }}
    />
  );
}

interface CopyFieldProps extends CopyButtonProps {
  id: string;
  fieldLabel: string;
}

export function CopyField({ id, fieldLabel, value, label, failureMessage, className }: CopyFieldProps) {
  return (
    <div className={cn('space-y-1', className)}>
      <Label htmlFor={id} className="text-sm font-medium">{fieldLabel}</Label>
      <div className="flex items-center gap-2">
        <Input
          id={id}
          readOnly
          value={value}
          className="text-xs font-mono"
          onFocus={(event) => event.currentTarget.select()}
        />
        <CopyButton value={value} label={label} failureMessage={failureMessage} />
      </div>
    </div>
  );
}
