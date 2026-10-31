/** POST /api/auth/whatsapp/start and /verify — real router, stub Firebase, real OTP logic over a fake table. */

import { test, afterEach, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import type { AddressInfo } from 'node:net';
import type { Server } from 'node:http';
import express from 'express';
import { createWhatsappAuthRouter } from '../authWhatsapp.js';
import { ChallengeOtpProvider, OtpProvider } from '../../services/whatsappOtp.js';
import { makeAuthDb, makeFirebase } from './fakeAuthDb.js';

const PHONE = '+919876543210';
let servers: Server[] = [];
const savedEnv = { ...process.env };
beforeEach(() => { process.env.JWT_SECRET = 'secret-for-otp-tests'; });
afterEach(() => {
  for (const s of servers) s.close();
  servers = [];
  for (const k of Object.keys(process.env)) if (!(k in savedEnv)) delete process.env[k];
  Object.assign(process.env, savedEnv);
});

async function startApp(opts: { firebase?: boolean; otp?: boolean; now?: () => number } = {}) {
  const db = makeAuthDb();
  const sent: { phone: string; code: string }[] = [];
  const firebase = makeFirebase();
  const provider: OtpProvider = new ChallengeOtpProvider('test', db, async (phone, code) => { sent.push({ phone, code }); }, () => process.env.JWT_SECRET!, opts.now);
  const app = express();
  app.use(express.json());
  app.use('/api/auth/whatsapp', createWhatsappAuthRouter({
    otp: () => (opts.otp === false ? null : provider),
    firebase: () => (opts.firebase === false ? null : firebase),
  }));
  const server = await new Promise<Server>((r) => { const s = app.listen(0, '127.0.0.1', () => r(s)); });
  servers.push(server);
  const base = `http://127.0.0.1:${(server.address() as AddressInfo).port}/api/auth/whatsapp`;
  const post = (path: string, body: object) => fetch(`${base}${path}`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
  return { db, sent, firebase, post };
}

// ── start ──────────────────────────────────────────────────────────────────

test('start: sends a code and answers with the fixed shape', async () => {
  const { post, sent } = await startApp();
  const res = await post('/start', { phone: PHONE });
  assert.equal(res.status, 200);
  assert.deepEqual(await res.json(), { success: true, data: { resendAfterSeconds: 30, expiresInSeconds: 600 } });
  assert.equal(sent.length, 1);
  assert.equal(sent[0].phone, PHONE);
});

test('start: the response is identical for a known and an unknown number', async () => {
  const { post, db } = await startApp();
  db.learners.push({ id: '00000000-0000-4000-8000-0000000000c1', organization_id: 'o', phone: '919876543210', whatsapp_id: '919876543210', name: 'Known', email: null, created_at: 0 });
  const known = await (await post('/start', { phone: PHONE })).json();
  const unknown = await (await post('/start', { phone: '+919000000001' })).json();
  assert.deepEqual(known, unknown);
});

test('start: a bad phone is 400', async () => {
  const { post, sent } = await startApp();
  for (const phone of ['9876543210', '+0123', '+91 98765', undefined, 5]) {
    assert.equal((await post('/start', { phone })).status, 400, String(phone));
  }
  assert.equal(sent.length, 0);
});

test('start: 3 codes per phone per 15 minutes, then 429 (other numbers unaffected)', async () => {
  const { post, sent } = await startApp();
  const statuses: number[] = [];
  for (let i = 0; i < 4; i++) statuses.push((await post('/start', { phone: PHONE })).status);
  assert.deepEqual(statuses, [200, 200, 200, 429]);
  assert.equal(sent.length, 3);
  assert.equal((await post('/start', { phone: '+919000000001' })).status, 200);
});

test('start: 5 requests per IP per hour, then 429', async () => {
  const { post } = await startApp();
  const statuses: number[] = [];
  for (let i = 0; i < 7; i++) statuses.push((await post('/start', { phone: `+9190000000${10 + i}` })).status);
  assert.deepEqual(statuses, [200, 200, 200, 200, 200, 429, 429]);
});

test('start: 503 when Firebase or the code provider is not configured, and nothing is sent', async () => {
  for (const opts of [{ firebase: false }, { otp: false }]) {
    const { post, sent } = await startApp(opts);
    assert.equal((await post('/start', { phone: PHONE })).status, 503);
    assert.equal((await post('/verify', { phone: PHONE, code: '123456' })).status, 503);
    assert.equal(sent.length, 0);
  }
});

test('start: a delivery failure is a 502 that does not leak whether the number is known', async () => {
  const app = express();
  app.use(express.json());
  const failing: OtpProvider = { name: 'f', start: async () => { throw new Error('twilio down'); }, check: async () => 'wrong' };
  app.use('/w', createWhatsappAuthRouter({ otp: () => failing, firebase: () => makeFirebase() }));
  const server = await new Promise<Server>((r) => { const s = app.listen(0, '127.0.0.1', () => r(s)); });
  servers.push(server);
  const res = await fetch(`http://127.0.0.1:${(server.address() as AddressInfo).port}/w/start`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ phone: PHONE }) });
  assert.equal(res.status, 502);
});

// ── verify ─────────────────────────────────────────────────────────────────

test('verify: the right code returns a Firebase custom token for uid wa:<digits> with a phone claim', async () => {
  const { post, sent, firebase } = await startApp();
  await post('/start', { phone: PHONE });
  const res = await post('/verify', { phone: PHONE, code: sent[0].code });
  assert.equal(res.status, 200);
  assert.deepEqual(await res.json(), { success: true, data: { firebaseCustomToken: 'custom-token-for:wa:919876543210' } });
  assert.deepEqual(firebase.calls.custom, [{ uid: 'wa:919876543210', claims: { phone: PHONE } }]);
});

test('verify: a wrong code is a generic 401, the same body as an expired one', async () => {
  let t = Date.now();
  const { post, sent } = await startApp({ now: () => t });
  await post('/start', { phone: PHONE });
  const wrong = sent[0].code === '000000' ? '111111' : '000000';
  const wrongRes = await post('/verify', { phone: PHONE, code: wrong });
  assert.equal(wrongRes.status, 401);
  t += 11 * 60_000;
  const expiredRes = await post('/verify', { phone: PHONE, code: sent[0].code });
  assert.equal(expiredRes.status, 401);
  assert.deepEqual(await wrongRes.json(), await expiredRes.json());
});

test('verify: 5 wrong attempts lock the code with a 429, even for the correct code', async () => {
  const { post, sent } = await startApp();
  await post('/start', { phone: PHONE });
  const wrong = sent[0].code === '000000' ? '111111' : '000000';
  const statuses: number[] = [];
  for (let i = 0; i < 5; i++) statuses.push((await post('/verify', { phone: PHONE, code: wrong })).status);
  assert.deepEqual(statuses, [401, 401, 401, 401, 429]);
  assert.equal((await post('/verify', { phone: PHONE, code: sent[0].code })).status, 429);
});

test('verify: a malformed code or phone is a 400 and does not spend an attempt', async () => {
  const { post, sent, db } = await startApp();
  await post('/start', { phone: PHONE });
  for (const code of ['12345', '1234567', 'abcdef', '12 456', 123456, undefined]) {
    assert.equal((await post('/verify', { phone: PHONE, code })).status, 400, String(code));
  }
  assert.equal((await post('/verify', { phone: 'nope', code: '123456' })).status, 400);
  assert.equal(db.challenges[0].attempts, 0);
  assert.equal((await post('/verify', { phone: PHONE, code: sent[0].code })).status, 200);
});

test('verify: a code is single-use', async () => {
  const { post, sent } = await startApp();
  await post('/start', { phone: PHONE });
  assert.equal((await post('/verify', { phone: PHONE, code: sent[0].code })).status, 200);
  assert.equal((await post('/verify', { phone: PHONE, code: sent[0].code })).status, 401);
});

test('codes are never written to the log in production', async () => {
  const { logger } = await import('../../lib/logger.js');
  const lines: string[] = [];
  const spy = (orig: any) => (...a: any[]) => { lines.push(a.join(' ')); return orig?.apply(logger, a); };
  const origInfo = (logger as any).info, origWarn = (logger as any).warn, origError = (logger as any).error;
  (logger as any).info = spy(origInfo); (logger as any).warn = spy(origWarn); (logger as any).error = spy(origError);
  process.env.NODE_ENV = 'production';
  try {
    const { post, sent } = await startApp();
    await post('/start', { phone: PHONE });
    await post('/verify', { phone: PHONE, code: sent[0].code });
    assert.ok(sent[0].code.length === 6);
    assert.ok(!lines.some((l) => l.includes(sent[0].code)), 'no log line may contain the code');
  } finally {
    (logger as any).info = origInfo; (logger as any).warn = origWarn; (logger as any).error = origError;
  }
});
