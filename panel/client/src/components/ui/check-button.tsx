import { Loader2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { CheckStatus } from '@/components/ui/check-status';

interface CheckButtonProps {
  label: string;
  pending: boolean;
  disabled?: boolean;
  passed: boolean | null;
  size?: 'default' | 'sm';
  onClick: () => void;
}

export function CheckButton({ label, pending, disabled = false, passed, size = 'default', onClick }: CheckButtonProps) {
  return (
    <div className="flex items-center gap-3">
      <Button type="button" variant="outline" size={size} disabled={disabled || pending} onClick={onClick}>
        {pending && <Loader2 className="animate-spin" aria-hidden="true" />}
        {label}
      </Button>
      <span role="status" className="flex items-center">{!pending && passed !== null && <CheckStatus state={passed ? 'passed' : 'failed'} />}</span>
    </div>
  );
}
