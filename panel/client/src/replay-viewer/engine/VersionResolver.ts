/**
 * Version resolution for atlas loading.
 * Maps a replay's MC version to the best available atlas version.
 */

export interface VersionManifest {
  versions: string[];
  default: string;
}

/**
 * Parse a version string into [major, minor, patch] numbers.
 */
function parseVersion(v: string): [number, number, number] {
  const parts = v.split('.').map(Number);
  return [versionPart(parts[0]), versionPart(parts[1]), versionPart(parts[2])];
}

function versionPart(part: number | undefined): number {
  return part === undefined || Number.isNaN(part) ? 0 : part;
}

function closestVersion(candidates: readonly string[], distance: (version: string) => number): string | undefined {
  let closest: string | undefined;
  let closestDistance = Number.POSITIVE_INFINITY;
  for (const candidate of candidates) {
    const candidateDistance = distance(candidate);
    if (candidateDistance < closestDistance) {
      closest = candidate;
      closestDistance = candidateDistance;
    }
  }
  return closest;
}

/**
 * Resolve the best atlas version for a given MC version.
 *
 * Strategy:
 * 1. Exact match
 * 2. Closest same major.minor by absolute patch distance
 * 3. Closest same major by absolute minor distance
 * 4. Fallback to manifest default
 */
export function resolveAtlasVersion(mcVersion: string, manifest: VersionManifest): string {
  // Exact match
  if (manifest.versions.includes(mcVersion)) {
    return mcVersion;
  }

  const [major, minor, patch] = parseVersion(mcVersion);

  const sameMinor = manifest.versions.filter(v => {
    const [ma, mi] = parseVersion(v);
    return ma === major && mi === minor;
  });
  const sameMajor = manifest.versions.filter(v => parseVersion(v)[0] === major);

  return closestVersion(sameMinor, v => Math.abs(parseVersion(v)[2] - patch))
    ?? closestVersion(sameMajor, v => Math.abs(parseVersion(v)[1] - minor))
    ?? manifest.default;
}
