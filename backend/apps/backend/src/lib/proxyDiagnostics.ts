/**
 * One-time log line (for the first request that came through the edge proxy) to confirm the proxy chain in front of the deployed service is one hop
 * (so `trust proxy` = 1 makes req.ip the real client). Addresses are truncated to the first two
 * octets / hextets, and only the number of X-Forwarded-For entries is reported.
 */

import type { Request, Response, NextFunction } from 'express';
import { logger } from './logger.js';

export function truncateIp(ip: string | undefined): string {
  if (!ip) return 'unknown';
  if (ip === '::1') return 'loopback';
  const v4 = ip.replace(/^::ffff:/i, '');
  if (/^\d{1,3}(\.\d{1,3}){3}$/.test(v4)) return `${v4.split('.').slice(0, 2).join('.')}.x.x`;
  if (ip.includes(':')) return `${ip.split(':').filter(Boolean).slice(0, 2).join(':')}::x`;
  return 'unknown';
}

export function describeProxyChain(req: Pick<Request, 'ip' | 'headers' | 'socket'>): string {
  const xff = req.headers['x-forwarded-for'];
  const hops = typeof xff === 'string' && xff.trim() ? xff.split(',').length : 0;
  return `proxy check: req.ip=${truncateIp(req.ip)} socket=${truncateIp(req.socket?.remoteAddress)} xff_hops=${hops}`;
}

export function logProxyChainOnce(log: (msg: string) => void = (m) => logger.info(m)) {
  let done = false;
  return (req: Request, _res: Response, next: NextFunction) => {
    // Only a request carrying X-Forwarded-For has been through the public edge; the platform's
    // internal health checks arrive without one and would tell us nothing about the real chain.
    if (!done && req.headers['x-forwarded-for']) {
      done = true;
      log(describeProxyChain(req));
    }
    next();
  };
}
