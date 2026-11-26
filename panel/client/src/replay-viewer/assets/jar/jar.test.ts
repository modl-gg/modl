import test from 'node:test';
import assert from 'node:assert/strict';
import { deflateRawSync } from 'node:zlib';
import { AssetLoadError } from '../AssetLibrary.ts';
import { assetPath, sourceJars } from './AssetSelection.ts';
import { packFiles, unpackFiles } from './FileBundle.ts';
import { crc32 } from './crc32.ts';
import { JarReader } from './JarReader.ts';
import { trustedObjectUrl } from './mojangVersions.ts';
import { Zip64ArchiveError, readEndRecord } from './ZipDirectory.ts';
import { parseVersion } from '../../format/MinecraftVersion.ts';

function zip(entries: readonly { name: string; data: Uint8Array; corrupt?: boolean }[]): Uint8Array {
  const locals: Buffer[] = [];
  const centrals: Buffer[] = [];
  let offset = 0;
  for (const entry of entries) {
    const name = Buffer.from(entry.name);
    const compressed = deflateRawSync(entry.data);
    const crc = crc32(entry.data) ^ (entry.corrupt === true ? 1 : 0);
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(8, 8);
    local.writeUInt32LE(crc >>> 0, 14);
    local.writeUInt32LE(compressed.length, 18);
    local.writeUInt32LE(entry.data.length, 22);
    local.writeUInt16LE(name.length, 26);
    const central = Buffer.alloc(46);
    central.writeUInt32LE(0x02014b50, 0);
    central.writeUInt16LE(8, 10);
    central.writeUInt32LE(crc >>> 0, 16);
    central.writeUInt32LE(compressed.length, 20);
    central.writeUInt32LE(entry.data.length, 24);
    central.writeUInt16LE(name.length, 28);
    central.writeUInt32LE(offset, 42);
    locals.push(local, name, compressed);
    centrals.push(central, name);
    offset += 30 + name.length + compressed.length;
  }
  const directory = Buffer.concat(centrals);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(entries.length, 8);
  end.writeUInt16LE(entries.length, 10);
  end.writeUInt32LE(directory.length, 12);
  end.writeUInt32LE(offset, 16);
  return new Uint8Array(Buffer.concat([...locals, directory, end]));
}

function rangeFetcher(archive: Uint8Array): (url: string, init: RequestInit) => Promise<Response> {
  return async (_url, init) => {
    const header = new Headers(init.headers).get('Range') ?? '';
    const match = /^bytes=(\d+)-(\d+)$/.exec(header);
    if (match === null) return await Promise.resolve(new Response(archive.slice(), { status: 200 }));
    const start = Number(match[1]);
    const end = Math.min(archive.length - 1, Number(match[2]));
    return await Promise.resolve(new Response(archive.slice(start, end + 1), { status: 206 }));
  };
}

const JAR = { version: '26.1', url: 'https://piston-data.mojang.com/v1/objects/0000000000000000000000000000000000000000/client.jar', sha1: '0'.repeat(40), size: 0 };

void test('crc32 matches the reference check value', () => {
  assert.equal(crc32(new TextEncoder().encode('123456789')), 0xcbf43926);
});

void test('the jar reader range-reads only selected entries and verifies them', async () => {
  const text = new TextEncoder();
  const archive = zip([
    { name: 'assets/minecraft/blockstates/stone.json', data: text.encode('{"variants":{}}') },
    { name: 'net/minecraft/Main.class', data: new Uint8Array(5000) },
    { name: 'assets/minecraft/textures/block/stone.png', data: text.encode('png bytes') },
  ]);
  const reader = new JarReader({ ...JAR, size: archive.length }, rangeFetcher(archive));
  let received = 0;
  const directory = await reader.directory((bytes) => { received += bytes; });
  assert.equal(directory.length, 3);
  const plan = reader.plan(directory, (name) => name.startsWith('assets/'));
  const files = await reader.extract(plan, (bytes) => { received += bytes; });
  assert.deepEqual([...files.keys()].sort(), ['assets/minecraft/blockstates/stone.json', 'assets/minecraft/textures/block/stone.png']);
  assert.equal(new TextDecoder().decode(files.get('assets/minecraft/textures/block/stone.png')), 'png bytes');
  assert.ok(received > 0);
});

void test('an entry with a wrong CRC-32 is refused', async () => {
  const archive = zip([{ name: 'assets/minecraft/lang/en_us.json', data: new TextEncoder().encode('{}'), corrupt: true }]);
  const reader = new JarReader({ ...JAR, size: archive.length }, rangeFetcher(archive));
  const directory = await reader.directory(() => undefined);
  await assert.rejects(reader.extract(reader.plan(directory, () => true), () => undefined), (error) => error instanceof AssetLoadError && error.kind === 'corrupt');
});

void test('a server that ignores ranges is a network failure', async () => {
  const archive = zip([{ name: 'a', data: new Uint8Array(1) }]);
  const reader = new JarReader({ ...JAR, size: archive.length }, async () => await Promise.resolve(new Response(archive.slice(), { status: 200 })));
  await assert.rejects(reader.directory(() => undefined), (error) => error instanceof AssetLoadError && error.kind === 'network');
});

void test('only Mojang object URLs are trusted and legacy hosts map to piston-data', () => {
  const sha = 'a'.repeat(40);
  assert.equal(trustedObjectUrl(`https://launcher.mojang.com/v1/objects/${sha}/client.jar`, ['piston-data.mojang.com', 'launcher.mojang.com']), `https://piston-data.mojang.com/v1/objects/${sha}/client.jar`);
  assert.equal(trustedObjectUrl(`https://evil.example/v1/objects/${sha}/client.jar`, ['piston-data.mojang.com']), null);
  assert.equal(trustedObjectUrl(`http://piston-data.mojang.com/v1/objects/${sha}/client.jar`, ['piston-data.mojang.com']), null);
  assert.equal(trustedObjectUrl(`https://piston-data.mojang.com/v1/objects/${sha}/client.jar?x=1`, ['piston-data.mojang.com']), null);
});

void test('legacy versions take models from 1.13.2 and textures from their own jar', () => {
  const legacy = sourceJars(parseVersion('1.8.8'));
  assert.deepEqual(legacy.map((source) => [source.version, source.role]), [['1.8.8', 'primary'], ['1.13.2', 'models']]);
  assert.ok(legacy[0]?.accepts('assets/minecraft/textures/blocks/stone.png'));
  assert.ok(!legacy[0]?.accepts('assets/minecraft/blockstates/stone.json'));
  assert.ok(legacy[1]?.accepts('assets/minecraft/blockstates/stone.json'));
  const modern = sourceJars(parseVersion('26.1.2'));
  assert.ok(modern[0]?.accepts('data/minecraft/worldgen/biome/plains.json'));
  assert.ok(!modern[0]?.accepts('assets/minecraft/sounds.json'));
  assert.equal(assetPath('assets/minecraft/models/block/stone.json'), 'models/block/stone.json');
});

void test('bundles round-trip through the cache format', () => {
  const files = new Map([['a.json', new TextEncoder().encode('{}')], ['textures/b.png', new Uint8Array([1, 2, 3])]]);
  const unpacked = unpackFiles(packFiles(files));
  assert.deepEqual([...(unpacked?.keys() ?? [])], ['a.json', 'textures/b.png']);
  assert.deepEqual([...(unpacked?.get('textures/b.png') ?? [])], [1, 2, 3]);
  assert.equal(unpackFiles(new ArrayBuffer(4)), null);
});

void test('ZIP64 archives are recognised by their locator, not by a full entry count', () => {
  const plain = new Uint8Array(22);
  const plainView = new DataView(plain.buffer);
  plainView.setUint32(0, 0x06054b50, true);
  plainView.setUint16(10, 0xffff, true);
  assert.equal(readEndRecord(plain, 22).entryCount, 0xffff);
  const zip64 = new Uint8Array(42);
  const zip64View = new DataView(zip64.buffer);
  zip64View.setUint32(0, 0x07064b50, true);
  zip64View.setUint32(20, 0x06054b50, true);
  assert.throws(() => readEndRecord(zip64, 42), Zip64ArchiveError);
});
