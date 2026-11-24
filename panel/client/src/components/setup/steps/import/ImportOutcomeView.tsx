import type { ImportState } from '@/hooks/data/hosted-import';
import { ImportPaused, ImportReport } from './ImportOutcome';
import { ImportReview } from './ImportReview';
import { ImportProgress } from './ImportTransfer';

export function ImportOutcomeView({ state }: { state: ImportState }) {
  switch (state.status) {
    case 'review':
      return <ImportReview state={state} />;
    case 'applying':
      return <ImportProgress state={state} />;
    case 'paused':
      return <ImportPaused state={state} />;
    case 'completed':
    case 'abandoned':
      return <ImportReport />;
    case 'none':
    case 'failed':
    case 'receiving':
    case 'downloading':
    case 'verifying':
      return null;
  }
}
