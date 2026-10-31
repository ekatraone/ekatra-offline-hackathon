/**
 * Find or create the demo learner by phone and print a 30-day learner token.
 * On the demo deploy the phone is the user's WhatsApp number, so the WhatsApp
 * learner and the app learner are the same row.
 *
 * Usage (JWT_SECRET must be the target environment's secret):
 *   npx tsx src/scripts/issueDemoLearner.ts --phone +910000000042 [--name "Demo"]
 */

import jwt from 'jsonwebtoken';
import { assertSafeDatabase } from './safeDb.js';

assertSafeDatabase();

const arg = (flag: string) => {
  const i = process.argv.indexOf(flag);
  return i >= 0 ? process.argv[i + 1] : undefined;
};

const phone = arg('--phone');
const name = arg('--name') ?? 'Demo Learner';
if (!phone || !/^\+[1-9]\d{7,14}$/.test(phone)) {
  console.error('Usage: issueDemoLearner.ts --phone <E.164, e.g. +910000000042> [--name "Demo"]');
  process.exit(1);
}
const secret = process.env.JWT_SECRET;
if (!secret) {
  console.error('JWT_SECRET is not set');
  process.exit(1);
}

const { initializeDatabase, db } = await import('@ekatra/database');
initializeDatabase({ connectionString: process.env.DATABASE_URL });

let org = await db.organizations.getDefault();
if (!org) {
  const created = await db.raw(
    `INSERT INTO organizations (name, slug) VALUES ('Ekatra Demo', 'ekatra-demo') RETURNING *`,
  );
  org = created.rows[0];
}

const existing = await db.raw(`SELECT * FROM learners WHERE phone = $1 AND organization_id = $2`, [phone, org.id]);
let learner = existing.rows[0];
if (!learner) {
  const created = await db.raw(
    `INSERT INTO learners (organization_id, phone, name, language, timezone)
     VALUES ($1, $2, $3, 'en', 'Asia/Kolkata') RETURNING *`,
    [org.id, phone, name],
  );
  learner = created.rows[0];
}

const token = jwt.sign(
  { learnerId: learner.id, phone: learner.phone, role: 'learner', organizationId: org.id },
  secret,
  { algorithm: 'HS256', expiresIn: '30d' },
);

console.log(JSON.stringify({ learnerId: learner.id, organizationId: org.id, phone: learner.phone, created: !existing.rows[0] }));
console.log(`TOKEN=${token}`);
process.exit(0);
