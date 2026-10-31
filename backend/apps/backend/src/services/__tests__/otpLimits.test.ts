/** Durable sign-in caps: 10 codes/day/phone, 20 wrong/day then a 24 h lock, global daily cap. */

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { OtpLimits, dailyCap, DEFAULT_DAILY_CAP } from '../otpLimits.js';
import { makeAuthDb } from '../../routes/__tests__/fakeAuthDb.js';

const PHONE = '+919876543210';
const HOUR = 3_600_000;

test('at most 10 codes per phone in 24 hours; the 11th is refused, and it frees up after a day', async () => {
  let t = Date.now();
  const limits = new OtpLimits(makeAuthDb([], () => t), {});
  for (let i = 0; i < 10; i++) assert.equal(await limits.claimStart(PHONE), 'ok', `code ${i + 1}`);
  assert.equal(await limits.claimStart(PHONE), 'phone_cap');
  assert.equal(await limits.claimStart('+919000000001'), 'ok'); // other numbers unaffected
  t += 25 * HOUR;
  assert.equal(await limits.claimStart(PHONE), 'ok');
});

test('20 wrong guesses in 24 hours start a 24-hour lock that blocks codes and guesses', async () => {
  let t = Date.now();
  const limits = new OtpLimits(makeAuthDb([], () => t), {});
  for (let i = 0; i < 19; i++) await limits.recordWrong(PHONE);
  assert.equal(await limits.isLocked(PHONE), false);
  await limits.recordWrong(PHONE);
  assert.equal(await limits.isLocked(PHONE), true);
  assert.equal(await limits.claimStart(PHONE), 'locked');
  t += 23 * HOUR;
  assert.equal(await limits.isLocked(PHONE), true);
  t += 2 * HOUR;
  assert.equal(await limits.isLocked(PHONE), false);
  assert.equal(await limits.claimStart(PHONE), 'ok');
});

test('wrong guesses spread over more than a day never lock', async () => {
  let t = Date.now();
  const limits = new OtpLimits(makeAuthDb([], () => t), {});
  for (let i = 0; i < 30; i++) { await limits.recordWrong(PHONE); t += 2 * HOUR; }
  assert.equal(await limits.isLocked(PHONE), false);
});

test('a lock is only written once while it lasts', async () => {
  const db = makeAuthDb();
  const limits = new OtpLimits(db, {});
  for (let i = 0; i < 25; i++) await limits.recordWrong(PHONE);
  assert.equal(db.events.filter((e) => e.kind === 'lock').length, 1);
});

test('the global daily cap defaults to 500, is set by OTP_DAILY_CAP, and counts across phones', async () => {
  assert.equal(dailyCap({}), DEFAULT_DAILY_CAP);
  assert.equal(DEFAULT_DAILY_CAP, 500);
  assert.equal(dailyCap({ OTP_DAILY_CAP: '25' }), 25);
  assert.equal(dailyCap({ OTP_DAILY_CAP: 'junk' }), 500);
  assert.equal(dailyCap({ OTP_DAILY_CAP: '0' }), 500);

  const limits = new OtpLimits(makeAuthDb(), { OTP_DAILY_CAP: '3' });
  assert.equal(await limits.claimStart('+919000000001'), 'ok');
  assert.equal(await limits.claimStart('+919000000002'), 'ok');
  assert.equal(await limits.claimStart('+919000000003'), 'ok');
  assert.equal(await limits.claimStart('+919000000004'), 'global_cap');
});

test('the counters live in the database, so a new instance (a restart) sees them', async () => {
  const db = makeAuthDb();
  for (let i = 0; i < 10; i++) await new OtpLimits(db, {}).claimStart(PHONE);
  assert.equal(await new OtpLimits(db, {}).claimStart(PHONE), 'phone_cap');
});
