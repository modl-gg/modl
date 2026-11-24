import { AlertCircle, ArrowLeft, ArrowUpRight, CheckCircle2, Clock, FileText, Lock, ThumbsUp, XCircle } from 'lucide-react';
import type { ActionIconKind } from './ticket-action-badge';

export function ActionIcon({ kind, className }: { kind: ActionIconKind; className: string }) {
  switch (kind) {
    case 'accepted':
      return <CheckCircle2 className={className} />;
    case 'rejected':
      return <XCircle className={className} />;
    case 'pardoned':
      return <ThumbsUp className={className} />;
    case 'reduced':
      return <ArrowLeft className={className} />;
    case 'waiting':
      return <Clock className={className} />;
    case 'investigating':
      return <AlertCircle className={className} />;
    case 'escalated':
      return <ArrowUpRight className={className} />;
    case 'closed':
      return <Lock className={className} />;
    case 'generic':
      return <FileText className={className} />;
  }
}
