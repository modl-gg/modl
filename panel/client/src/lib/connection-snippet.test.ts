import test from 'node:test';
import assert from 'node:assert/strict';

import { isPlainHttpToPublicHost, planConnectionUrl, renderConnectionSnippet } from './connection-snippet.ts';

await test('the panel url the backend returns wins over the browsing origin', () => {
  assert.deepEqual(planConnectionUrl('https://panel.example.com/', 'http://localhost:8080'), {
    primary: 'https://panel.example.com',
    sameMachine: null,
    loopbackOnly: false,
  });
});

await test('without a panel url the browsing origin is used', () => {
  assert.deepEqual(planConnectionUrl('', 'http://203.0.113.7:8080'), {
    primary: 'http://203.0.113.7:8080',
    sameMachine: 'http://127.0.0.1:8080',
    loopbackOnly: false,
  });
});

await test('plain http without a port uses port 80 on the same machine', () => {
  assert.equal(planConnectionUrl('http://panel.example.com', '').sameMachine, 'http://127.0.0.1:80');
});

await test('loopback urls only reach the same machine', () => {
  for (const url of ['http://localhost:8080', 'http://127.0.0.1:8080', 'http://[::1]:8080']) {
    assert.deepEqual(planConnectionUrl('', url), { primary: url, sameMachine: null, loopbackOnly: true });
  }
});

await test('plain http is flagged only towards public hosts', () => {
  for (const url of ['http://203.0.113.7:8080', 'http://panel.example.com', 'http://10.example.com', 'http://[2001:db8::1]:8080']) {
    assert.equal(isPlainHttpToPublicHost(url), true, url);
  }
  const unflagged = [
    'https://panel.example.com', 'http://localhost:8080', 'http://10.0.0.5', 'http://172.16.0.2', 'http://192.168.1.4',
    'http://100.64.0.9', 'http://modl', 'http://proxy.local', 'http://[fd00::2]', 'not a url',
  ];
  for (const url of unflagged) {
    assert.equal(isPlainHttpToPublicHost(url), false, url);
  }
});

await test('the behind-proxy snippet carries the panel url and the key', () => {
  assert.equal(
    renderConnectionSnippet({ mode: 'behind-proxy', url: 'https://panel.example.com', apiKey: 'msk_abc' }),
    'mode: behind-proxy\n\nremote:\n  url: "https://panel.example.com"\n  api-key: "msk_abc"',
  );
});

await test('the remote snippet carries the panel url and the key', () => {
  assert.equal(
    renderConnectionSnippet({ mode: 'remote', url: 'https://panel.example.com', apiKey: 'msk_abc' }),
    'mode: remote\n\nremote:\n  url: "https://panel.example.com"\n  api-key: "msk_abc"',
  );
});

await test('snippet values are quoted so they stay valid yaml', () => {
  const snippet = renderConnectionSnippet({ mode: 'remote', url: 'https://panel.example.com', apiKey: 'a"b\\c' });
  assert.match(snippet, /api-key: "a\\"b\\\\c"/);
});
