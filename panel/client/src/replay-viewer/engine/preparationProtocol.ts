import { isRecord } from '../format/guards.ts';
import { type PreparedReplay, parsePreparedReplay } from './PreparedReplay.ts';

export interface PreparationRequest {
  readonly buffer: ArrayBuffer;
}

export type PreparationResponse =
  | { readonly type: 'prepared'; readonly replay: PreparedReplay }
  | { readonly type: 'failure'; readonly format: boolean; readonly message: string };

export function parsePreparationRequest(value: unknown): PreparationRequest | null {
  return isRecord(value) && value['buffer'] instanceof ArrayBuffer ? { buffer: value['buffer'] } : null;
}

export function parsePreparationResponse(value: unknown): PreparationResponse | null {
  if (!isRecord(value)) return null;
  switch (value['type']) {
    case 'prepared': {
      const replay = parsePreparedReplay(value['replay']);
      return replay === null ? null : { type: 'prepared', replay };
    }
    case 'failure': {
      const { format, message } = value;
      return typeof format === 'boolean' && typeof message === 'string' ? { type: 'failure', format, message } : null;
    }
    default:
      return null;
  }
}
