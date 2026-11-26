import { createHash } from "node:crypto";
import { crc32, deflateRawSync } from "node:zlib";

export const ARCHIVE_COLLECTIONS = [
  "chat_logs",
  "command_logs",
  "homepagecards",
  "knowledgebasearticles",
  "knowledgebasecategories",
  "logs",
  "players",
  "replays",
  "settings",
  "staffroles",
  "staffs",
  "storage_files",
  "tickets",
] as const;

export type ArchiveCollection = (typeof ARCHIVE_COLLECTIONS)[number];

export interface ArchiveObject {
  key: string;
  content: Buffer;
  contentType: string;
}

export interface HostedArchiveContent {
  exportId: string;
  createdAt: Date;
  serverName: string;
  subdomain: string;
  adminEmail: string;
  keyPrefix: string;
  publicBaseUrls: readonly string[];
  collections: Partial<Record<ArchiveCollection, readonly string[]>>;
  objects: readonly ArchiveObject[];
  skippedObjects: readonly { key: string; reason: string }[];
  excludedCollections: readonly { name: string; reason: string; approximateDocuments: number }[];
}

export interface ArchiveTampering {
  wrongHashOf?: ArchiveCollection;
}

interface ZipEntry {
  path: string;
  content: Buffer;
}

const LOCAL_HEADER = 0x04034b50;
const CENTRAL_HEADER = 0x02014b50;
const END_RECORD = 0x06054b50;
const LOCAL_HEADER_SIZE = 30;
const CENTRAL_HEADER_SIZE = 46;
const END_RECORD_SIZE = 22;
const VERSION_DEFLATE = 20;
const UTF8_NAMES = 0x0800;
const DEFLATE = 8;
const EARLIEST_DOS_DATE = 0x21;
const SERVER_ID = "665f1c2e8b3e4a0012345678";
const WRONG_HASH = "0".repeat(64);

export function sha256(content: Buffer): string {
  return createHash("sha256").update(content).digest("hex");
}

function zipped(entries: readonly ZipEntry[]): Buffer {
  const files: Buffer[] = [];
  const directory: Buffer[] = [];
  let offset = 0;
  for (const { path, content } of entries) {
    const name = Buffer.from(path, "utf8");
    const compressed = deflateRawSync(content, { level: 1 });
    const checksum = crc32(content);
    const local = Buffer.alloc(LOCAL_HEADER_SIZE);
    local.writeUInt32LE(LOCAL_HEADER, 0);
    local.writeUInt16LE(VERSION_DEFLATE, 4);
    local.writeUInt16LE(UTF8_NAMES, 6);
    local.writeUInt16LE(DEFLATE, 8);
    local.writeUInt16LE(EARLIEST_DOS_DATE, 12);
    local.writeUInt32LE(checksum, 14);
    local.writeUInt32LE(compressed.length, 18);
    local.writeUInt32LE(content.length, 22);
    local.writeUInt16LE(name.length, 26);
    const central = Buffer.alloc(CENTRAL_HEADER_SIZE);
    central.writeUInt32LE(CENTRAL_HEADER, 0);
    central.writeUInt16LE(VERSION_DEFLATE, 4);
    local.copy(central, 6, 4, LOCAL_HEADER_SIZE);
    central.writeUInt32LE(offset, 42);
    files.push(local, name, compressed);
    directory.push(central, name);
    offset += local.length + name.length + compressed.length;
  }
  const centralDirectory = Buffer.concat(directory);
  const end = Buffer.alloc(END_RECORD_SIZE);
  end.writeUInt32LE(END_RECORD, 0);
  end.writeUInt16LE(entries.length, 8);
  end.writeUInt16LE(entries.length, 10);
  end.writeUInt32LE(centralDirectory.length, 12);
  end.writeUInt32LE(offset, 16);
  return Buffer.concat([...files, centralDirectory, end]);
}

function ndjson(lines: readonly string[]): Buffer {
  return Buffer.from(lines.map((line) => `${line}\n`).join(""), "utf8");
}

export function buildHostedArchive(content: HostedArchiveContent, tampering: ArchiveTampering = {}): Buffer {
  const server = {
    path: "server.json",
    content: Buffer.from(
      JSON.stringify({
        id: SERVER_ID,
        serverName: content.serverName,
        subdomain: content.subdomain,
        adminEmail: content.adminEmail,
        createdAt: null,
      }),
    ),
  };
  const collections = ARCHIVE_COLLECTIONS.map((name) => {
    const lines = content.collections[name] ?? [];
    return { name, path: `collections/${name}.ndjson`, documents: lines.length, content: ndjson(lines) };
  });
  const objects = content.objects
    .map((object) => ({ ...object, path: `storage/${object.key}` }))
    .sort((left, right) => Buffer.compare(Buffer.from(left.key, "utf8"), Buffer.from(right.key, "utf8")));
  const collectionBytes = collections.reduce((total, collection) => total + collection.content.length, 0);
  const storageBytes = objects.reduce((total, object) => total + object.content.length, 0);
  const manifest = {
    format: "modl-hosted-export",
    formatVersion: 1,
    exportId: content.exportId,
    createdAt: content.createdAt.toISOString(),
    completedAt: content.createdAt.toISOString(),
    source: {
      backendVersion: "2.3.1",
      serverId: SERVER_ID,
      serverName: content.serverName,
      subdomain: content.subdomain,
      panelDomain: `${content.subdomain}.modl.gg`,
      defaultPanelDomain: `${content.subdomain}.modl.gg`,
    },
    server: { path: server.path, size: server.content.length, sha256: sha256(server.content) },
    collections: collections.map((collection) => ({
      name: collection.name,
      path: collection.path,
      documents: collection.documents,
      omittedDocuments: 0,
      size: collection.content.length,
      sha256: tampering.wrongHashOf === collection.name ? WRONG_HASH : sha256(collection.content),
    })),
    excludedCollections: content.excludedCollections,
    storage: {
      keyPrefix: content.keyPrefix,
      publicBaseUrls: content.publicBaseUrls,
      objects: objects.map((object) => ({
        key: object.key,
        path: object.path,
        size: object.content.length,
        contentType: object.contentType,
        sha256: sha256(object.content),
      })),
      skippedObjects: content.skippedObjects,
    },
    totals: {
      documents: collections.reduce((total, collection) => total + collection.documents, 0),
      collectionBytes,
      storageObjects: objects.length,
      storageBytes,
      uncompressedBytes: server.content.length + collectionBytes + storageBytes,
    },
  };
  return zipped([server, ...collections, ...objects, { path: "manifest.json", content: Buffer.from(JSON.stringify(manifest)) }]);
}
