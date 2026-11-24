import { createContext, use, useEffect, useId, useLayoutEffect, useRef, useState, useSyncExternalStore, type ReactNode } from 'react';
import { useNavigationBlocker } from '@/lib/guarded-location';
import { useSettingsPageCanModify } from '../layout/SettingsPageFrame';
import { saveDirtySources, summarizeSources, type SaveSourceStatus, type SaveSummary } from '@/lib/settings-save-model';
import { PageSubmitContext } from '@/components/ui/action-form';
import { SaveBar, useSaveBarAlert, type SaveBarExit } from './SaveBar';

const SAVED_FLASH_MS = 900;
const EXIT_FALLBACK_MS = 400;

export interface SaveSource extends SaveSourceStatus {
  save: () => Promise<boolean>;
  reset: () => void;
}

type SaveActions = Pick<SaveSource, 'save' | 'reset'>;

interface SaveRegistry {
  register: (id: string, actions: SaveActions) => () => void;
  report: (id: string, status: SaveSourceStatus) => void;
  hold: (id: string, held: boolean) => void;
  subscribe: (listener: () => void) => () => void;
  summary: () => SaveSummary;
  isHeld: () => boolean;
  saveAll: () => Promise<boolean>;
  resetAll: () => void;
}

function sameStatus(left: SaveSourceStatus | undefined, right: SaveSourceStatus): boolean {
  return left?.isDirty === right.isDirty && left.isValid === right.isValid;
}

function createSaveRegistry(): SaveRegistry {
  const statuses = new Map<string, SaveSourceStatus>();
  const actions = new Map<string, SaveActions>();
  const holds = new Set<string>();
  const listeners = new Set<() => void>();
  let summary = summarizeSources([]);

  const notify = () => {
    for (const listener of listeners) listener();
  };

  const publish = () => {
    summary = summarizeSources(statuses.values());
    notify();
  };

  return {
    register: (id, sourceActions) => {
      actions.set(id, sourceActions);
      return () => {
        actions.delete(id);
        if (statuses.delete(id)) publish();
      };
    },
    report: (id, status) => {
      if (sameStatus(statuses.get(id), status)) return;
      statuses.set(id, status);
      publish();
    },
    hold: (id, held) => {
      if (holds.has(id) === held) return;
      if (held) holds.add(id);
      else holds.delete(id);
      notify();
    },
    subscribe: (listener) => {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
    summary: () => summary,
    isHeld: () => holds.size > 0,
    saveAll: async () => saveDirtySources([...statuses].map(([id, status]) => ({
      status,
      save: async () => actions.get(id)?.save() ?? true,
    }))),
    resetAll: () => {
      for (const sourceActions of actions.values()) sourceActions.reset();
    },
  };
}

const SaveScopeContext = createContext<SaveRegistry | null>(null);

export function useSettingsSaveSource(source: SaveSource): void {
  const registry = use(SaveScopeContext);
  const id = useId();
  const latestRef = useRef<SaveActions>(source);

  useLayoutEffect(() => {
    latestRef.current = source;
  });

  useLayoutEffect(() => {
    if (registry === null) return undefined;
    return registry.register(id, {
      save: async () => latestRef.current.save(),
      reset: () => {
        latestRef.current.reset();
      },
    });
  }, [registry, id]);

  const { isDirty, isValid } = source;
  useLayoutEffect(() => {
    registry?.report(id, { isDirty, isValid });
  }, [registry, id, isDirty, isValid]);
}

export function useHoldSaveBarWhile(held: boolean): void {
  const registry = use(SaveScopeContext);
  const id = useId();

  useLayoutEffect(() => {
    if (registry === null) return undefined;
    registry.hold(id, held);
    return () => {
      registry.hold(id, false);
    };
  }, [registry, id, held]);
}

export function SettingsSaveScope({ children }: { children: ReactNode }) {
  const [registry] = useState(createSaveRegistry);
  const summary = useSyncExternalStore(registry.subscribe, registry.summary);
  const held = useSyncExternalStore(registry.subscribe, registry.isHeld);
  const editable = useSettingsPageCanModify();
  const isDirty = editable && summary.isDirty;
  const [settledDirty, setSettledDirty] = useState(isDirty);
  if (!held && settledDirty !== isDirty) setSettledDirty(isDirty);
  const showChanges = held ? settledDirty : isDirty;
  const [saving, setSaving] = useState(false);
  const alert = useSaveBarAlert();

  useNavigationBlocker(isDirty, alert.raise);

  const [exit, setExit] = useState<SaveBarExit | null>(null);
  const [shownChanges, setShownChanges] = useState(showChanges);
  if (shownChanges !== showChanges) {
    setShownChanges(showChanges);
    if (showChanges) setExit(null);
    else if (exit === null && !saving) setExit({ outcome: 'reset', leaving: true });
  }

  useEffect(() => {
    if (exit === null) return undefined;
    const timer = exit.leaving
      ? setTimeout(() => { setExit(null); }, EXIT_FALLBACK_MS)
      : setTimeout(() => { setExit({ outcome: exit.outcome, leaving: true }); }, SAVED_FLASH_MS);
    return () => { clearTimeout(timer); };
  }, [exit]);

  const save = async () => {
    setExit(null);
    setSaving(true);
    try {
      if (await registry.saveAll()) setExit({ outcome: 'saved', leaving: false });
    } finally {
      setSaving(false);
    }
  };

  const reset = () => {
    setExit({ outcome: 'reset', leaving: true });
    registry.resetAll();
  };

  const canSave = isDirty && summary.isValid && !saving;
  const pageSubmit = { submit: () => { void save(); }, canSubmit: canSave };

  return (
    <SaveScopeContext value={registry}>
      <PageSubmitContext value={pageSubmit}>{children}</PageSubmitContext>
      <SaveBar
        visible={showChanges || saving}
        canSave={canSave}
        saving={saving}
        alert={alert}
        exit={exit}
        onSave={() => { void save(); }}
        onReset={reset}
        onExited={() => { setExit(null); }}
      />
    </SaveScopeContext>
  );
}
