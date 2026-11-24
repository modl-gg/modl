import test from 'node:test';
import assert from 'node:assert/strict';

import type { ConnectedServer } from '@/hooks/data/servers';
import type { ServerKey } from '@/hooks/data/server-keys';
import { isRowConnected, lastUsedAt, serverRows } from './server-rows.ts';

const key = (overrides: Partial<ServerKey> = {}): ServerKey => ({
  id: 'k1',
  name: 'lobby',
  role: 'behind-proxy',
  keyHint: 'msk_abcd',
  createdAt: null,
  lastSeenAt: null,
  connected: false,
  allowProxyCommands: false,
  ...overrides,
});

const connection = (overrides: Partial<ConnectedServer> = {}): ConnectedServer => ({
  instanceId: 'i1',
  name: 'lobby',
  platform: 'SPIGOT',
  pluginVersion: '3.0.0',
  serverVersion: '1.21.11',
  onlinePlayers: 0,
  lastSeen: null,
  role: 'member',
  keyName: 'lobby',
  hubConnected: true,
  compatibility: 'compatible',
  ...overrides,
});

void test('a key that was never used shows no last used time', () => {
  const [row] = serverRows([key()], []);
  assert.ok(row);
  assert.equal(isRowConnected(row), false);
  assert.equal(lastUsedAt(row), null);
});

void test('a disconnected key that was used shows when', () => {
  const [row] = serverRows([key({ lastSeenAt: '2026-11-20T10:00:00.000Z' })], []);
  assert.ok(row);
  assert.equal(lastUsedAt(row), '2026-11-20T10:00:00.000Z');
});

void test('a connected key shows no last used time', () => {
  const [row] = serverRows([key({ lastSeenAt: '2026-11-20T10:00:00.000Z', connected: true })], []);
  assert.ok(row);
  assert.equal(isRowConnected(row), true);
  assert.equal(lastUsedAt(row), null);
});

void test('a key with a live connection groups it and shows no last used time', () => {
  const [row] = serverRows([key({ lastSeenAt: '2026-11-20T10:00:00.000Z' })], [connection()]);
  assert.ok(row);
  assert.equal(row.connections.length, 1);
  assert.equal(isRowConnected(row), true);
  assert.equal(lastUsedAt(row), null);
});

void test('connections without a known key come first as their own rows', () => {
  const rows = serverRows([key()], [connection({ instanceId: 'i2', name: 'hub', keyName: null })]);
  assert.deepEqual(rows.map((row) => row.id), ['connection-i2', 'key-k1']);
});

void test('a key whose only connection was refused shows the connection, not a last used time', () => {
  const [row] = serverRows([key({ lastSeenAt: '2026-11-20T10:00:00.000Z' })], [connection({ role: 'refused' })]);
  assert.ok(row);
  assert.equal(isRowConnected(row), false);
  assert.equal(lastUsedAt(row), null);
});
