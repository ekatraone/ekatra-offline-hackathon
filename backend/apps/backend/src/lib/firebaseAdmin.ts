/**
 * Lazy Firebase Admin for sign-in (project maya-ai-ekatra).
 *
 * FIREBASE_SERVICE_ACCOUNT is a base64-encoded service-account JSON (raw JSON is accepted too).
 * When it is unset or unusable, `getFirebaseAuth()` returns null and the auth routes answer 503;
 * nothing else in the server depends on it. A named app is used so the default Firebase app,
 * if any other code creates one, is never touched.
 */

import admin from 'firebase-admin';
import { logger } from './logger.js';

/** The slice of firebase-admin's Auth that sign-in uses (also what tests stub). */
export interface FirebaseAuthLike {
  verifyIdToken(idToken: string, checkRevoked?: boolean): Promise<Record<string, any>>;
  createCustomToken(uid: string, developerClaims?: object): Promise<string>;
}

const APP_NAME = 'ekatra-sign-in';
let override: FirebaseAuthLike | null | undefined;
let cached: FirebaseAuthLike | null | undefined;

/** Tests inject a stub; `undefined` restores the real lookup. */
export function setFirebaseAuthForTests(auth: FirebaseAuthLike | null | undefined): void {
  override = auth;
  cached = undefined;
}

function parseServiceAccount(raw: string): Record<string, any> {
  const text = raw.trim().startsWith('{') ? raw : Buffer.from(raw, 'base64').toString('utf-8');
  return JSON.parse(text);
}

export function getFirebaseAuth(): FirebaseAuthLike | null {
  if (override !== undefined) return override;
  if (cached !== undefined) return cached;

  const raw = process.env.FIREBASE_SERVICE_ACCOUNT;
  if (!raw) {
    cached = null;
    return null;
  }
  try {
    const app =
      admin.apps.find((a) => a?.name === APP_NAME) ??
      admin.initializeApp({ credential: admin.credential.cert(parseServiceAccount(raw) as admin.ServiceAccount) }, APP_NAME);
    cached = admin.auth(app);
  } catch (err: any) {
    // Fixed text plus the error class only: the message can quote part of the credential.
    logger.error('FIREBASE_SERVICE_ACCOUNT is set but could not be used', { errorClass: err?.constructor?.name ?? 'Error' });
    cached = null;
  }
  return cached;
}
