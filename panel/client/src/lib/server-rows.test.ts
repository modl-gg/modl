import test from 'node:test';
import assert from 'node:assert/strict';

import type { ConnectedServer } from '@/hooks/data/servers';
import type { ProxiedServer, ServerKey } from '@/hooks/data/server-keys';
import { isRowConnected, lastUsedAt, needsServerKey, serverRows } from './server-rows.ts';

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
  features: null,
  ...overrides,
});

const proxied = (overrides: Partial<ProxiedServer> = {}): ProxiedServer => ({
  name: 'survival',
  address: '10.0.0.3:25565',
  state: 'no-key',
  keyId: null,
  ...overrides,
});

const listing = (keys: ServerKey[], proxiedServers: ProxiedServer[] = []) => ({ keys, proxiedServers });

void test('a key that was never used shows no last used time', () => {
  const [row] = serverRows(listing([key()]), []);
  assert.ok(row);
  assert.equal(isRowConnected(row), false);
  assert.equal(lastUsedAt(row), null);
});

void test('a disconnected key that was used shows when', () => {
  const [row] = serverRows(listing([key({ lastSeenAt: '2026-11-20T10:00:00.000Z' })]), []);
  assert.ok(row);
  assert.equal(lastUsedAt(row), '2026-11-20T10:00:00.000Z');
});

void test('a connected key shows no last used time', () => {
  const [row] = serverRows(listing([key({ lastSeenAt: '2026-11-20T10:00:00.000Z', connected: true })]), []);
  assert.ok(row);
  assert.equal(isRowConnected(row), true);
  assert.equal(lastUsedAt(row), null);
});

void test('a key with a live connection groups it and shows no last used time', () => {
  const [row] = serverRows(listing([key({ lastSeenAt: '2026-11-20T10:00:00.000Z' })]), [connection()]);
  assert.ok(row);
  assert.equal(row.connections.length, 1);
  assert.equal(isRowConnected(row), true);
  assert.equal(lastUsedAt(row), null);
});

void test('connections without a known key come first as their own rows', () => {
  const rows = serverRows(listing([key()]), [connection({ instanceId: 'i2', name: 'hub', keyName: null })]);
  assert.deepEqual(rows.map((row) => row.id), ['connection-i2', 'key-k1']);
});

void test('a key whose only connection was refused shows the connection, not a last used time', () => {
  const [row] = serverRows(listing([key({ lastSeenAt: '2026-11-20T10:00:00.000Z' })]), [connection({ role: 'refused' })]);
  assert.ok(row);
  assert.equal(isRowConnected(row), false);
  assert.equal(lastUsedAt(row), null);
});

void test('a proxy server without a key is its own row that needs a key', () => {
  const rows = serverRows(listing([key()], [proxied()]), [connection({ instanceId: 'i2', name: 'hub', keyName: null })]);
  assert.deepEqual(rows.map((row) => row.id), ['connection-i2', 'proxied-survival', 'key-k1']);
  const [, row] = rows;
  assert.ok(row);
  assert.equal(needsServerKey(row), true);
  assert.equal(isRowConnected(row), false);
});

void test('a proxy server with a key joins the key row', () => {
  const rows = serverRows(listing([key()], [proxied({ name: 'lobby', state: 'connected', keyId: 'k1' })]), [connection()]);
  assert.deepEqual(rows.map((row) => row.id), ['key-k1']);
  const [row] = rows;
  assert.ok(row);
  assert.equal(row.proxied?.address, '10.0.0.3:25565');
  assert.equal(needsServerKey(row), false);
  assert.equal(isRowConnected(row), true);
});
