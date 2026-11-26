import { createContext, use, useMemo, useState, type ReactNode } from 'react';
import type { SetupStepId } from '@/lib/setup-steps';

type ChoiceKey = `${SetupStepId}:${string}`;

interface SetupChoices {
  choices: ReadonlyMap<ChoiceKey, string>;
  remember: (key: ChoiceKey, choice: string) => void;
}

const SetupChoicesContext = createContext<SetupChoices | null>(null);

export function SetupChoicesProvider({ children }: { children: ReactNode }) {
  const [choices, setChoices] = useState<ReadonlyMap<ChoiceKey, string>>(() => new Map());
  const value = useMemo<SetupChoices>(() => ({
    choices,
    remember: (key, choice) => {
      setChoices((current) => new Map(current).set(key, choice));
    },
  }), [choices]);
  return <SetupChoicesContext value={value}>{children}</SetupChoicesContext>;
}

export function useRememberedChoice(step: SetupStepId, slot = ''): [string | undefined, (choice: string) => void] {
  const store = use(SetupChoicesContext);
  if (store === null) throw new Error('useRememberedChoice must be used inside a SetupChoicesProvider');
  const key: ChoiceKey = `${step}:${slot}`;
  return [store.choices.get(key), (choice: string) => {
    store.remember(key, choice);
  }];
}
