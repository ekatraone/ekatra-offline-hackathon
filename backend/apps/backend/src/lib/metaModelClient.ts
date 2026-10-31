/**
 * Meta Model API client (Muse Spark).
 *
 * OpenAI-compatible Chat Completions at META_MODEL_API_BASE_URL. Server-side only.
 * Muse Spark always reasons, so reasoning tokens count against max_completion_tokens;
 * the budget below is padded to leave room for visible output.
 */

import { logger } from './logger.js';

const DEFAULT_BASE_URL = 'https://api.meta.ai/v1';
const DEFAULT_MODEL = 'muse-spark-1.3';
const REASONING_HEADROOM = 4000;
const MAX_COMPLETION_TOKENS = 16000;
const REQUEST_TIMEOUT_MS = 240_000;

class MetaModelClient {
  isAvailable(): boolean {
    return !!process.env.MODEL_API_KEY;
  }

  async createChatCompletion(params: {
    messages: Array<{ role: string; content: string }>;
    temperature?: number;
    maxTokens?: number;
  }) {
    const apiKey = process.env.MODEL_API_KEY;
    if (!apiKey) throw new Error('Meta Model API not configured — set MODEL_API_KEY');

    const baseUrl = (process.env.META_MODEL_API_BASE_URL || DEFAULT_BASE_URL).replace(/\/+$/, '');
    const model = process.env.META_MODEL_ID || DEFAULT_MODEL;
    const budget = Math.min((params.maxTokens || 500) + REASONING_HEADROOM, MAX_COMPLETION_TOKENS);

    const res = await fetch(`${baseUrl}/chat/completions`, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${apiKey}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        model,
        messages: params.messages,
        max_completion_tokens: budget,
        reasoning_effort: 'low',
        ...(params.temperature !== undefined ? { temperature: params.temperature } : {}),
      }),
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    });

    if (!res.ok) {
      const detail = (await res.text()).slice(0, 300);
      logger.error('Meta Model API error', { status: res.status, detail });
      throw new Error(`Meta Model API ${res.status}`);
    }
    return res.json();
  }
}

export const metaModelClient = new MetaModelClient();
