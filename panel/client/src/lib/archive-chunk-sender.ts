import { fromJsonString } from '@bufbuild/protobuf';
import { HostedImportStateSchema, type HostedImportState } from '@proto/modl/v1/hosted_import_pb.ts';
import { apiFetch } from '@/lib/api';
import { PAYLOAD_TOO_LARGE, TOO_MANY_REQUESTS, type ArchiveUploadPlan, type ChunkSender } from '@/lib/archive-upload';
import { ApiHttpError } from '@/lib/http-error';
import { READ_OPTS, protoHttpErrorOf } from '@/lib/proto-fetch';

export function httpChunkSender(plan: Pick<ArchiveUploadPlan, 'path' | 'importId'>): ChunkSender<HostedImportState> {
  return async (offset, chunk, signal) => {
    const query = new URLSearchParams({ importId: plan.importId, offset: String(offset) });
    let response: Response;
    try {
      response = await apiFetch(`${plan.path}?${query.toString()}`, {
        method: 'PUT',
        body: chunk,
        headers: { 'Content-Type': 'application/octet-stream' },
        rateLimitFeedback: 'caller',
        signal,
      });
    } catch (error: unknown) {
      if (error instanceof ApiHttpError && error.status === TOO_MANY_REQUESTS) {
        return { status: TOO_MANY_REQUESTS, retryAfterSeconds: error.retryAfterSeconds, state: null };
      }
      throw error;
    }
    if (response.ok) {
      return { status: response.status, retryAfterSeconds: null, state: fromJsonString(HostedImportStateSchema, await response.text(), READ_OPTS) };
    }
    if (response.status === PAYLOAD_TOO_LARGE) return { status: response.status, retryAfterSeconds: null, state: null };
    throw await protoHttpErrorOf(response);
  };
}
