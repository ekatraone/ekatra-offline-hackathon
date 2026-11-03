/**
 * META_ONLY: a hard guarantee that nothing but the Meta Model API is called for AI.
 *
 * - META_ONLY=1 (or true/yes/on) forces it on; META_ONLY=0 (or false/no/off) forces it off.
 * - Unset: it is ON when MODEL_API_KEY (the Meta key) is set and no key for any other
 *   AI provider is set, so a deployment configured with only the Meta key is Meta-only
 *   without further configuration.
 *
 * When on, llmChat skips the Anthropic and Azure OpenAI branches, llmAvailable() looks at
 * the Meta key only, the Azure OpenAI wrapper and the Azure model router report unavailable
 * (so RAG embeddings, Whisper, gpt-image, Azure speech and video stay off).
 */

/** Environment variables whose presence means a non-Meta AI provider is configured. */
export const OTHER_PROVIDER_KEYS = [
  'AZURE_ANTHROPIC_API_KEY',
  'ANTHROPIC_API_KEY',
  'AZURE_OPENAI_API_KEY',
  'OPENAI_API_KEY',
  'AZURE_LLAMA_API_KEY',
  'GOOGLE_API_KEY',
  'AZURE_SPEECH_KEY',
  'ELEVENLABS_API_KEY',
] as const;

const ON = new Set(['1', 'true', 'yes', 'on']);
const OFF = new Set(['0', 'false', 'no', 'off']);

export function metaOnly(env: NodeJS.ProcessEnv = process.env): boolean {
  const flag = (env.META_ONLY || '').trim().toLowerCase();
  if (ON.has(flag)) return true;
  if (OFF.has(flag)) return false;
  return Boolean(env.MODEL_API_KEY) && !OTHER_PROVIDER_KEYS.some((k) => Boolean(env[k]));
}
