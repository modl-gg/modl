import { ReplayFormatError } from '../format/ReplayModel.ts';
import type { PreparedReplay } from './PreparedReplay.ts';
import { type PreparationRequest, parsePreparationResponse } from './preparationProtocol.ts';

export class ReplayPreparationError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'ReplayPreparationError';
  }
}

export async function loadPreparedReplay(buffer: ArrayBuffer, signal: AbortSignal): Promise<PreparedReplay> {
  signal.throwIfAborted();
  const worker = new Worker(new URL('./prepareReplay.worker.ts', import.meta.url), { type: 'module', name: 'modl-replay-prepare' });
  return new Promise<PreparedReplay>((resolve, reject) => {
    const finish = (): void => {
      signal.removeEventListener('abort', abort);
      worker.terminate();
    };
    const abort = (): void => {
      finish();
      reject(new DOMException('aborted', 'AbortError'));
    };
    signal.addEventListener('abort', abort, { once: true });
    worker.addEventListener('error', () => {
      finish();
      reject(new ReplayPreparationError('replay preparation worker failed'));
    });
    worker.addEventListener('messageerror', () => {
      finish();
      reject(new ReplayPreparationError('replay preparation message failed'));
    });
    worker.addEventListener('message', (event: MessageEvent<unknown>) => {
      finish();
      const response = parsePreparationResponse(event.data);
      if (response === null) reject(new ReplayPreparationError('unexpected replay preparation message'));
      else if (response.type === 'prepared') resolve(response.replay);
      else reject(response.format ? new ReplayFormatError(response.message) : new ReplayPreparationError(response.message));
    });
    const request: PreparationRequest = { buffer };
    worker.postMessage(request, [buffer]);
  });
}
