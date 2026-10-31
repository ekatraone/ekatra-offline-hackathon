/**
 * B9 — image prompts, best-effort image generation, generator provenance, createImage,
 * and Twilio sending images as real media.
 */

import { test, beforeEach, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import { CourseGeneratorService, buildCourseSettings } from '../courseGenerator.js';
import { generateCourseImages, withImageStyle, IMAGE_STYLE_SUFFIX, mediaUrl, publicBaseUrl } from '../courseImages.js';
import { metaModelClient, metaModelId } from '../../lib/metaModelClient.js';

const realFetch = globalThis.fetch;
const savedEnv = { ...process.env };

beforeEach(() => {
  process.env.MODEL_API_KEY = 'test-key';
  process.env.META_MODEL_API_BASE_URL = 'https://models.test/v1';
  process.env.META_MODEL_ID = 'muse-wonder-1.0';
  delete process.env.META_IMAGE_MODEL_ID;
});
afterEach(() => {
  globalThis.fetch = realFetch;
  for (const k of Object.keys(process.env)) if (!(k in savedEnv)) delete process.env[k];
  Object.assign(process.env, savedEnv);
});

const courseJson = (modules = 3) => JSON.stringify({
  title: 'T', description: 'D', coverImagePrompt: 'a piggy bank on a hill',
  days: [{
    dayNumber: 1,
    modules: Array.from({ length: modules }, (_, i) => ({
      title: `M${i}`, content: 'c', imagePrompt: `scene ${i}`,
      quiz: { question: 'q', options: ['a', 'b', 'c', 'd'], correctAnswer: 'A', explanation: 'e' },
    })),
    comprehensionQuestions: [],
  }],
});

function mockChat(content: string, bodies: any[] = []) {
  globalThis.fetch = (async (_url: any, init: any) => {
    bodies.push(JSON.parse(init.body));
    return new Response(JSON.stringify({ model: 'ignored', choices: [{ message: { content } }] }), { status: 200 });
  }) as any;
  return bodies;
}

// ── prompts ────────────────────────────────────────────────────────────────

for (const size of ['micro', 'standard'] as const) {
  test(`${size} prompt asks for coverImagePrompt and a per-module imagePrompt`, async () => {
    const bodies = mockChat(courseJson());
    const svc: any = new CourseGeneratorService();
    await svc.callLLM('Saving', undefined, 'en', 'conversational', size === 'micro' ? 1 : 3, 3, size, 'meta');
    const prompt = bodies[0].messages.map((m: any) => m.content).join('\n');
    assert.match(prompt, /"coverImagePrompt"/);
    assert.match(prompt, /"imagePrompt"/);
    assert.equal(bodies[0].model, 'muse-wonder-1.0');
  });
}

// ── provenance ─────────────────────────────────────────────────────────────

test('a course generated without a forced provider (WhatsApp chat flow) still records the generator', async () => {
  mockChat(courseJson());
  const svc: any = new CourseGeneratorService();
  // No provider argument, as conversationFSM calls generate(): only MODEL_API_KEY is configured.
  const data = await svc.callLLM('Saving', undefined, 'en', 'casual', 1, 3, 'micro', undefined);
  assert.deepEqual(data.generator, { provider: 'meta', model: 'muse-wonder-1.0' });
  assert.deepEqual(buildCourseSettings(data, 'en', 'micro').generator, { provider: 'meta', model: 'muse-wonder-1.0' });
});

test('provenance reads the configured model, never a hard-coded one', () => {
  process.env.META_MODEL_ID = 'muse-next-9';
  assert.equal(metaModelId(), 'muse-next-9');
  assert.deepEqual(buildCourseSettings({ days: [] }, 'en', 'micro', 'meta').generator, { provider: 'meta', model: 'muse-next-9' });
  assert.equal(buildCourseSettings({ days: [] }, 'en', 'micro').generator, undefined);
});

// ── createImage ────────────────────────────────────────────────────────────

test('createImage posts the image model request and returns the JPEG bytes', async () => {
  let seen: any;
  globalThis.fetch = (async (url: any, init: any) => {
    seen = { url: String(url), body: JSON.parse(init.body) };
    return new Response(JSON.stringify({ data: [{ b64_json: Buffer.from('jpegbytes').toString('base64') }] }), { status: 200 });
  }) as any;
  const img = await metaModelClient.createImage('a cat');
  assert.equal(img.toString(), 'jpegbytes');
  assert.equal(seen.url, 'https://models.test/v1/images/generations');
  assert.deepEqual(seen.body, { model: 'muse-image-1.0', prompt: 'a cat', n: 1, size: '1024x1024', output_format: 'jpeg' });
});

test('createImage throws on HTTP errors and empty responses', async () => {
  globalThis.fetch = (async () => new Response('nope', { status: 500 })) as any;
  await assert.rejects(metaModelClient.createImage('x'), /500/);
  globalThis.fetch = (async () => new Response(JSON.stringify({ data: [] }), { status: 200 })) as any;
  await assert.rejects(metaModelClient.createImage('x'), /no image/);
});

// ── generateCourseImages ───────────────────────────────────────────────────

function imageDb() {
  const media: any[] = []; const updates: any[] = [];
  return {
    media, updates,
    async raw(sql: string, p: any[] = []) {
      if (sql.includes('INSERT INTO lesson_media')) {
        media.push({ course: p[0], lesson: p[1], kind: p[2], bytes: p[3] });
        return { rows: [{ id: `media-${media.length}` }] };
      }
      updates.push({ sql, p });
      return { rows: [] };
    },
  };
}

test('every image prompt gets the fixed style suffix', () => {
  assert.equal(withImageStyle('A piggy bank.'), `A piggy bank. ${IMAGE_STYLE_SUFFIX}`);
  assert.match(IMAGE_STYLE_SUFFIX, /no text, no letters, no numbers/);
});

test('stores a cover and module images and points the course and lessons at absolute URLs', async () => {
  const db = imageDb(); const prompts: string[] = [];
  const res = await generateCourseImages('c1', [
    { kind: 'cover', lessonId: null, prompt: 'cover idea' },
    { kind: 'module', lessonId: 'l1', prompt: 'one' },
    { kind: 'module', lessonId: 'l2', prompt: 'two' },
  ], { db, baseUrl: 'https://demo.example', createImage: async (p) => { prompts.push(p); return Buffer.alloc(10); } });
  assert.equal(res.stored, 3);
  assert.ok(prompts.every((p) => p.endsWith(IMAGE_STYLE_SUFFIX)));
  const cover = db.updates.find((u) => u.sql.includes('coverImageUrl'));
  assert.equal(cover.p[0], 'https://demo.example/api/sync/media/media-1');
  const lessonUpdates = db.updates.filter((u) => u.sql.includes('UPDATE lessons'));
  assert.deepEqual(lessonUpdates.map((u) => [u.p[1], u.p[0]]), [
    ['l1', 'https://demo.example/api/sync/media/media-2'], ['l2', 'https://demo.example/api/sync/media/media-3']]);
});

test('image failures never fail the lesson: the rest are still stored', async () => {
  const db = imageDb();
  const res = await generateCourseImages('c1', [
    { kind: 'cover', lessonId: null, prompt: 'cover' },
    { kind: 'module', lessonId: 'l1', prompt: 'boom' },
    { kind: 'module', lessonId: 'l2', prompt: 'ok' },
  ], { db, baseUrl: 'https://x', createImage: async (p) => { if (p.startsWith('boom')) throw new Error('muse image down'); return Buffer.alloc(5); } });
  assert.deepEqual([res.stored, res.failed], [2, 1]);
  assert.equal(db.media.length, 2);
  assert.equal(db.updates.filter((u) => u.sql.includes('UPDATE lessons')).length, 1);
});

test('the overall budget stops the wait on slow images', async () => {
  const db = imageDb();
  const started = Date.now();
  const res = await generateCourseImages('c1', [{ kind: 'cover', lessonId: null, prompt: 'slow' }], {
    db, baseUrl: 'https://x', budgetMs: 30, createImage: () => new Promise(() => undefined),
  });
  assert.ok(Date.now() - started < 1000);
  assert.deepEqual([res.stored, res.failed], [0, 1]);
});

test('image URLs use PUBLIC_BASE_URL, then WEBHOOK_BASE_URL; production without either gets none', () => {
  assert.equal(publicBaseUrl({ PUBLIC_BASE_URL: 'https://a.test/', WEBHOOK_BASE_URL: 'https://b.test' }), 'https://a.test');
  assert.equal(publicBaseUrl({ WEBHOOK_BASE_URL: 'https://b.test' }), 'https://b.test');
  assert.equal(publicBaseUrl({ NODE_ENV: 'production' }), null);
  assert.equal(publicBaseUrl({ PORT: '4000' }), 'http://localhost:4000');
  assert.equal(mediaUrl('https://a.test', 'abc'), 'https://a.test/api/sync/media/abc');
});

// ── Twilio sends images as media ───────────────────────────────────────────

test('TwilioProvider.sendImage sends MediaUrl, with the caption as Body', async () => {
  const { initializeMessaging, messaging } = await import('@ekatra/messaging');
  initializeMessaging({ provider: 'twilio', twilioAccountSid: 'ACtest', twilioAuthToken: 't', twilioFromNumber: '+15550001111' } as any);
  const sent: URLSearchParams[] = [];
  globalThis.fetch = (async (_u: any, init: any) => {
    sent.push(new URLSearchParams(init.body));
    return new Response(JSON.stringify({ sid: 'SM1' }), { status: 201 });
  }) as any;

  await messaging.sendImage('+919876543210', { imageUrl: 'https://demo.example/api/sync/media/abc' });
  await messaging.sendImage('+919876543210', { imageUrl: 'https://demo.example/api/sync/media/abc', caption: 'Ready!' });
  assert.equal(sent[0].get('MediaUrl'), 'https://demo.example/api/sync/media/abc');
  assert.equal(sent[0].get('Body'), null);
  assert.equal(sent[1].get('Body'), 'Ready!');
  assert.equal(sent[1].get('MediaUrl'), 'https://demo.example/api/sync/media/abc');
});
