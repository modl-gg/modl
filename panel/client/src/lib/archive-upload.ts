export const MIN_CHUNK_BYTES = 512 * 1024;
export const PAYLOAD_TOO_LARGE = 413;
export const TOO_MANY_REQUESTS = 429;
const DEFAULT_RETRY_SECONDS = 5;
const MAX_RETRY_SECONDS = 60;

export interface ArchiveUploadPlan {
  path: string;
  importId: string;
  size: number;
  receivedBytes: number;
  maxChunkBytes: number;
}

export interface UploadProgress {
  readonly receivedBytes: bigint;
}

export interface ChunkResponse<State extends UploadProgress> {
  status: number;
  retryAfterSeconds: number | null;
  state: State | null;
}

export type ChunkSender<State extends UploadProgress> = (offset: number, chunk: Blob, signal: AbortSignal) => Promise<ChunkResponse<State>>;

export function nextChunkSize(current: number, status: number): number | null {
  if (status !== PAYLOAD_TOO_LARGE) return current;
  const halved = Math.floor(current / 2);
  return halved >= MIN_CHUNK_BYTES ? halved : null;
}

async function wait(seconds: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(resolve, Math.min(seconds, MAX_RETRY_SECONDS) * 1000);
    signal.addEventListener('abort', () => {
      clearTimeout(timer);
      reject(new DOMException('Upload aborted', 'AbortError'));
    }, { once: true });
  });
}

export async function uploadArchive<State extends UploadProgress>(
  file: Blob,
  plan: ArchiveUploadPlan,
  send: ChunkSender<State>,
  onProgress: (state: State) => void,
  signal: AbortSignal,
): Promise<void> {
  let offset = plan.receivedBytes;
  let chunkSize = Math.max(MIN_CHUNK_BYTES, plan.maxChunkBytes);
  while (offset < plan.size) {
    signal.throwIfAborted();
    const end = Math.min(plan.size, offset + chunkSize);
    const result = await send(offset, file.slice(offset, end), signal);
    if (result.status === TOO_MANY_REQUESTS) {
      await wait(result.retryAfterSeconds ?? DEFAULT_RETRY_SECONDS, signal);
      continue;
    }
    const resized = nextChunkSize(chunkSize, result.status);
    if (resized === null) throw new Error('chunk-too-large');
    if (resized !== chunkSize || result.state === null) {
      chunkSize = resized;
      continue;
    }
    offset = Number(result.state.receivedBytes);
    onProgress(result.state);
  }
}
