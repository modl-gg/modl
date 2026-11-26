import type { AssetLoadFailureKind } from '../AssetLibrary.ts';
import { isRecord } from '../json.ts';
import type { SourceRole } from './AssetSelection.ts';

export interface LoadRequest {
  readonly version: string;
}

export interface ProgressMessage {
  readonly type: 'progress';
  readonly loaded: number;
  readonly total: number;
}

export interface BundleMessage {
  readonly role: SourceRole;
  readonly version: string;
  readonly bytes: ArrayBuffer;
}

export interface DoneMessage {
  readonly type: 'done';
  readonly bundles: readonly BundleMessage[];
}

export interface FailureMessage {
  readonly type: 'failure';
  readonly kind: AssetLoadFailureKind;
  readonly message: string;
}

export type LoaderMessage = ProgressMessage | DoneMessage | FailureMessage;

const FAILURE_KINDS: readonly AssetLoadFailureKind[] = ['network', 'unsupported_version', 'aborted', 'corrupt'];

function failureKind(value: unknown): AssetLoadFailureKind | null {
  return FAILURE_KINDS.find((kind) => kind === value) ?? null;
}

function bundle(value: unknown): BundleMessage | null {
  if (!isRecord(value)) return null;
  const role = value['role'];
  const version = value['version'];
  const bytes = value['bytes'];
  if ((role !== 'primary' && role !== 'models') || typeof version !== 'string' || !(bytes instanceof ArrayBuffer)) return null;
  return { role, version, bytes };
}

export function parseLoadRequest(value: unknown): LoadRequest | null {
  return isRecord(value) && typeof value['version'] === 'string' ? { version: value['version'] } : null;
}

export function parseLoaderMessage(value: unknown): LoaderMessage | null {
  if (!isRecord(value)) return null;
  switch (value['type']) {
    case 'progress': {
      const loaded = value['loaded'];
      const total = value['total'];
      return typeof loaded === 'number' && typeof total === 'number' ? { type: 'progress', loaded, total } : null;
    }
    case 'done': {
      const raw = value['bundles'];
      if (!Array.isArray(raw)) return null;
      const bundles = raw.map(bundle);
      return bundles.every((entry) => entry !== null) ? { type: 'done', bundles } : null;
    }
    case 'failure': {
      const kind = failureKind(value['kind']);
      const message = value['message'];
      return kind !== null && typeof message === 'string' ? { type: 'failure', kind, message } : null;
    }
    default:
      return null;
  }
}
