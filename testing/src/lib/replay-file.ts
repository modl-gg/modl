import { create, fromBinary } from "@bufbuild/protobuf";
import { gunzipSync } from "node:zlib";
import {
  ReplayFrameSchema,
  ReplayHeaderSchema,
  type ReplayBlockState,
  type ReplayEnd,
  type ReplayEvent,
  type ReplayHeader,
  type ReplayKeyframe,
  type ReplayWorldSection,
} from "@proto/modl/v1/replay_format_pb";

export const REPLAY_FORMAT_VERSION = 5;
const MAGIC = "MODL";
const SECTION_BLOCKS = 4096;

export interface TimedEvent {
  timeMs: number;
  event: ReplayEvent;
}

export interface BlockAt {
  name: string;
  properties: Record<string, string>;
}

export interface ParsedReplay {
  header: ReplayHeader;
  strings: string[];
  palette: ReplayBlockState[];
  sections: Map<string, ReplayWorldSection>;
  keyframes: ReplayKeyframe[];
  events: TimedEvent[];
  end: ReplayEnd | null;
  frameCount: number;
}

export function parseReplay(file: Buffer): ParsedReplay {
  if (file.length < 6 || file.subarray(0, 4).toString("latin1") !== MAGIC) throw new Error("the file does not start with the MODL magic");
  const version = file.readUInt16BE(4);
  if (version !== REPLAY_FORMAT_VERSION) throw new Error(`the file has format version ${String(version)}, expected ${String(REPLAY_FORMAT_VERSION)}`);
  const body = gunzipSync(file.subarray(6));
  const parsed: ParsedReplay = { header: emptyHeader(), strings: [], palette: [], sections: new Map(), keyframes: [], events: [], end: null, frameCount: 0 };
  let offset = 0;
  let headerSeen = false;
  while (offset < body.length) {
    const { value: length, next } = readVarint(body, offset);
    const frame = fromBinary(ReplayFrameSchema, body.subarray(next, next + length));
    offset = next + length;
    parsed.frameCount++;
    const content = frame.frame;
    switch (content.case) {
      case "header":
        parsed.header = content.value;
        headerSeen = true;
        break;
      case "strings":
        parsed.strings.push(...content.value.values);
        break;
      case "blockPalette":
        parsed.palette.push(...content.value.states);
        break;
      case "section": {
        const section = content.value;
        parsed.sections.set(sectionKey(section.world, section.x, section.y, section.z), section);
        break;
      }
      case "keyframe":
        parsed.keyframes.push(content.value);
        break;
      case "events": {
        let time = content.value.startMs;
        for (const event of content.value.events) {
          time += event.deltaMs;
          parsed.events.push({ timeMs: time, event });
        }
        break;
      }
      case "end":
        parsed.end = content.value;
        break;
      case "blockEntity":
      case undefined:
        break;
    }
  }
  if (!headerSeen) throw new Error("the replay has no header frame");
  return parsed;
}

export function stringAt(replay: ParsedReplay, index: number): string {
  return replay.strings[index] ?? `#${String(index)}`;
}

export function blockAt(replay: ParsedReplay, world: number, x: number, y: number, z: number, untilMs = 0): BlockAt {
  let state = sectionState(replay, world, x, y, z);
  let currentWorld = replay.header.dimension?.world ?? 0;
  for (const { timeMs, event } of replay.events) {
    if (timeMs > untilMs) break;
    const content = event.event;
    if (content.case === "dimensionChange") currentWorld = content.value.dimension?.world ?? currentWorld;
    if (content.case === "blockChange" && currentWorld === world && content.value.x === x && content.value.y === y && content.value.z === z) {
      state = content.value.state;
    }
  }
  return describe(replay.palette[state]);
}

export function blockAtEnd(replay: ParsedReplay, world: number, x: number, y: number, z: number): BlockAt {
  return blockAt(replay, world, x, y, z, Number.MAX_SAFE_INTEGER);
}

function sectionState(replay: ParsedReplay, world: number, x: number, y: number, z: number): number {
  const section = replay.sections.get(sectionKey(world, x >> 4, y >> 4, z >> 4));
  if (section?.states.length !== SECTION_BLOCKS) return 0;
  return section.states[((y & 15) << 8) | ((z & 15) << 4) | (x & 15)] ?? 0;
}

function describe(state: ReplayBlockState | undefined): BlockAt {
  if (state === undefined) return { name: "minecraft:air", properties: {} };
  const properties: Record<string, string> = {};
  for (const property of state.properties) properties[property.name] = property.value;
  return { name: state.name, properties };
}

function sectionKey(world: number, x: number, y: number, z: number): string {
  return `${String(world)}:${String(x)}:${String(y)}:${String(z)}`;
}

function readVarint(bytes: Buffer, start: number): { value: number; next: number } {
  let value = 0;
  let shift = 0;
  let offset = start;
  for (;;) {
    const byte = bytes[offset];
    if (byte === undefined) throw new Error("the replay ends inside a frame length");
    offset++;
    value |= (byte & 0x7f) << shift;
    if ((byte & 0x80) === 0) return { value: value >>> 0, next: offset };
    shift += 7;
    if (shift > 28) throw new Error("a frame length is too long");
  }
}

function emptyHeader(): ReplayHeader {
  return create(ReplayHeaderSchema);
}

export function printable(value: unknown): unknown {
  const text = JSON.stringify(value ?? null, (_key, entry: unknown) => (typeof entry === "bigint" ? entry.toString() : entry));
  return JSON.parse(text) as unknown;
}
