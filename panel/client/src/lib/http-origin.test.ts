import test from 'node:test';
import assert from 'node:assert/strict';

import { normalizeHttpOrigin, panelUrlWarning } from './http-origin.ts';

await test('accepts http and https origins and drops a trailing slash', () => {
  assert.equal(normalizeHttpOrigin('https://panel.example.com'), 'https://panel.example.com');
  assert.equal(normalizeHttpOrigin('https://panel.example.com/'), 'https://panel.example.com');
  assert.equal(normalizeHttpOrigin(' http://203.0.113.7:8080 '), 'http://203.0.113.7:8080');
});

await test('normalizes host case and default ports', () => {
  assert.equal(normalizeHttpOrigin('HTTPS://Panel.Example.com:443'), 'https://panel.example.com');
});

await test('rejects values that are not a bare http origin', () => {
  assert.equal(normalizeHttpOrigin(''), null);
  assert.equal(normalizeHttpOrigin('panel.example.com'), null);
  assert.equal(normalizeHttpOrigin('ftp://panel.example.com'), null);
  assert.equal(normalizeHttpOrigin('https://panel.example.com/panel'), null);
  assert.equal(normalizeHttpOrigin('https://panel.example.com/?a=1'), null);
  assert.equal(normalizeHttpOrigin('https://panel.example.com/#x'), null);
  assert.equal(normalizeHttpOrigin('https://user:pass@panel.example.com'), null);
  assert.equal(normalizeHttpOrigin('javascript:alert(1)'), null);
});

await test('a panel url that only this machine can open is reported as local only', () => {
  assert.equal(panelUrlWarning('http://localhost:8080'), 'local-only');
  assert.equal(panelUrlWarning('http://panel.localhost'), 'local-only');
  assert.equal(panelUrlWarning('http://127.0.0.1:8080'), 'local-only');
  assert.equal(panelUrlWarning('http://127.10.0.3'), 'local-only');
  assert.equal(panelUrlWarning('http://[::1]:8080'), 'local-only');
  assert.equal(panelUrlWarning('http://0.0.0.0:8080'), 'local-only');
});

await test('a public panel url without https is reported as insecure', () => {
  assert.equal(panelUrlWarning('http://203.0.113.7:8080'), 'insecure');
  assert.equal(panelUrlWarning('http://panel.example.com'), 'insecure');
});

await test('a public https panel url has no warning', () => {
  assert.equal(panelUrlWarning('https://panel.example.com'), null);
  assert.equal(panelUrlWarning('https://128.0.0.1'), null);
  assert.equal(panelUrlWarning('not a url'), null);
});
