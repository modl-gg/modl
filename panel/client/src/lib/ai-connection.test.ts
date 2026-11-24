import test from 'node:test';
import assert from 'node:assert/strict';

import type { AiConnectionInfo, AiModelOption, AiProvider } from '@/hooks/data/ai';
import {
  canListModels,
  canSave,
  draftOf,
  draftRequest,
  isTypedModelNew,
  limitsFrom,
  normalizedBaseUrl,
  parsedLimit,
  saveRequest,
  visibleModels,
} from './ai-connection.ts';

const OPENAI: AiProvider = {
  id: 'openai',
  displayName: 'OpenAI',
  group: 'cloud',
  endpoints: [{ region: 'global', baseUrl: 'https://api.openai.com/v1' }],
  baseUrlEditable: false,
  apiKeyRequired: true,
  keyPageUrl: 'https://platform.openai.com/api-keys',
};

const OLLAMA: AiProvider = {
  id: 'ollama',
  displayName: 'Ollama',
  group: 'local',
  endpoints: [{ region: 'global', baseUrl: 'http://localhost:11434/v1' }],
  baseUrlEditable: true,
  apiKeyRequired: false,
  keyPageUrl: null,
};

const SAVED_CUSTOM: AiConnectionInfo = {
  provider: 'ollama',
  baseUrl: 'http://10.0.0.5:11434/v1',
  hasApiKey: true,
  model: 'llama',
  outputMode: 'schema',
  verifiedAt: null,
};

function model(id: string, chatCapable = true, displayName: string | null = null): AiModelOption {
  return { id, displayName, chatCapable, deprecated: false, retiresAt: null, structuredOutputs: null };
}

await test('an untouched provider starts at its first endpoint with no model chosen', () => {
  const draft = draftOf(OPENAI, null, null);

  assert.equal(draft.baseUrl, 'https://api.openai.com/v1');
  assert.equal(draft.model, '');
  assert.equal(draft.dirty, false);
  assert.equal(canSave(draft), false);
});

await test('a saved key applies only while the base URL stays the same', () => {
  assert.equal(draftOf(OLLAMA, SAVED_CUSTOM, null).storedKeyApplies, true);
  assert.equal(draftOf(OLLAMA, SAVED_CUSTOM, { baseUrl: 'http://10.0.0.5:11434/v1/' }).storedKeyApplies, true);

  const moved = draftOf(OLLAMA, SAVED_CUSTOM, { baseUrl: 'http://10.0.0.6:11434/v1' });

  assert.equal(moved.storedKeyApplies, false);
  assert.equal(moved.storedKeyDropped, true);
});

await test('a required key must be typed when none is saved', () => {
  assert.equal(canSave(draftOf(OPENAI, null, { model: 'm' })), false);
  assert.equal(canSave(draftOf(OPENAI, null, { model: 'm', apiKey: 'sk' })), true);
  assert.equal(canListModels(draftOf(OPENAI, null, null)), false);
  assert.equal(canSave(draftOf(OLLAMA, null, { model: 'llama' })), true);
});

await test('the save request carries only what changed', () => {
  const edits = { model: 'llama-2' };
  const request = saveRequest(draftOf(OLLAMA, SAVED_CUSTOM, edits), edits, true);

  assert.deepEqual(request, { provider: 'ollama', activate: true, model: 'llama-2' });
});

await test('clearing the key sends an empty key', () => {
  const edits = { clearKey: true };

  assert.equal(saveRequest(draftOf(OLLAMA, SAVED_CUSTOM, edits), edits, false).apiKey, '');
});

await test('drafts for tests and model lists never invent a key', () => {
  assert.deepEqual(draftRequest(draftOf(OLLAMA, SAVED_CUSTOM, null)), {
    provider: 'ollama',
    baseUrl: 'http://10.0.0.5:11434/v1',
    model: 'llama',
  });
  assert.equal(draftRequest(draftOf(OPENAI, null, { apiKey: ' sk ' })).apiKey, 'sk');
});

await test('model lists hide non-chat models unless asked and match on id or name', () => {
  const models = [model('gpt-a', true, 'Alpha'), model('embed-1', false), model('gpt-b')];

  assert.deepEqual(visibleModels(models, '', false).map((entry) => entry.id), ['gpt-a', 'gpt-b']);
  assert.deepEqual(visibleModels(models, '', true).map((entry) => entry.id), ['gpt-a', 'embed-1', 'gpt-b']);
  assert.deepEqual(visibleModels(models, 'alpha', false).map((entry) => entry.id), ['gpt-a']);
  assert.equal(isTypedModelNew(models, 'gpt-c'), true);
  assert.equal(isTypedModelNew(models, 'gpt-a'), false);
  assert.equal(isTypedModelNew(models, ' '), false);
});

await test('base URLs compare without case, default ports or trailing slashes', () => {
  assert.equal(normalizedBaseUrl('HTTPS://API.OpenAI.com:443/v1/'), 'https://api.openai.com/v1');
  assert.equal(normalizedBaseUrl('http://localhost:11434/v1'), 'http://localhost:11434/v1');
});

await test('limits are whole numbers within their bounds', () => {
  assert.equal(parsedLimit('maxOutputTokens', '255'), null);
  assert.equal(parsedLimit('maxOutputTokens', '4096'), 4096);
  assert.equal(parsedLimit('concurrentCalls', '1.5'), null);
  assert.equal(limitsFrom({
    callsPerHour: '60',
    callsPerDay: '500',
    reportsPerReporterPerHour: '5',
    concurrentCalls: '17',
    maxOutputTokens: '4096',
  }), null);
  assert.deepEqual(limitsFrom({
    callsPerHour: '60',
    callsPerDay: '500',
    reportsPerReporterPerHour: '5',
    concurrentCalls: '2',
    maxOutputTokens: '4096',
  }), { callsPerHour: 60, callsPerDay: 500, reportsPerReporterPerHour: 5, concurrentCalls: 2, maxOutputTokens: 4096 });
});
