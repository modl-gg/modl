import { apiErrorCode } from '@/lib/http-error';

const ERROR_KEYS: Record<string, string> = {
  UPDATE_CHECK_DISABLED: 'updates.error.checkDisabled',
  UPDATE_VERSION_STALE: 'updates.error.stale',
  UPDATE_NOT_INSTALLABLE: 'updates.error.notInstallable',
  UPDATE_PLUGIN_ACK_REQUIRED: 'updates.error.acknowledgementRequired',
  UPDATE_IN_PROGRESS: 'updates.error.inProgress',
};

export function updateErrorKey(error: unknown): string {
  const code = apiErrorCode(error);
  return (code === null ? undefined : ERROR_KEYS[code]) ?? 'updates.error.generic';
}
