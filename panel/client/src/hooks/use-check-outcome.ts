import { useState } from 'react';

export type CheckOutcome<Result> = { kind: 'result'; result: Result } | { kind: 'error'; error: unknown };

interface CheckedSource<Result> {
  source: string;
  outcome: CheckOutcome<Result>;
}

export interface CheckOutcomeState<Result> {
  outcome: CheckOutcome<Result> | null;
  run: (execute: () => Promise<Result>) => Promise<void>;
  carry: (from: string, to: string) => void;
}

export function useCheckOutcome<Result>(source: string): CheckOutcomeState<Result> {
  const [checked, setChecked] = useState<CheckedSource<Result> | null>(null);

  const run = async (execute: () => Promise<Result>) => {
    const started = source;
    setChecked(null);
    try {
      setChecked({ source: started, outcome: { kind: 'result', result: await execute() } });
    } catch (error) {
      setChecked({ source: started, outcome: { kind: 'error', error } });
    }
  };

  const carry = (from: string, to: string) => {
    setChecked((current) => (current?.source === from ? { source: to, outcome: current.outcome } : current));
  };

  return { outcome: checked?.source === source ? checked.outcome : null, run, carry };
}

export function checkPassed<Result>(outcome: CheckOutcome<Result> | null, passed: (result: Result) => boolean): boolean | null {
  if (outcome === null) return null;
  return outcome.kind === 'result' && passed(outcome.result);
}
