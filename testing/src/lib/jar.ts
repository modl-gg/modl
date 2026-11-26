import type { Readable } from "node:stream";
import yauzl, { type Entry, type ZipFile } from "yauzl";

async function openZipFile(file: string): Promise<ZipFile> {
  return new Promise((resolve, reject) => {
    yauzl.open(file, { lazyEntries: true, autoClose: false }, (error: Error | null, zip: ZipFile | undefined) => {
      if (error || !zip) reject(error ?? new Error(`cannot open ${file}`));
      else resolve(zip);
    });
  });
}

async function openZipBuffer(buffer: Buffer, label: string): Promise<ZipFile> {
  return new Promise((resolve, reject) => {
    yauzl.fromBuffer(buffer, { lazyEntries: true }, (error: Error | null, zip: ZipFile | undefined) => {
      if (error || !zip) reject(error ?? new Error(`cannot open ${label}`));
      else resolve(zip);
    });
  });
}

async function readEntryBytes(zip: ZipFile, entry: Entry): Promise<Buffer> {
  return new Promise((resolve, reject) => {
    zip.openReadStream(entry, (error: Error | null, stream: Readable | undefined) => {
      if (error || !stream) {
        reject(error ?? new Error(`cannot read ${entry.fileName}`));
        return;
      }
      const chunks: Buffer[] = [];
      stream.on("data", (chunk: Buffer) => chunks.push(chunk));
      stream.once("error", reject);
      stream.once("end", () => resolve(Buffer.concat(chunks)));
    });
  });
}

export interface JarVisitor {
  wants(entryName: string): boolean;
  visit(entryName: string, read: () => Promise<Buffer>): Promise<void> | void;
}

async function walk(zip: ZipFile, visitor: JarVisitor): Promise<void> {
  return new Promise<void>((resolve, reject) => {
    zip.on("entry", (entry: Entry) => {
      if (!visitor.wants(entry.fileName)) {
        zip.readEntry();
        return;
      }
      Promise.resolve(visitor.visit(entry.fileName, async () => readEntryBytes(zip, entry))).then(() => zip.readEntry(), reject);
    });
    zip.once("end", () => resolve());
    zip.once("error", reject);
    zip.readEntry();
  });
}

export async function walkJar(jar: string, visitor: JarVisitor): Promise<void> {
  const zip = await openZipFile(jar);
  try {
    await walk(zip, visitor);
  } finally {
    zip.close();
  }
}

export async function walkJarBytes(bytes: Buffer, label: string, visitor: JarVisitor): Promise<void> {
  await walk(await openZipBuffer(bytes, label), visitor);
}

export async function readJarEntry(jar: string, entryName: string): Promise<string | null> {
  let content: string | null = null;
  await walkJar(jar, {
    wants: (name) => content === null && name === entryName,
    visit: async (_, read) => {
      content = (await read()).toString("utf8");
    },
  });
  return content;
}
