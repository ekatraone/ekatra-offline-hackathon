/**
 * Sync routes — Maya offline app ↔ backend. Learner JWT auth (not the admin JWT).
 *
 * GET  /api/sync/me
 * POST /api/sync/lessons/request
 * GET  /api/sync/courses/:courseId/bundle
 * POST /api/sync/events
 *
 * Contract: .claude/hackathon/CONTRACT.md
 *
 * The learner token is verified inline with JWT_SECRET read at request time:
 * middleware/learnerAuth.ts exits the process at import when the secret is unset.
 */

import { Router, Request, Response, NextFunction } from 'express';
import jwt from 'jsonwebtoken';
import rateLimit from 'express-rate-limit';
import { logger } from '../lib/logger.js';
import {
  SyncDb,
  SyncLearner,
  CourseGeneratorLike,
  SyncError,
  requestLesson,
  buildBundle,
  applyEvents,
  getMe,
  isUuid,
} from '../services/syncService.js';

export interface SyncRouterDeps {
  db: SyncDb;
  generator: CourseGeneratorLike;
}

type SyncRequest = Request & { syncLearner?: SyncLearner };

/**
 * Verifies the learner JWT and that the learner still exists in the token's organization,
 * so a token for a deleted learner (or a forged id) stops working immediately.
 */
function learnerAuth(db: SyncDb) {
  return async (req: SyncRequest, res: Response, next: NextFunction): Promise<void> => {
    const header = req.headers.authorization;
    if (!header || !header.startsWith('Bearer ')) {
      res.status(401).json({ success: false, error: 'Missing or invalid Authorization header' });
      return;
    }
    const secret = process.env.JWT_SECRET;
    if (!secret) {
      res.status(500).json({ success: false, error: 'Server auth is not configured' });
      return;
    }
    let payload: any;
    try {
      payload = jwt.verify(header.slice(7), secret, { algorithms: ['HS256'] });
    } catch {
      res.status(401).json({ success: false, error: 'Invalid or expired token' });
      return;
    }
    // Tokens must expire, and carry well-formed ids (they are used as SQL uuid parameters).
    if (
      payload?.role !== 'learner' ||
      typeof payload.exp !== 'number' ||
      !isUuid(payload.learnerId) ||
      !isUuid(payload.organizationId)
    ) {
      res.status(401).json({ success: false, error: 'Invalid token' });
      return;
    }
    try {
      const found = await db.raw(`SELECT 1 FROM learners WHERE id = $1 AND organization_id = $2`, [
        payload.learnerId,
        payload.organizationId,
      ]);
      if (!found.rows.length) {
        res.status(401).json({ success: false, error: 'Unknown learner' });
        return;
      }
    } catch (err) {
      logger.error('sync auth lookup failed', err);
      res.status(500).json({ success: false, error: 'Internal error' });
      return;
    }
    req.syncLearner = { id: payload.learnerId, organizationId: payload.organizationId, phone: payload.phone };
    next();
  };
}

const perLearner = (max: number, windowMs: number) =>
  rateLimit({
    windowMs,
    max,
    standardHeaders: true,
    legacyHeaders: false,
    keyGenerator: (req) => (req as SyncRequest).syncLearner?.id ?? 'anonymous',
    message: { success: false, error: 'Rate limit exceeded. Please try again later.' },
  });

function fail(res: Response, err: unknown, what: string): void {
  if (err instanceof SyncError) {
    res.status(err.status).json({ success: false, error: err.message });
    return;
  }
  logger.error(`sync ${what} failed`, err);
  res.status(500).json({ success: false, error: 'Internal error' });
}

export function createSyncRouter({ db, generator }: SyncRouterDeps): Router {
  const router = Router();

  // Public, registered before the auth middleware: Twilio/WhatsApp fetch these by URL. The
  // unguessable UUID is the capability; anything that isn't a UUID is a plain 404. It is exempt
  // from the global API limiter in index.ts and has its own per-IP limit instead.
  const mediaLimiter = rateLimit({
    windowMs: 60_000,
    max: 120,
    standardHeaders: true,
    legacyHeaders: false,
    message: { success: false, error: 'Rate limit exceeded. Please try again later.' },
  });
  router.get('/media/:id', mediaLimiter, async (req: Request, res: Response) => {
    try {
      if (!isUuid(req.params.id)) {
        res.status(404).json({ success: false, error: 'Not found' });
        return;
      }
      const found = await db.raw(`SELECT mime, bytes FROM lesson_media WHERE id = $1`, [req.params.id]);
      const row = found.rows[0];
      if (!row) {
        res.status(404).json({ success: false, error: 'Not found' });
        return;
      }
      // Media rows are immutable, so the id itself is a perfect strong validator.
      const etag = `"${req.params.id.toLowerCase()}"`;
      res.set({ ETag: etag, 'Cache-Control': 'public, max-age=31536000, immutable' });
      const ifNoneMatch = req.headers['if-none-match'];
      if (ifNoneMatch && ifNoneMatch.split(',').some((t) => t.trim() === etag || t.trim() === '*')) {
        res.status(304).end();
        return;
      }
      res.set({ 'Content-Type': row.mime || 'image/jpeg', 'Content-Length': String(row.bytes.length) });
      res.send(row.bytes);
    } catch (err) {
      fail(res, err, 'media');
    }
  });

  router.use(learnerAuth(db));

  router.get('/me', perLearner(60, 60_000), async (req: SyncRequest, res: Response) => {
    try {
      res.json({ success: true, data: await getMe(db, req.syncLearner!) });
    } catch (err) {
      fail(res, err, 'me');
    }
  });

  router.post('/lessons/request', perLearner(20, 60_000), async (req: SyncRequest, res: Response) => {
    try {
      // Muse can take up to 240 s; keep the socket open past node's default.
      req.setTimeout(330_000); // above the client's 300 s read timeout
      const data = await requestLesson(db, generator, req.syncLearner!, req.body ?? {});
      res.json({ success: true, data });
    } catch (err) {
      fail(res, err, 'lessons/request');
    }
  });

  router.get('/courses/:courseId/bundle', perLearner(60, 60_000), async (req: SyncRequest, res: Response) => {
    try {
      res.json({ success: true, data: await buildBundle(db, req.syncLearner!, req.params.courseId) });
    } catch (err) {
      fail(res, err, 'bundle');
    }
  });

  router.post('/events', perLearner(120, 60_000), async (req: SyncRequest, res: Response) => {
    try {
      const data = await applyEvents(db, req.syncLearner!, req.body?.events);
      res.json({ success: true, data });
    } catch (err) {
      fail(res, err, 'events');
    }
  });

  return router;
}

/** Production wiring: the real database pool and the course generator, loaded lazily. */
export function createDefaultSyncRouter(): Router {
  const lazy = {
    raw: async (sql: string, params?: any[]) => (await import('@ekatra/database')).db.raw(sql, params),
  };
  const generator: CourseGeneratorLike = {
    generate: async (params) => (await import('../services/courseGenerator.js')).courseGenerator.generate(params),
  };
  return createSyncRouter({ db: lazy as SyncDb, generator });
}
