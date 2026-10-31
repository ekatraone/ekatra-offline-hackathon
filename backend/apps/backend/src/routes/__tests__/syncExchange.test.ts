/** POST /api/sync/auth/exchange — Firebase ID token → learner JWT, and fb: learners never being messaged. */

import { test, beforeEach, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import type { AddressInfo } from 'node:net';
import type { Server } from 'node:http';
import express from 'express';
import jwt from 'jsonwebtoken';
import { createSyncRouter } from '../sync.js';
import { ORG, makeAuthDb, makeFirebase } from './fakeAuthDb.js';

const SECRET = 'secret-for-exchange-tests';
let servers: Server[] = [];
const savedEnv = { ...process.env };
beforeEach(() => { process.env.JWT_SECRET = SECRET; });
afterEach(() => {
  for (const s of servers) s.close();
  servers = [];
  for (const k of Object.keys(process.env)) if (!(k in savedEnv)) delete process.env[k];
  Object.assign(process.env, savedEnv);
});

async function startApp(seed: Parameters<typeof makeAuthDb>[0] = [], withFirebase = true) {
  const db = makeAuthDb(seed);
  const firebase = makeFirebase();
  const app = express();
  app.use(express.json());
  app.use('/api/sync', createSyncRouter({ db, generator: { generate: async () => ({ id: 'x' }) }, firebase: () => (withFirebase ? firebase : null) }));
  const server = await new Promise<Server>((r) => { const s = app.listen(0, '127.0.0.1', () => r(s)); });
  servers.push(server);
  const base = `http://127.0.0.1:${(server.address() as AddressInfo).port}/api/sync`;
  const exchange = (claims: object | string) =>
    fetch(`${base}/auth/exchange`, { method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ idToken: typeof claims === 'string' ? claims : JSON.stringify(claims) }) });
  return { db, firebase, base, exchange };
}

test('exchange: an invalid or revoked token is a 401, and revocation is checked', async () => {
  const { exchange, firebase } = await startApp();
  assert.equal((await exchange('not-a-token')).status, 401);
  assert.equal((await exchange({ nouid: true })).status, 401);
  assert.ok(firebase.calls.verify.length >= 1 && firebase.calls.verify.every((c) => c.checkRevoked === true));
});

test('exchange: a missing idToken is a 400, and no Firebase configuration is a 503', async () => {
  const { base } = await startApp();
  const res = await fetch(`${base}/auth/exchange`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' });
  assert.equal(res.status, 400);
  const off = await startApp([], false);
  assert.equal((await off.exchange({ uid: 'u1' })).status, 503);
});

test('exchange: a phone token maps onto the row the WhatsApp flow created (bare-digit phone)', async () => {
  const { exchange, db } = await startApp([{ phone: '919876543210', whatsapp_id: '919876543210', name: 'Friend' }]);
  const body: any = await (await exchange({ uid: 'wa:919876543210', phone: '+919876543210' })).json();
  assert.equal(body.data.learner.id, db.learners[0].id);
  assert.equal(body.data.learner.phone, '+919876543210');
  assert.equal(db.learners.length, 1);
});

test('exchange: Firebase phone_number and the wa: uid alone both resolve to the same phone learner', async () => {
  const { exchange, db } = await startApp();
  const viaUid: any = await (await exchange({ uid: 'wa:919876543210' })).json();
  const viaClaim: any = await (await exchange({ uid: 'someFirebaseUid', phone_number: '+919876543210' })).json();
  assert.equal(viaUid.data.learner.id, viaClaim.data.learner.id);
  assert.equal(db.learners.length, 1);
  assert.equal(db.learners[0].phone, '919876543210'); // written the way WhatsApp writes it
});

test('exchange: for wa: users the uid wins over a mismatching phone claim', async () => {
  const { exchange, db } = await startApp();
  const body: any = await (await exchange({ uid: 'wa:919876543210', phone: '+911111111111' })).json();
  assert.equal(body.data.learner.phone, '+919876543210');
  assert.equal(db.learners.length, 1);
});

test('exchange: a malformed wa: uid or phone claim is a 401', async () => {
  const { exchange } = await startApp();
  assert.equal((await exchange({ uid: 'wa:12' })).status, 401);
  assert.equal((await exchange({ uid: 'wa:abc' })).status, 401);
  assert.equal((await exchange({ uid: 'g1', phone_number: 'not-a-phone' })).status, 401);
});

test('exchange: a Google/email user maps to a "fb:<uid>" learner with name and email', async () => {
  const { exchange, db } = await startApp();
  const body: any = await (await exchange({ uid: 'AbC123xyz', name: 'Asha Rao', email: 'asha@example.com' })).json();
  assert.equal(body.success, true);
  assert.deepEqual(body.data.learner, { id: db.learners[0].id, name: 'Asha Rao', phone: null, email: 'asha@example.com' });
  assert.equal(db.learners[0].phone, 'fb:AbC123xyz');
  assert.equal(db.learners[0].email, 'asha@example.com');
  assert.equal(db.learners[0].whatsapp_id, null);
});

test('exchange: the same fb: user gets the same learner on a second exchange, and a later email is filled in', async () => {
  const { exchange, db } = await startApp();
  const first: any = await (await exchange({ uid: 'u9', name: 'Ravi' })).json();
  const second: any = await (await exchange({ uid: 'u9', name: 'Other', email: 'ravi@example.com' })).json();
  assert.equal(first.data.learner.id, second.data.learner.id);
  assert.equal(db.learners.length, 1);
  assert.equal(db.learners[0].name, 'Ravi');
  assert.equal(db.learners[0].email, 'ravi@example.com');
});

test('exchange: a user with no name falls back to the email name, then "Friend"', async () => {
  const { exchange, db } = await startApp();
  await exchange({ uid: 'u1', email: 'priya.s@example.com' });
  await exchange({ uid: 'u2' });
  assert.deepEqual(db.learners.map((l) => l.name), ['priya.s', 'Friend']);
});

test('exchange: the returned token has the learner format and passes /me; fb: learners show no phone', async () => {
  const { exchange, base, db } = await startApp();
  const { data }: any = await (await exchange({ uid: 'u7', name: 'Mira' })).json();
  const payload: any = jwt.verify(data.token, SECRET, { algorithms: ['HS256'] });
  assert.deepEqual({ role: payload.role, learnerId: payload.learnerId, organizationId: payload.organizationId },
    { role: 'learner', learnerId: db.learners[0].id, organizationId: ORG });
  assert.equal(payload.exp - payload.iat, 30 * 24 * 60 * 60);

  const me: any = await (await fetch(`${base}/me`, { headers: { Authorization: `Bearer ${data.token}` } })).json();
  assert.equal(me.data.learner.id, db.learners[0].id);
  assert.equal(me.data.learner.phone, null); // the "fb:" address is never shown as a phone number
});

// ── fb: learners are never messaged ────────────────────────────────────────

test('messaging: nothing is ever sent to an "fb:" learner, whatever the provider', async () => {
  const { initializeMessaging, messaging, isAppOnlyRecipient } = await import('@ekatra/messaging');
  const realFetch = globalThis.fetch;
  let fetches = 0;
  globalThis.fetch = (async () => { fetches++; return new Response(JSON.stringify({ sid: 'SM1', messages: [{ id: 'x' }] }), { status: 200 }); }) as any;
  try {
    for (const config of [
      { provider: 'twilio', twilioAccountSid: 'ACx', twilioAuthToken: 't', twilioFromNumber: '+15550001111' },
      { provider: 'cloudapi', accessToken: 't', phoneNumberId: '1', businessAccountId: '1' },
    ]) {
      initializeMessaging(config as any);
      const to = 'fb:AbC123xyz';
      const results = [
        await messaging.sendText(to, { text: 'hi' }),
        await messaging.sendImage(to, { imageUrl: 'https://x.test/a.jpg' }),
        await messaging.sendInteractiveButtons(to, { body: 'b', buttons: [{ id: 'a', title: 'A' }] }),
        await messaging.sendTemplate(to, 'HXabc', { '1': 'x' }),
        await messaging.sendDocument(to, { documentUrl: 'https://x.test/a.pdf' }),
      ];
      for (const r of results) assert.equal(r.success, false);
    }
    assert.equal(fetches, 0, 'no network call may be made for an fb: recipient');
    assert.equal(isAppOnlyRecipient('fb:abc'), true);
    assert.equal(isAppOnlyRecipient('tg:123'), false);
    assert.equal(isAppOnlyRecipient('919876543210'), false);

    // a normal WhatsApp recipient still goes out
    initializeMessaging({ provider: 'twilio', twilioAccountSid: 'ACx', twilioAuthToken: 't', twilioFromNumber: '+15550001111' } as any);
    assert.equal((await messaging.sendText('919876543210', { text: 'hi' })).success, true);
    assert.equal(fetches, 1);
  } finally {
    globalThis.fetch = realFetch;
  }
});
