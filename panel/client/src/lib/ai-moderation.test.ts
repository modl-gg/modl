import test from 'node:test';
import assert from 'node:assert/strict';

import { ApiHttpError } from './http-error.ts';
import { AI_PUNISHMENT_TYPE_INELIGIBLE_CODE, canToggleAiReview, isAiPunishmentTypeIneligible, selectableAiPunishmentTypes } from './ai-moderation.ts';

await test('the AI picker offers only types the backend accepts suggestions for', () => {
  const types = [
    { name: 'Manual Ban', ordinal: 2, acceptsAiSuggestions: false },
    { name: 'Chat Abuse', ordinal: 8, acceptsAiSuggestions: true },
    { name: 'Lengthless', ordinal: 9 },
    { name: 'Spam', ordinal: 10, acceptsAiSuggestions: true },
  ];

  const offered = selectableAiPunishmentTypes(types, ['10']).map((type) => type.name);

  assert.deepEqual(offered, ['Chat Abuse']);
});

await test('an ineligible AI type refusal is recognised by its machine code', () => {
  const refused = new ApiHttpError(400, 'Bad Request', JSON.stringify({ code: AI_PUNISHMENT_TYPE_INELIGIBLE_CODE, message: 'no' }));
  const invalid = new ApiHttpError(400, 'Bad Request', JSON.stringify({ code: 'INVALID_ARGUMENT', message: 'no' }));

  assert.equal(isAiPunishmentTypeIneligible(refused), true);
  assert.equal(isAiPunishmentTypeIneligible(invalid), false);
});

await test('AI review can be turned on only with a ready provider and turned off at any time', () => {
  assert.equal(canToggleAiReview(false, false), false);
  assert.equal(canToggleAiReview(false, true), true);
  assert.equal(canToggleAiReview(true, false), true);
});
