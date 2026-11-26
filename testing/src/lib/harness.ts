import harnessPackage from "../../package.json" with { type: "json" };

export const HARNESS_VERSION = harnessPackage.version;
export const REQUIRED_NODE = harnessPackage.engines.node;

const MINIMUM_VERSION = /^>=(\d+)(?:\.(\d+))?(?:\.(\d+))?$/;

function versionNumbers(parts: readonly (string | undefined)[]): number[] {
  return [0, 1, 2].map((index) => Number(parts[index] ?? 0));
}

function compareVersions(left: readonly number[], right: readonly number[]): number {
  for (const [index, part] of left.entries()) {
    const other = right[index] ?? 0;
    if (part !== other) return part - other;
  }
  return 0;
}

export function meetsNodeRequirement(nodeVersion: string): boolean {
  const minimum = MINIMUM_VERSION.exec(REQUIRED_NODE);
  if (!minimum) throw new Error(`engines.node in package.json must look like >=24 or >=24.1.0, got '${REQUIRED_NODE}'`);
  return compareVersions(versionNumbers(nodeVersion.split(".")), versionNumbers(minimum.slice(1))) >= 0;
}
