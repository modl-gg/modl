import {
  type ReplayHeader,
  type BlockSnapshot,
  type ReplayEvent,
  type ParsedReplay,
  EventType,
  isAnimationTypeId,
} from '../types/ReplayFile.ts';
import { optionalField } from '../../lib/defined-fields.ts';

const MAGIC = [0x4d, 0x4f, 0x44, 0x4c]; // "MODL"
const GZIP_MAGIC = [0x1f, 0x8b];
const textDecoder = new TextDecoder('utf-8');

function isGzip(buffer: ArrayBuffer): boolean {
  if (buffer.byteLength < 2) return false;
  const bytes = new Uint8Array(buffer, 0, 2);
  return bytes[0] === GZIP_MAGIC[0] && bytes[1] === GZIP_MAGIC[1];
}

async function decompressGzip(buffer: ArrayBuffer): Promise<ArrayBuffer> {
  if (typeof DecompressionStream === 'undefined') {
    throw new Error('DecompressionStream API not available, cannot decompress gzip replay file');
  }
  return new Response(
    new Blob([buffer]).stream().pipeThrough(new DecompressionStream('gzip'))
  ).arrayBuffer();
}

/**
 * Parse a .modlreplay binary file from an ArrayBuffer.
 * Automatically decompresses gzip if detected.
 */
export async function parseReplayAsync(buffer: ArrayBuffer): Promise<ParsedReplay> {
  if (isGzip(buffer)) {
    buffer = await decompressGzip(buffer);
  }
  return parseReplaySync(buffer);
}

/**
 * Parse a .modlreplay binary file from an ArrayBuffer.
 * Uses DataView for big-endian reads (matching Java DataOutputStream).
 * @deprecated Use parseReplayAsync for gzip support.
 */
export function parseReplay(buffer: ArrayBuffer): ParsedReplay {
  return parseReplaySync(buffer);
}

function parseReplaySync(buffer: ArrayBuffer): ParsedReplay {
  const view = new DataView(buffer);
  let offset = 0;

  // --- Helper functions ---
  function readUint8(): number { const v = view.getUint8(offset); offset += 1; return v; }
  function readInt16(): number { const v = view.getInt16(offset); offset += 2; return v; }
  function readUint16(): number { const v = view.getUint16(offset); offset += 2; return v; }
  function readInt32(): number { const v = view.getInt32(offset); offset += 4; return v; }
  function readFloat32(): number { const v = view.getFloat32(offset); offset += 4; return v; }
  function readInt64(): bigint { const v = view.getBigInt64(offset); offset += 8; return v; }

  function readString8(): string {
    const len = readUint8();
    const bytes = new Uint8Array(buffer, offset, len);
    offset += len;
    return textDecoder.decode(bytes);
  }

  function readString16(): string {
    const len = readUint16();
    const bytes = new Uint8Array(buffer, offset, len);
    offset += len;
    return textDecoder.decode(bytes);
  }

  function readUUID(): string {
    const most = readInt64();
    const least = readInt64();
    // Convert signed BigInts to unsigned hex (BigInt can be negative when high bit is set)
    const toUnsignedHex = (v: bigint): string => {
      if (v < 0n) v = v + (1n << 64n);
      return v.toString(16).padStart(16, '0');
    };
    const hex = toUnsignedHex(most) + toUnsignedHex(least);
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20, 32)}`;
  }

  function readBytes(len: number): Uint8Array<ArrayBuffer> {
    const bytes = new Uint8Array(buffer, offset, len);
    offset += len;
    return bytes.slice(); // copy to avoid referencing the underlying buffer
  }

  /** Decode a raw int16 yaw/pitch value to degrees (for v1-v3). */
  function decodeAngle(raw: number): number {
    return (raw * 360.0) / 32767.0;
  }

  /** Read yaw or pitch: float32 in v4+ (already degrees), int16 in v1-v3 (decode to degrees). */
  function readAngle(): number {
    if (hasTLV) {
      return readFloat32();
    }
    return decodeAngle(readInt16());
  }

  // --- Header ---
  const magic = [readUint8(), readUint8(), readUint8(), readUint8()];
  if (magic[0] !== MAGIC[0] || magic[1] !== MAGIC[1] || magic[2] !== MAGIC[2] || magic[3] !== MAGIC[3]) {
    throw new Error('Invalid magic bytes: not a .modlreplay file');
  }

  const version = readUint16();
  const hasEnchantments = version >= 3;
  const hasTLV = version >= 4;

  // startTime is int64 - use Number() since JS timestamps fit in Number.MAX_SAFE_INTEGER
  const startTime = Number(readInt64());
  const mcVersion = readString16();
  const targetX = readInt32();
  const targetY = readInt32();
  const targetZ = readInt32();
  const radiusBlocks = readInt32();

  const header: ReplayHeader = { version, startTime, mcVersion, targetX, targetY, targetZ, radiusBlocks };

  // --- Snapshot ---
  const blockCount = readInt32();
  const snapshot: BlockSnapshot[] = Array.from({ length: blockCount }, () => ({
    x: readInt32(),
    y: readInt16(),
    z: readInt32(),
    stateId: readInt32(),
  }));

  // --- Events ---
  const events: ReplayEvent[] = [];
  while (offset < buffer.byteLength) {
    const eventTypeByte = readUint8();
    const timestampDeltaMs = readInt32();
    const payloadLength = hasTLV ? readUint16() : 0;
    const payloadStart = offset;

    switch (eventTypeByte) {
      case EventType.BLOCK_CHANGE:
        events.push({
          type: EventType.BLOCK_CHANGE,
          timestampDeltaMs,
          x: readInt32(),
          y: readInt16(),
          z: readInt32(),
          stateId: readInt32(),
        });
        break;

      case EventType.ENTITY_SPAWN: {
        const entityId = readInt32();
        const entityTypeId = readInt16();
        const x = readFloat32();
        const y = readFloat32();
        const z = readFloat32();
        const metaLen = readUint16();
        const metadata = readBytes(metaLen);
        events.push({ type: EventType.ENTITY_SPAWN, timestampDeltaMs, entityId, entityTypeId, x, y, z, metadata });
        break;
      }

      case EventType.ENTITY_MOVE:
        events.push({
          type: EventType.ENTITY_MOVE,
          timestampDeltaMs,
          entityId: readInt32(),
          x: readFloat32(),
          y: readFloat32(),
          z: readFloat32(),
          yaw: readAngle(),
          pitch: readAngle(),
        });
        break;

      case EventType.ENTITY_REMOVE:
        events.push({ type: EventType.ENTITY_REMOVE, timestampDeltaMs, entityId: readInt32() });
        break;

      case EventType.PLAYER_SPAWN: {
        const uuid = readUUID();
        const playerName = readString8();
        const x = readFloat32();
        const y = readFloat32();
        const z = readFloat32();
        const yaw = readAngle();
        const pitch = readAngle();
        const equipLen = readUint16();
        const equipment = readBytes(equipLen);
        events.push({ type: EventType.PLAYER_SPAWN, timestampDeltaMs, uuid, playerName, x, y, z, yaw, pitch, equipment });
        break;
      }

      case EventType.PLAYER_MOVE:
        events.push({
          type: EventType.PLAYER_MOVE,
          timestampDeltaMs,
          uuid: readUUID(),
          x: readFloat32(),
          y: readFloat32(),
          z: readFloat32(),
          yaw: readAngle(),
          pitch: readAngle(),
        });
        break;

      case EventType.PLAYER_REMOVE:
        events.push({ type: EventType.PLAYER_REMOVE, timestampDeltaMs, uuid: readUUID() });
        break;

      case EventType.PLAYER_ANIM: {
        const uuid = readUUID();
        const animationType = readUint8();
        if (isAnimationTypeId(animationType)) {
          events.push({ type: EventType.PLAYER_ANIM, timestampDeltaMs, uuid, animationType });
        }
        break;
      }

      case EventType.CHAT:
        events.push({
          type: EventType.CHAT,
          timestampDeltaMs,
          uuid: readUUID(),
          message: readString16(),
        });
        break;

      case EventType.PLAYER_EQUIPMENT:
        events.push({
          type: EventType.PLAYER_EQUIPMENT,
          timestampDeltaMs,
          uuid: readUUID(),
          itemName: readString8(),
        });
        break;

      case EventType.PLAYER_EQUIPMENT_FULL: {
        const uuid = readUUID();
        const slotCount = readUint8();
        const slots: { slotId: number; itemName: string; enchantments?: { id: string; level: number }[] }[] = [];
        for (let i = 0; i < slotCount; i++) {
          const slotId = readUint8();
          const itemName = readString8();
          let enchantments: { id: string; level: number }[] | undefined;
          if (hasEnchantments) {
            const enchCount = readUint8();
            if (enchCount > 0) {
              enchantments = [];
              for (let e = 0; e < enchCount; e++) {
                enchantments.push({ id: readString8(), level: readUint8() });
              }
            }
          }
          slots.push({ slotId, itemName, ...optionalField('enchantments', enchantments) });
        }
        events.push({ type: EventType.PLAYER_EQUIPMENT_FULL, timestampDeltaMs, uuid, slots });
        break;
      }

      case EventType.PLAYER_HEALTH: {
        const uuid = readUUID();
        const health = readFloat32();
        const food = readUint8();
        const saturation = readFloat32();
        events.push({ type: EventType.PLAYER_HEALTH, timestampDeltaMs, uuid, health, food, saturation });
        break;
      }

      case EventType.PLAYER_EFFECTS: {
        const uuid = readUUID();
        const effectCount = readUint8();
        const effects: { effectTypeId: number; amplifier: number; durationTicks: number }[] = [];
        for (let i = 0; i < effectCount; i++) {
          effects.push({ effectTypeId: readUint8(), amplifier: readUint8(), durationTicks: readInt32() });
        }
        events.push({ type: EventType.PLAYER_EFFECTS, timestampDeltaMs, uuid, effects });
        break;
      }

      case EventType.PLAYER_INVENTORY: {
        const uuid = readUUID();
        const isFullSnapshot = readUint8() !== 0;
        const slotCount = readUint8();
        const slots: { slotIndex: number; itemName: string; count: number; enchantments?: { id: string; level: number }[] }[] = [];
        for (let i = 0; i < slotCount; i++) {
          const slotIndex = readUint8();
          const itemName = readString16();
          const count = readUint8();
          let enchantments: { id: string; level: number }[] | undefined;
          if (hasEnchantments) {
            const enchCount = readUint8();
            if (enchCount > 0) {
              enchantments = [];
              for (let e = 0; e < enchCount; e++) {
                enchantments.push({ id: readString8(), level: readUint8() });
              }
            }
          }
          slots.push({ slotIndex, itemName, count, ...optionalField('enchantments', enchantments) });
        }
        events.push({ type: EventType.PLAYER_INVENTORY, timestampDeltaMs, uuid, isFullSnapshot, slots });
        break;
      }

      case EventType.PLAYER_SKIN: {
        const uuid = readUUID();
        const skinLen = readInt32();
        const skinPng = readBytes(skinLen);
        events.push({ type: EventType.PLAYER_SKIN, timestampDeltaMs, uuid, skinPng });
        break;
      }

      case EventType.PLAYER_BLOCK_PLACE: {
        const placerUuid = readUUID();
        const x = readInt32();
        const y = readInt16();
        const z = readInt32();
        const stateId = readInt32();
        events.push({ type: EventType.PLAYER_BLOCK_PLACE, timestampDeltaMs, placerUuid, x, y, z, stateId });
        break;
      }

      case EventType.PLAYER_BLOCK_BREAK: {
        const breakerUuid = readUUID();
        const x = readInt32();
        const y = readInt16();
        const z = readInt32();
        const previousStateId = readInt32();
        events.push({ type: EventType.PLAYER_BLOCK_BREAK, timestampDeltaMs, breakerUuid, x, y, z, previousStateId });
        break;
      }

      default:
        offset = hasTLV ? payloadStart + payloadLength : buffer.byteLength;
        break;
    }
  }

  return { header, snapshot, events };
}
