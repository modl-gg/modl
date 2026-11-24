export type InstructionDeployment = 'jar' | 'embedded' | 'docker' | 'unmanaged';
export type InstructionAvailability =
  | 'available'
  | 'docker'
  | 'unmanaged'
  | 'pluginJarRequired'
  | 'upgradePath'
  | 'java'
  | 'launcher'
  | 'dataDirectory';

export interface InstructionRelease {
  version: string;
  prerelease: boolean;
  imageReference: string;
  availability: InstructionAvailability;
  minUpgradeFrom: string;
  minJava: number;
  releaseUrl: string;
}

export type InstructionKey =
  | 'docker'
  | 'dockerTag'
  | 'dockerSource'
  | 'jar'
  | 'plugin'
  | 'unmanaged'
  | 'upgradePath'
  | 'java'
  | 'launcher'
  | 'dataDirectory';

export interface UpdateInstruction {
  key: InstructionKey;
  values: Record<string, string>;
  command: string | null;
}

export const DOCKER_PULL_AND_RESTART = 'docker compose pull && docker compose up -d';
export const DOCKER_SOURCE_BUILD = 'git pull && docker compose -f docker-compose.yml -f docker-compose.build.yml up -d --build';

function instruction(key: InstructionKey, command: string | null, values: Record<string, string> = {}): UpdateInstruction {
  return { key, values, command };
}

function dockerInstructions(release: InstructionRelease): UpdateInstruction[] {
  return [
    instruction('dockerTag', `MODL_IMAGE_TAG=${release.imageReference}`, { tag: release.imageReference }),
    instruction('docker', DOCKER_PULL_AND_RESTART),
    instruction('dockerSource', DOCKER_SOURCE_BUILD),
  ];
}

function blockedInstructions(release: InstructionRelease): UpdateInstruction[] {
  switch (release.availability) {
    case 'upgradePath':
      return [instruction('upgradePath', null, { version: release.minUpgradeFrom })];
    case 'java':
      return [instruction('java', null, { java: String(release.minJava) })];
    case 'launcher':
      return [instruction('launcher', null, { url: release.releaseUrl })];
    case 'dataDirectory':
      return [instruction('dataDirectory', null, { url: release.releaseUrl })];
    case 'pluginJarRequired':
      return [instruction('plugin', null, { version: release.version, url: release.releaseUrl })];
    case 'available':
    case 'docker':
    case 'unmanaged':
      return [];
  }
}

export function updateInstructions(deployment: InstructionDeployment, release: InstructionRelease): UpdateInstruction[] {
  if (deployment === 'docker' || release.availability === 'docker') {
    return dockerInstructions(release);
  }
  if (deployment === 'unmanaged' || release.availability === 'unmanaged') {
    return [instruction('unmanaged', null, { version: release.version, url: release.releaseUrl })];
  }
  const blocked = blockedInstructions(release);
  if (blocked.length > 0) {
    return blocked;
  }
  return deployment === 'embedded' ? [] : [instruction('jar', null, { version: release.version, url: release.releaseUrl })];
}

export function canUpdateNow(release: InstructionRelease): boolean {
  return release.availability === 'available';
}
