import test from 'node:test';
import assert from 'node:assert/strict';

import { attachSessionKey, observeSessionResponse, readSessionKey } from './panel-session.ts';

const issued = (sessionKey: string) => new Response(JSON.stringify({ success: true, message: '', sessionKey }), { status: 200 });

async function signOut(): Promise<void> {
  await observeSessionResponse('POST', '/v1/panel/auth/logout', new Response(null, { status: 204 }), null);
}

await test('claiming the instance stores the issued session key', async () => {
  await signOut();
  await observeSessionResponse('POST', '/v1/public/setup/claim', issued('claim-key'), null);
  assert.equal(readSessionKey(), 'claim-key');
});

await test('the owner sign-in with a setup code stores the issued session key', async () => {
  await signOut();
  await observeSessionResponse('POST', '/v1/public/setup/sign-in', issued('owner-key'), null);
  assert.equal(readSessionKey(), 'owner-key');
});

await test('a rejected owner sign-in keeps the current session key', async () => {
  await observeSessionResponse('POST', '/v1/public/setup/sign-in', issued('owner-key'), null);
  await observeSessionResponse('POST', '/v1/public/setup/sign-in', new Response('{}', { status: 401 }), 'owner-key');
  assert.equal(readSessionKey(), 'owner-key');
});

await test('other public setup requests do not touch the session key', async () => {
  await observeSessionResponse('POST', '/v1/public/setup/sign-in', issued('owner-key'), null);
  await observeSessionResponse('GET', '/v1/public/setup/status', issued('ignored'), null);
  assert.equal(readSessionKey(), 'owner-key');
});

await test('the session key is sent only to panel endpoints', async () => {
  await observeSessionResponse('POST', '/v1/public/setup/sign-in', issued('owner-key'), null);
  const panelHeaders = new Headers();
  const publicHeaders = new Headers();
  assert.equal(attachSessionKey('/v1/panel/setup', panelHeaders), 'owner-key');
  assert.equal(panelHeaders.get('X-Modl-Session-Key'), 'owner-key');
  assert.equal(attachSessionKey('/v1/public/setup/status', publicHeaders), null);
  assert.equal(publicHeaders.has('X-Modl-Session-Key'), false);
});

await test('a second-factor challenge leaves the stored session key alone', async () => {
  await observeSessionResponse('POST', '/v1/public/setup/sign-in', issued('kept-key'), null);
  const challenged = new Response(JSON.stringify({ success: false, secondFactor: { challenge: 'token' } }), { status: 200 });
  await observeSessionResponse('POST', '/v1/panel/auth/password/login', challenged, null);
  assert.equal(readSessionKey(), 'kept-key');
});

await test('completing the second factor stores the issued session key', async () => {
  await signOut();
  await observeSessionResponse('POST', '/v1/panel/auth/sign-in/second-factor', issued('verified-key'), null);
  assert.equal(readSessionKey(), 'verified-key');
});
