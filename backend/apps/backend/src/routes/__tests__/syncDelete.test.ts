/** DELETE /api/sync/me — account deletion; the token stops working afterwards. */

import { test, beforeEach, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import type { AddressInfo } from 'node:net';
import type { Server } from 'node:http';
import express from 'express';
import { createSyncRouter } from '../sync.js';
import { signLearnerToken } from '../../services/learnerIdentity.js';
import { ORG, makeAuthDb, makeFirebase } from './fakeAuthDb.js';

const SECRET = 'secret-for-delete-tests';
let servers: Server[] = [];
const savedEnv = { ...process.env };
beforeEach(() => { process.env.JWT_SECRET = SECRET; });
afterEach(() => {
  for (const s of servers) s.close();
  servers = [];
  for (const k of Object.keys(process.env)) if (!(k in savedEnv)) delete process.env[k];
  Object.assign(process.env, savedEnv);
});

async function startApp(seed: Parameters<typeof makeAuthDb>[0]) {
  const db = makeAuthDb(seed);
  const app = express();
  app.use(express.json());
  app.use('/api/sync', createSyncRouter({ db, generator: { generate: async () => ({ id: 'x' }) }, firebase: () => makeFirebase() }));
  const server = await new Promise<Server>((r) => { const s = app.listen(0, '127.0.0.1', () => r(s)); });
  servers.push(server);
  const base = `http://127.0.0.1:${(server.address() as AddressInfo).port}/api/sync`;
  const as = (token: string) => ({ Authorization: `Bearer ${token}` });
  return { db, base, as };
}

const tokenFor = (l: { id: string; phone: string }) =>
  signLearnerToken({ id: l.id, organization_id: ORG, phone: l.phone, name: null }, SECRET);

test('delete: removes the account, and the same token then gets 401 on /me', async () => {
  const { db, base, as } = await startApp([
    { phone: '919876543210', whatsapp_id: '919876543210', name: 'Asha' },
    { phone: '919000000001', whatsapp_id: '919000000001', name: 'Other' },
  ]);
  const token = tokenFor(db.learners[0]);
  assert.equal((await fetch(`${base}/me`, { headers: as(token) })).status, 200);

  const del = await fetch(`${base}/me`, { method: 'DELETE', headers: as(token) });
  assert.equal(del.status, 200);
  assert.deepEqual(await del.json(), { success: true });

  assert.equal((await fetch(`${base}/me`, { headers: as(token) })).status, 401);
  assert.equal(db.learners.length, 1);
  assert.equal(db.learners[0].name, 'Other'); // another learner is untouched
});

test('delete: also clears the number\'s sign-in codes and ledger, but not other numbers\'', async () => {
  const { db, base, as } = await startApp([{ phone: '919876543210', whatsapp_id: '919876543210', name: 'Asha' }]);
  const at = Date.now();
  db.challenges.push(
    { id: 'a', phone: '+919876543210', code_hash: 'h', expires_at: new Date(at + 1000).toISOString(), attempts: 0, consumed_at: null, created_at: 1, at },
    { id: 'b', phone: '+919000000009', code_hash: 'h', expires_at: new Date(at + 1000).toISOString(), attempts: 0, consumed_at: null, created_at: 2, at });
  db.events.push({ phone: '+919876543210', kind: 'start', at }, { phone: '+919000000009', kind: 'start', at });
  const res = await fetch(`${base}/me`, { method: 'DELETE', headers: as(tokenFor(db.learners[0])) });
  assert.equal(res.status, 200);
  assert.deepEqual(db.challenges.map((c) => c.id), ['b']);
  assert.deepEqual(db.events.map((e) => e.phone), ['+919000000009']);
});

test('delete: an app-only (fb:) learner can delete too', async () => {
  const { db, base, as } = await startApp([{ phone: 'fb:AbC123', name: 'Mira', email: 'mira@example.com' }]);
  const token = tokenFor(db.learners[0]);
  assert.equal((await fetch(`${base}/me`, { method: 'DELETE', headers: as(token) })).status, 200);
  assert.equal(db.learners.length, 0);
  assert.equal((await fetch(`${base}/me`, { headers: as(token) })).status, 401);
});

test('delete: needs a valid learner token', async () => {
  const { base } = await startApp([{ phone: '919876543210', name: 'Asha' }]);
  assert.equal((await fetch(`${base}/me`, { method: 'DELETE' })).status, 401);
  assert.equal((await fetch(`${base}/me`, { method: 'DELETE', headers: { Authorization: 'Bearer nope' } })).status, 401);
});

test('delete: runs as ONE statement, so it is all-or-nothing', async () => {
  const { db, base, as } = await startApp([{ phone: '919876543210', name: 'Asha' }]);
  const seen: string[] = [];
  const raw = db.raw.bind(db);
  db.raw = async (sql: string, p?: any[]) => { if (/DELETE FROM learners/.test(sql)) seen.push(sql); return raw(sql, p); };
  await fetch(`${base}/me`, { method: 'DELETE', headers: as(tokenFor(db.learners[0])) });
  assert.equal(seen.length, 1);
  for (const table of ['otp_challenges', 'otp_events', 'message_log', 'failed_messages', 'learners']) {
    assert.match(seen[0], new RegExp(`DELETE FROM ${table}`), table);
  }
});
