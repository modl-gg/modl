export const MAX_BLOCK_ENTITIES = 65_536;
export const MAX_BLOCK_STATES = 131_072;
export const MAX_COMPRESSED_BYTES = 10 * 1024 * 1024;
export const MAX_EVENTS = 2_000_000;
export const MAX_FRAME_BYTES = 4 * 1024 * 1024;
export const MAX_KEYFRAMES = 4_096;
export const MAX_SECTIONS = 65_536;
export const MAX_STRINGS = 131_072;
export const MAX_UNCOMPRESSED_BYTES = 256 * 1024 * 1024;
export const SECTION_STATES = 4_096;

export const REPLAY_LIMITS: ReadonlyMap<string, number> = new Map([
  ['max-block-entities', MAX_BLOCK_ENTITIES],
  ['max-block-states', MAX_BLOCK_STATES],
  ['max-compressed-bytes', MAX_COMPRESSED_BYTES],
  ['max-events', MAX_EVENTS],
  ['max-frame-bytes', MAX_FRAME_BYTES],
  ['max-keyframes', MAX_KEYFRAMES],
  ['max-sections', MAX_SECTIONS],
  ['max-strings', MAX_STRINGS],
  ['max-uncompressed-bytes', MAX_UNCOMPRESSED_BYTES],
  ['section-states', SECTION_STATES],
]);
