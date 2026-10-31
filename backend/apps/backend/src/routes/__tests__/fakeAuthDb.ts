/** In-memory learners + otp_challenges tables for the sign-in tests (mirrors the SQL used). */

export const ORG = '00000000-0000-4000-8000-0000000000aa';

export interface FakeLearner {
  id: string; organization_id: string; phone: string; whatsapp_id: string | null;
  name: string | null; email: string | null; created_at: number;
}
export interface FakeChallenge {
  id: string; phone: string; code_hash: string; expires_at: string; attempts: number;
  consumed_at: string | null; created_at: number;
}

export function makeAuthDb(seed: Partial<FakeLearner>[] = [], clock: () => number = Date.now) {
  let n = 100;
  const learners: FakeLearner[] = seed.map((l, i) => ({
    id: `00000000-0000-4000-8000-00000000f0${i}`, organization_id: ORG, phone: '', whatsapp_id: null,
    name: null, email: null, created_at: i, ...l,
  }));
  const challenges: FakeChallenge[] = [];

  return {
    learners, challenges,
    async raw(sql: string, p: any[] = []): Promise<{ rows: any[]; rowCount?: number }> {
      const has = (s: string) => sql.includes(s);
      if (has('FROM organizations')) return { rows: [{ id: ORG }] };

      if (has('AND (phone = $2 OR phone = $3 OR whatsapp_id = $3)')) {
        const found = learners.filter((l) => l.organization_id === p[0] && (l.phone === p[1] || l.phone === p[2] || l.whatsapp_id === p[2]));
        return { rows: found.sort((a, b) => a.created_at - b.created_at).slice(0, 1) };
      }
      if (has('WHERE organization_id = $1 AND phone = $2 LIMIT 1')) {
        return { rows: learners.filter((l) => l.organization_id === p[0] && l.phone === p[1]).slice(0, 1) };
      }
      if (has('INSERT INTO learners') && has('whatsapp_id')) { // phone identity: [org, digits, name, tz]
        if (learners.some((l) => l.organization_id === p[0] && l.phone === p[1])) return { rows: [] };
        const row: FakeLearner = { id: `00000000-0000-4000-8000-00000000a${n++}`, organization_id: p[0], phone: p[1], whatsapp_id: p[1], name: p[2], email: null, created_at: n };
        learners.push(row);
        return { rows: [row] };
      }
      if (has('INSERT INTO learners')) { // firebase identity: [org, address, name, email, tz]
        if (learners.some((l) => l.organization_id === p[0] && l.phone === p[1])) return { rows: [] };
        const row: FakeLearner = { id: `00000000-0000-4000-8000-00000000b${n++}`, organization_id: p[0], phone: p[1], whatsapp_id: null, name: p[2], email: p[3], created_at: n };
        learners.push(row);
        return { rows: [row] };
      }
      if (has('UPDATE learners SET email')) {
        const l = learners.find((x) => x.id === p[0] && x.email === null);
        if (l) l.email = p[1];
        return { rows: [] };
      }
      if (has('SELECT 1 FROM learners WHERE id = $1 AND organization_id = $2')) {
        return { rows: learners.some((l) => l.id === p[0] && l.organization_id === p[1]) ? [{}] : [] };
      }
      if (has('SELECT id, name, phone, language')) {
        const l = learners.find((x) => x.id === p[0])!;
        return { rows: [{ id: l.id, name: l.name, phone: l.phone, language: 'en' }] };
      }

      // otp_challenges
      if (has('UPDATE otp_challenges SET consumed_at = NOW() WHERE phone = $1')) {
        for (const c of challenges) if (c.phone === p[0] && !c.consumed_at) c.consumed_at = 'x';
        return { rows: [] };
      }
      if (has('INSERT INTO otp_challenges')) {
        challenges.push({ id: `c${n++}`, phone: p[0], code_hash: p[1], expires_at: p[2], attempts: 0, consumed_at: null, created_at: n });
        return { rows: [] };
      }
      if (has('FROM otp_challenges') && has('ORDER BY created_at DESC')) {
        const live = challenges.filter((c) => c.phone === p[0] && !c.consumed_at).sort((a, b) => b.created_at - a.created_at);
        return { rows: live.slice(0, 1) };
      }
      if (has('SET attempts = attempts + 1')) {
        const c = challenges.find((x) => x.id === p[0] && !x.consumed_at && x.attempts < p[1]);
        if (!c) return { rows: [] };
        c.attempts++;
        return { rows: [{ attempts: c.attempts }] };
      }
      if (has('UPDATE otp_challenges SET consumed_at = NOW() WHERE id = $1')) {
        const c = challenges.find((x) => x.id === p[0] && !x.consumed_at);
        if (!c) return { rows: [] };
        c.consumed_at = 'used';
        return { rows: [{ id: c.id }] };
      }
      return { rows: [] }; // /me extras: courses, events, timezone, profile
    },
    clock,
  };
}

/** A stand-in for firebase-admin's Auth. Tokens are plain JSON so tests can shape any claims. */
export function makeFirebase() {
  const calls = { verify: [] as { token: string; checkRevoked?: boolean }[], custom: [] as { uid: string; claims?: object }[] };
  return {
    calls,
    async verifyIdToken(token: string, checkRevoked?: boolean) {
      calls.verify.push({ token, checkRevoked });
      try {
        const claims = JSON.parse(token);
        if (!claims.uid) throw new Error('no uid');
        return claims;
      } catch {
        throw new Error('invalid or revoked');
      }
    },
    async createCustomToken(uid: string, claims?: object) {
      calls.custom.push({ uid, claims });
      return `custom-token-for:${uid}`;
    },
  };
}
