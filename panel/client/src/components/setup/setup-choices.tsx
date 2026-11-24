import { createContext, use, useMemo, useState, type ReactNode } from 'react';
import type { SetupStepId } from '@/lib/setup-steps';

interface SetupChoices {
  choices: ReadonlyMap<SetupStepId, string>;
  remember: (step: SetupStepId, choice: string) => void;
}

const SetupChoicesContext = createContext<SetupChoices | null>(null);

export function SetupChoicesProvider({ children }: { children: ReactNode }) {
  const [choices, setChoices] = useState<ReadonlyMap<SetupStepId, string>>(() => new Map());
  const value = useMemo<SetupChoices>(() => ({
    choices,
    remember: (step, choice) => {
      setChoices((current) => new Map(current).set(step, choice));
    },
  }), [choices]);
  return <SetupChoicesContext value={value}>{children}</SetupChoicesContext>;
}

export function useRememberedChoice(step: SetupStepId): [string | undefined, (choice: string) => void] {
  const store = use(SetupChoicesContext);
  if (store === null) throw new Error('useRememberedChoice must be used inside a SetupChoicesProvider');
  return [store.choices.get(step), (choice: string) => {
    store.remember(step, choice);
  }];
}
