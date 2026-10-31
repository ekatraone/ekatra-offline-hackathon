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
} from '../services/syncService.js';

export interface SyncRouterDeps {
  db: SyncDb;
  generator: CourseGeneratorLike;
}

type SyncRequest = Request & { syncLearner?: SyncLearner };

function learnerAuth(req: SyncRequest, res: Response, next: NextFunction): void {
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
  try {
    const payload = jwt.verify(header.slice(7), secret, { algorithms: ['HS256'] }) as any;
    if (payload?.role !== 'learner' || !payload.learnerId || !payload.organizationId) {
      res.status(401).json({ success: false, error: 'Invalid token' });
      return;
    }
    req.syncLearner = { id: payload.learnerId, organizationId: payload.organizationId, phone: payload.phone };
    next();
  } catch {
    res.status(401).json({ success: false, error: 'Invalid or expired token' });
  }
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
  router.use(learnerAuth);

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
      req.setTimeout(290_000);
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
