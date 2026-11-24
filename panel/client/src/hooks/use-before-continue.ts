import { createContext, use, useEffect, useId, useRef } from 'react';

export type BeforeContinue = () => Promise<boolean>;

export interface ContinueGate {
  register: (handler: BeforeContinue) => () => void;
  reportReady: (reporter: string, ready: boolean) => void;
}

const OPEN_GATE: ContinueGate = {
  register: () => () => undefined,
  reportReady: () => undefined,
};

export const ContinueGateContext = createContext<ContinueGate>(OPEN_GATE);

export function useBeforeContinue(handler: BeforeContinue): void {
  const { register } = use(ContinueGateContext);
  const latestHandlerRef = useRef(handler);

  useEffect(() => {
    latestHandlerRef.current = handler;
  });

  useEffect(() => register(async () => latestHandlerRef.current()), [register]);
}

export function useContinueGateActive(): boolean {
  return use(ContinueGateContext) !== OPEN_GATE;
}

export function useContinueReady(ready: boolean): void {
  const { reportReady } = use(ContinueGateContext);
  const reporterId = useId();

  useEffect(() => {
    reportReady(reporterId, ready);
    return () => {
      reportReady(reporterId, true);
    };
  }, [reportReady, reporterId, ready]);
}

export async function allowContinue(handlers: Iterable<BeforeContinue>): Promise<boolean> {
  for (const handler of handlers) {
    if (!(await handler())) return false;
  }
  return true;
}
