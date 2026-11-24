import test from 'node:test';
import assert from 'node:assert/strict';

import { PUNISHMENT_CATEGORY_LABEL_KEYS, punishmentCategoryLabelKey } from './punishment-category-labels.ts';

await test('stored category names of any case map to the one shared label', () => {
  assert.equal(punishmentCategoryLabelKey('Gameplay'), PUNISHMENT_CATEGORY_LABEL_KEYS.gameplay);
  assert.equal(punishmentCategoryLabelKey(' social '), PUNISHMENT_CATEGORY_LABEL_KEYS.social);
  assert.equal(punishmentCategoryLabelKey('ADMINISTRATIVE'), PUNISHMENT_CATEGORY_LABEL_KEYS.administrative);
});

await test('an unknown category has no shared label', () => {
  assert.equal(punishmentCategoryLabelKey('Custom'), undefined);
  assert.equal(punishmentCategoryLabelKey('constructor'), undefined);
});
