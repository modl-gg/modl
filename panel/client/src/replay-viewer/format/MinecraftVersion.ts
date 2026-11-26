export interface MinecraftVersion {
  readonly id: string;
  readonly parts: readonly [number, number, number];
}

const VERSION_ID = /^\d+\.\d+(\.\d+)?/;

export function parseVersion(id: string): MinecraftVersion {
  const numbers = id.split('.').map((part) => Number.parseInt(part, 10));
  const major = Number.isFinite(numbers[0]) ? numbers[0] ?? 0 : 0;
  const minor = Number.isFinite(numbers[1]) ? numbers[1] ?? 0 : 0;
  const patch = Number.isFinite(numbers[2]) ? numbers[2] ?? 0 : 0;
  return { id, parts: [major, minor, patch] };
}

export function minecraftVersion(raw: string): MinecraftVersion | null {
  const match = VERSION_ID.exec(raw.trim());
  return match === null ? null : parseVersion(match[0]);
}

export function compareVersions(left: MinecraftVersion, right: MinecraftVersion): number {
  for (let index = 0; index < 3; index++) {
    const difference = (left.parts[index] ?? 0) - (right.parts[index] ?? 0);
    if (difference !== 0) return difference;
  }
  return 0;
}

export function atLeast(version: MinecraftVersion, id: string): boolean {
  return compareVersions(version, parseVersion(id)) >= 0;
}
