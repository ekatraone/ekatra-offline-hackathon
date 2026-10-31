/** GET /api/sync/media/:id — public, validated, long-cached. */

import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import type { AddressInfo } from 'node:net';
import type { Server } from 'node:http';
import express from 'express';
import { createSyncRouter } from '../sync.js';

const ID = '00000000-0000-4000-8000-0000000000f1';
const JPEG = Buffer.from([0xff, 0xd8, 0xff, 0xe0, 1, 2, 3, 0xff, 0xd9]);

let server: Server;
let base = '';

before(async () => {
  const db = {
    async raw(sql: string, p: any[] = []) {
      if (sql.includes('FROM lesson_media') && p[0] === ID) return { rows: [{ mime: 'image/jpeg', bytes: JPEG }] };
      return { rows: [] };
    },
  };
  const app = express();
  app.use('/api/sync', createSyncRouter({ db, generator: { generate: async () => ({ id: 'x' }) } }));
  await new Promise<void>((r) => { server = app.listen(0, '127.0.0.1', () => r()); });
  base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
});
after(() => server.close());

test('media: serves the JPEG with no auth and immutable public caching', async () => {
  const res = await fetch(`${base}/api/sync/media/${ID}`);
  assert.equal(res.status, 200);
  assert.equal(res.headers.get('content-type'), 'image/jpeg');
  assert.equal(res.headers.get('cache-control'), 'public, max-age=31536000, immutable');
  assert.deepEqual(Buffer.from(await res.arrayBuffer()), JPEG);
});

test('media: a non-UUID id or an unknown id is a 404', async () => {
  assert.equal((await fetch(`${base}/api/sync/media/not-a-uuid`)).status, 404);
  assert.equal((await fetch(`${base}/api/sync/media/1%27%20OR%201=1`)).status, 404);
  assert.equal((await fetch(`${base}/api/sync/media/00000000-0000-4000-8000-0000000000f2`)).status, 404);
});

test('media: sends an ETag and answers If-None-Match with 304 and no body', async () => {
  const first = await fetch(`${base}/api/sync/media/${ID}`);
  const etag = first.headers.get('etag');
  assert.equal(etag, `"${ID}"`);
  const second = await fetch(`${base}/api/sync/media/${ID}`, { headers: { 'If-None-Match': etag! } });
  assert.equal(second.status, 304);
  assert.equal((await second.arrayBuffer()).byteLength, 0);
  assert.equal(second.headers.get('cache-control'), 'public, max-age=31536000, immutable');
  const stale = await fetch(`${base}/api/sync/media/${ID}`, { headers: { 'If-None-Match': '"other"' } });
  assert.equal(stale.status, 200);
});

test('media: has its own per-IP limit of 120 requests per minute', async () => {
  let limited = 0;
  for (let i = 0; i < 130; i++) {
    const res = await fetch(`${base}/api/sync/media/${ID}`, { headers: { 'If-None-Match': `"${ID}"` } });
    if (res.status === 429) limited++;
  }
  assert.ok(limited >= 1, 'expected a 429 once the per-minute limit is exceeded');
});

test('media: the rest of the sync API still requires auth', async () => {
  assert.equal((await fetch(`${base}/api/sync/me`)).status, 401);
});
