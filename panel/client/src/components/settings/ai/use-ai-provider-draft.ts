import { useState } from 'react';
import type { AiSettingsState } from '@/hooks/data/ai';
import { draftOf, savedConnectionFor, type AiDraft, type AiDraftEdits } from '@/lib/ai-connection';

interface ProviderEdits {
  providerId: string;
  edits: AiDraftEdits | null;
}

export interface AiProviderDraft {
  providerId: string;
  draft: AiDraft | null;
  edits: AiDraftEdits | null;
  changed: boolean;
  choose: (providerId: string) => void;
  edit: (change: AiDraftEdits) => void;
  reset: () => void;
}

export function useAiProviderDraft(state: AiSettingsState): AiProviderDraft {
  const [local, setLocal] = useState<ProviderEdits | null>(null);
  const providerId = local?.providerId ?? state.activeProvider ?? '';
  const provider = state.providers.find((candidate) => candidate.id === providerId);
  const edits = local?.edits ?? null;
  const draft = provider ? draftOf(provider, savedConnectionFor(state.connections, providerId), edits) : null;

  return {
    providerId,
    draft,
    edits,
    changed: (draft?.dirty ?? false) || providerId !== (state.activeProvider ?? ''),
    choose: (chosen) => {
      setLocal({ providerId: chosen, edits: null });
    },
    edit: (change) => {
      setLocal({ providerId, edits: { ...edits, ...change } });
    },
    reset: () => {
      setLocal(null);
    },
  };
}
