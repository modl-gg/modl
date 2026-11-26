const UTF8_ENCODER = new TextEncoder();
const UTF8_DECODER = new TextDecoder('utf-8');
const MAGIC = 0x4d524131;

export type FileMap = ReadonlyMap<string, Uint8Array<ArrayBuffer>>;

export function packFiles(files: FileMap): ArrayBuffer {
  const names = [...files.keys()].sort();
  let size = 8;
  const encoded = names.map((name) => UTF8_ENCODER.encode(name));
  names.forEach((name, index) => {
    size += 8 + (encoded[index]?.byteLength ?? 0) + (files.get(name)?.byteLength ?? 0);
  });
  const buffer = new ArrayBuffer(size);
  const view = new DataView(buffer);
  const bytes = new Uint8Array(buffer);
  view.setUint32(0, MAGIC);
  view.setUint32(4, names.length);
  let offset = 8;
  names.forEach((name, index) => {
    const nameBytes = encoded[index] ?? new Uint8Array();
    const data = files.get(name) ?? new Uint8Array();
    view.setUint32(offset, nameBytes.byteLength);
    view.setUint32(offset + 4, data.byteLength);
    offset += 8;
    bytes.set(nameBytes, offset);
    offset += nameBytes.byteLength;
    bytes.set(data, offset);
    offset += data.byteLength;
  });
  return buffer;
}

export function unpackFiles(buffer: ArrayBuffer): Map<string, Uint8Array<ArrayBuffer>> | null {
  if (buffer.byteLength < 8) return null;
  const view = new DataView(buffer);
  if (view.getUint32(0) !== MAGIC) return null;
  const count = view.getUint32(4);
  const files = new Map<string, Uint8Array<ArrayBuffer>>();
  let offset = 8;
  for (let index = 0; index < count; index++) {
    if (offset + 8 > buffer.byteLength) return null;
    const nameLength = view.getUint32(offset);
    const dataLength = view.getUint32(offset + 4);
    offset += 8;
    if (offset + nameLength + dataLength > buffer.byteLength) return null;
    const name = UTF8_DECODER.decode(new Uint8Array(buffer, offset, nameLength));
    offset += nameLength;
    files.set(name, new Uint8Array(buffer, offset, dataLength));
    offset += dataLength;
  }
  return files;
}
