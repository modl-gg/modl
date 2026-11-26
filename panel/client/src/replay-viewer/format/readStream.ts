export type StreamProgress = (loadedBytes: number) => void;

export async function readStream(stream: ReadableStream<Uint8Array>, limit: number, overflow: () => Error, onChunk: StreamProgress = () => undefined): Promise<Uint8Array<ArrayBuffer>> {
  const reader = stream.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > limit) {
        await reader.cancel();
        throw overflow();
      }
      chunks.push(value);
      onChunk(total);
    }
  } finally {
    reader.releaseLock();
  }
  const output = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    output.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return output;
}
