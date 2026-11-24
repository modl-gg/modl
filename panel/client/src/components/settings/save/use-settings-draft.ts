import { useLayoutEffect, useRef, useState, type SetStateAction } from 'react';
import type { SettingsSaveResult, SettingsWrite, VersionedSettings } from '@/hooks/data/settings';
import { editDraft, sameValue, settleDraft, type Draft, type Loaded } from '@/lib/settings-save-model';
import { useSaveFailureReporter, type SaveRejectionDescriber } from './save-failure';
import { useSettingsSaveSource } from './settings-save-scope';

export interface SettingsDraftOptions<Snapshot, Value> {
  snapshot: Snapshot | undefined;
  fallback: Value;
  valueOf: (snapshot: Snapshot) => Value;
  save: (value: Value, base: Snapshot) => Promise<Snapshot | null>;
  isValid?: ((value: Value) => boolean) | undefined;
  describeRejection?: SaveRejectionDescriber | undefined;
}

export interface SettingsDraft<Value> {
  value: Value;
  update: (action: SetStateAction<Value>) => void;
  isDirty: boolean;
  isValid: boolean;
  save: () => Promise<boolean>;
}

function isUpdater<T>(action: SetStateAction<T>): action is (previous: T) => T {
  return typeof action === 'function';
}

export function useSettingsDraft<Snapshot, Value>({
  snapshot,
  fallback,
  valueOf,
  save,
  isValid,
  describeRejection,
}: SettingsDraftOptions<Snapshot, Value>): SettingsDraft<Value> {
  const [draft, setDraft] = useState<Draft<Snapshot, Value> | null>(null);
  const reportFailure = useSaveFailureReporter();
  const loaded: Loaded<Snapshot, Value> | null = snapshot === undefined ? null : { snapshot, value: valueOf(snapshot) };
  const loadedRef = useRef(loaded);
  const inFlightRef = useRef<Draft<Snapshot, Value> | null>(null);
  const value = draft?.value ?? loaded?.value ?? fallback;
  const isDirty = draft !== null && loaded !== null && !sameValue(draft.value, loaded.value);

  useLayoutEffect(() => {
    loadedRef.current = loaded;
  });

  const update = (action: SetStateAction<Value>) => {
    setDraft((current) => {
      const latest = loadedRef.current;
      if (latest === null) return current;
      const previous = current?.value ?? latest.value;
      return editDraft(current, isUpdater(action) ? action(previous) : action, latest, inFlightRef.current !== null);
    });
  };

  const valid = isValid?.(value) ?? true;

  const dropUnchangedDraft = () => {
    setDraft((current) => {
      const latest = loadedRef.current;
      return current !== null && latest !== null && sameValue(current.value, latest.value) ? null : current;
    });
  };

  const saveDraft = async (): Promise<boolean> => {
    if (draft === null) return true;
    inFlightRef.current = draft;
    try {
      const saved = await save(draft.value, draft.base);
      if (saved === null) {
        dropUnchangedDraft();
        return false;
      }
      setDraft((current) => settleDraft(current, draft, { snapshot: saved, value: valueOf(saved) }));
      return true;
    } catch (error) {
      reportFailure(error, describeRejection);
      dropUnchangedDraft();
      return false;
    } finally {
      inFlightRef.current = null;
    }
  };

  useSettingsSaveSource({
    isDirty,
    isValid: valid,
    save: saveDraft,
    reset: () => {
      setDraft(null);
    },
  });

  return { value, update, isDirty, isValid: valid, save: saveDraft };
}

export interface VersionedSettingsDraftOptions<Value> {
  persisted: VersionedSettings<Value> | null | undefined;
  fallback: Value;
  save: (write: SettingsWrite<Value>) => Promise<SettingsSaveResult<Value>>;
  isValid?: ((value: Value) => boolean) | undefined;
  describeRejection?: SaveRejectionDescriber | undefined;
}

function valueOfVersioned<Value>(settings: VersionedSettings<Value>): Value {
  return settings.value;
}

export function useVersionedSettingsDraft<Value>({
  persisted,
  fallback,
  save,
  isValid,
  describeRejection,
}: VersionedSettingsDraftOptions<Value>): SettingsDraft<Value> {
  return useSettingsDraft({
    snapshot: persisted ?? undefined,
    fallback,
    valueOf: valueOfVersioned,
    save: async (value, base) => (await save({ expectedVersion: base.version, value })).saved,
    isValid,
    describeRejection,
  });
}
