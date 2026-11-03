/** META_ONLY: nothing but the Meta Model API may be called for AI. */

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { metaOnly, OTHER_PROVIDER_KEYS } from '../metaOnly.js';
import { llmChat, llmAvailable, llmSource } from '../llm.js';
import { anthropicClient } from '../anthropicClient.js';
import { azureOpenAI } from '../azureOpenAI.js';
import { modelRouter } from '../modelRouter.js';

test('default is on only when the Meta key is set and no other provider key is', () => {
  assert.equal(metaOnly({}), false, 'no keys at all: off');
  assert.equal(metaOnly({ MODEL_API_KEY: 'k' }), true, 'only the Meta key: on');
  for (const other of OTHER_PROVIDER_KEYS) {
    assert.equal(metaOnly({ MODEL_API_KEY: 'k', [other]: 'x' }), false, `${other} set: off by default`);
  }
});

test('an explicit META_ONLY value overrides the default in both directions', () => {
  for (const v of ['1', 'true', 'YES', ' on ']) {
    assert.equal(metaOnly({ META_ONLY: v, AZURE_OPENAI_API_KEY: 'x' }), true, `META_ONLY=${v}`);
  }
  for (const v of ['0', 'false', 'No', 'off']) {
    assert.equal(metaOnly({ META_ONLY: v, MODEL_API_KEY: 'k' }), false, `META_ONLY=${v}`);
  }
  assert.equal(metaOnly({ META_ONLY: 'maybe', MODEL_API_KEY: 'k' }), true, 'unknown value falls back to the default');
});

async function withEnv<T>(env: Record<string, string | undefined>, fn: () => Promise<T> | T): Promise<T> {
  const saved: Record<string, string | undefined> = {};
  for (const k of Object.keys(env)) { saved[k] = process.env[k]; if (env[k] === undefined) delete process.env[k]; else process.env[k] = env[k]; }
  try { return await fn(); } finally {
    for (const k of Object.keys(saved)) { if (saved[k] === undefined) delete process.env[k]; else process.env[k] = saved[k]; }
  }
}

test('with META_ONLY on, llmChat never reaches Anthropic or Azure even when they look available', async () => {
  const a: any = anthropicClient, z: any = azureOpenAI;
  const origA = [a.isAvailable, a.createChatCompletion], origZ = [z.isAvailable, z.createChatCompletion];
  const origFetch = globalThis.fetch;
  const calls: string[] = [];
  a.isAvailable = () => true; a.createChatCompletion = async () => { calls.push('anthropic'); return {}; };
  z.isAvailable = () => true; z.createChatCompletion = async () => { calls.push('azure'); return {}; };
  globalThis.fetch = (async (url: any) => {
    calls.push(String(url));
    return new Response(JSON.stringify({ model: 'muse-test', choices: [{ message: { content: 'hi' } }] }), { status: 200 });
  }) as typeof fetch;
  try {
    await withEnv({ META_ONLY: '1', MODEL_API_KEY: 'meta-test-key', META_MODEL_API_BASE_URL: 'https://meta.invalid/v1' }, async () => {
      const c = await llmChat({ messages: [{ role: 'user', content: 'x' }] });
      assert.equal(llmSource(c)?.provider, 'meta');
      assert.deepEqual(calls, ['https://meta.invalid/v1/chat/completions']);
    });
    // Control: with the flag forced off the Anthropic branch wins, proving the stubs are reachable.
    calls.length = 0;
    await withEnv({ META_ONLY: '0', MODEL_API_KEY: 'meta-test-key' }, async () => {
      await llmChat({ messages: [{ role: 'user', content: 'x' }] });
      assert.deepEqual(calls, ['anthropic']);
    });
  } finally {
    [a.isAvailable, a.createChatCompletion] = origA; [z.isAvailable, z.createChatCompletion] = origZ;
    globalThis.fetch = origFetch;
  }
});

test('with META_ONLY on, llmAvailable depends on the Meta key only and llmChat fails without it', async () => {
  const a: any = anthropicClient;
  const orig = a.isAvailable;
  a.isAvailable = () => true;
  try {
    await withEnv({ META_ONLY: '1', MODEL_API_KEY: undefined }, async () => {
      assert.equal(llmAvailable(), false);
      await assert.rejects(llmChat({ messages: [{ role: 'user', content: 'x' }] }), /META_ONLY is on/);
    });
    await withEnv({ META_ONLY: '1', MODEL_API_KEY: 'k' }, () => assert.equal(llmAvailable(), true));
    await withEnv({ META_ONLY: '0', MODEL_API_KEY: undefined }, () => assert.equal(llmAvailable(), true));
  } finally { a.isAvailable = orig; }
});

test('with META_ONLY on, Azure OpenAI (RAG embeddings, Whisper) and the Azure model router are off', async () => {
  await withEnv({ META_ONLY: '1', AZURE_OPENAI_ENDPOINT: 'https://x.invalid', AZURE_OPENAI_API_KEY: 'k', AZURE_SPEECH_KEY: 'k', AZURE_SPEECH_REGION: 'r', AZURE_VIDEO_DEPLOYMENT: 'v' }, async () => {
    azureOpenAI.initialize();
    assert.equal(azureOpenAI.isAvailable(), false);
    assert.throws(() => azureOpenAI.getClient(), /META_ONLY is on/);
    await assert.rejects(azureOpenAI.createEmbedding('q'), /META_ONLY is on/);
    assert.equal(modelRouter.isAvailable(), false);
    assert.deepEqual(modelRouter.capabilities(), { text: false, image: false, speech: false, transcribe: false, video: false });
  });
  await withEnv({ META_ONLY: '0', AZURE_OPENAI_ENDPOINT: 'https://x.invalid', AZURE_OPENAI_API_KEY: 'k' }, () => {
    azureOpenAI.initialize();
    assert.equal(azureOpenAI.isAvailable(), true, 'forced off: Azure behaves as before');
    assert.equal(modelRouter.isAvailable(), true);
  });
});
