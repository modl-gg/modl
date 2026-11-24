import test from 'node:test';
import assert from 'node:assert/strict';

import { DOCKER_PULL_AND_RESTART, canUpdateNow, updateInstructions, type InstructionRelease } from './update-instructions.ts';

const patch: InstructionRelease = {
  version: '3.0.1',
  prerelease: false,
  imageReference: `3.0.1@sha256:${'a'.repeat(64)}`,
  availability: 'available',
  minUpgradeFrom: '3.0.0',
  minJava: 21,
  releaseUrl: 'https://github.com/modl-gg/modl/releases/tag/v3.0.1',
};

await test('docker always pins the signed image digest before pulling', () => {
  const steps = updateInstructions('docker', { ...patch, availability: 'docker' });
  assert.deepEqual(steps.map((step) => step.key), ['dockerTag', 'docker', 'dockerSource']);
  assert.equal(steps[0]?.command, `MODL_IMAGE_TAG=3.0.1@sha256:${'a'.repeat(64)}`);
  assert.equal(steps[1]?.command, DOCKER_PULL_AND_RESTART);
});

await test('a blocked jar update explains the blocker instead of offering the button', () => {
  assert.equal(updateInstructions('jar', { ...patch, availability: 'upgradePath', minUpgradeFrom: '3.0.5' })[0]?.values['version'], '3.0.5');
  assert.equal(updateInstructions('jar', { ...patch, availability: 'java', minJava: 25 })[0]?.values['java'], '25');
  assert.equal(updateInstructions('jar', { ...patch, availability: 'launcher' })[0]?.key, 'launcher');
  assert.equal(canUpdateNow({ ...patch, availability: 'java' }), false);
});

await test('the plugin needs its jar replaced for another line', () => {
  const steps = updateInstructions('embedded', { ...patch, version: '3.1.0', availability: 'pluginJarRequired' });
  assert.deepEqual(steps.map((step) => step.key), ['plugin']);
});

await test('an installable embedded update needs no plugin jar, only a jar install keeps the manual alternative', () => {
  assert.equal(canUpdateNow(patch), true);
  assert.equal(updateInstructions('jar', patch)[0]?.key, 'jar');
  assert.deepEqual(updateInstructions('embedded', patch), []);
  assert.equal(updateInstructions('unmanaged', { ...patch, availability: 'unmanaged' })[0]?.key, 'unmanaged');
});
