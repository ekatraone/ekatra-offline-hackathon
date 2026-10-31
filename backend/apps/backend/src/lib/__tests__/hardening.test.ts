/** Firebase credential logging and the one-time proxy-chain log. */

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { getFirebaseAuth, setFirebaseAuthForTests } from '../firebaseAdmin.js';
import { truncateIp, describeProxyChain, logProxyChainOnce } from '../proxyDiagnostics.js';
import { logger } from '../logger.js';

test('an unusable FIREBASE_SERVICE_ACCOUNT logs a fixed message and the error class, never the error text', () => {
  const secret = 'TOPSECRETKEYMATERIAL-9f8e7d';
  const saved = process.env.FIREBASE_SERVICE_ACCOUNT;
  const logged: unknown[][] = [];
  const orig = (logger as any).error;
  (logger as any).error = (...args: unknown[]) => { logged.push(args); };
  try {
    setFirebaseAuthForTests(undefined);
    process.env.FIREBASE_SERVICE_ACCOUNT = JSON.stringify({ type: 'service_account', project_id: 'p', private_key: secret, client_email: `x@${secret}.iam` });
    assert.equal(getFirebaseAuth(), null);
  } finally {
    (logger as any).error = orig;
    if (saved === undefined) delete process.env.FIREBASE_SERVICE_ACCOUNT; else process.env.FIREBASE_SERVICE_ACCOUNT = saved;
    setFirebaseAuthForTests(undefined);
  }
  assert.equal(logged.length, 1);
  assert.equal(logged[0][0], 'FIREBASE_SERVICE_ACCOUNT is set but could not be used');
  assert.match(String((logged[0][1] as any).errorClass), /^\w+$/);
  assert.ok(!JSON.stringify(logged).includes(secret), 'no part of the credential may reach the log');
  assert.deepEqual(Object.keys(logged[0][1] as object), ['errorClass']);
});

test('truncateIp keeps only the first two octets / hextets', () => {
  assert.equal(truncateIp('203.0.113.77'), '203.0.x.x');
  assert.equal(truncateIp('::ffff:198.51.100.9'), '198.51.x.x');
  assert.equal(truncateIp('2001:db8:85a3::8a2e:370:7334'), '2001:db8::x');
  assert.equal(truncateIp('::1'), 'loopback');
  assert.equal(truncateIp(undefined), 'unknown');
  assert.equal(truncateIp('garbage'), 'unknown');
});

test('the proxy line shows truncated addresses and only the number of X-Forwarded-For hops', () => {
  const line = describeProxyChain({
    ip: '203.0.113.77',
    headers: { 'x-forwarded-for': '203.0.113.77, 100.64.0.5' },
    socket: { remoteAddress: '100.64.0.9' },
  } as any);
  assert.equal(line, 'proxy check: req.ip=203.0.x.x socket=100.64.x.x xff_hops=2');
  assert.ok(!line.includes('113') && !line.includes('77') && !line.includes('.5'), 'no full address');
  assert.match(describeProxyChain({ ip: '10.1.2.3', headers: {}, socket: {} } as any), /xff_hops=0/);
});

test('the proxy check logs exactly once, on the first request that carries X-Forwarded-For', () => {
  const lines: string[] = [];
  const mw = logProxyChainOnce((m) => lines.push(m));
  let nexts = 0;
  const next = () => { nexts++; };
  const internal: any = { ip: '100.64.0.7', headers: {}, socket: { remoteAddress: '100.64.0.7' } }; // platform health check
  const edge: any = { ip: '203.0.113.77', headers: { 'x-forwarded-for': '203.0.113.77' }, socket: { remoteAddress: '100.64.0.9' } };

  for (let i = 0; i < 3; i++) mw(internal, {} as any, next);
  assert.equal(lines.length, 0, 'requests without X-Forwarded-For are ignored');

  mw(edge, {} as any, next);
  mw(edge, {} as any, next);
  mw(internal, {} as any, next);
  assert.equal(lines.length, 1);
  assert.equal(lines[0], 'proxy check: req.ip=203.0.x.x socket=100.64.x.x xff_hops=1');
  assert.equal(nexts, 6, 'every request still continues down the chain');
});
