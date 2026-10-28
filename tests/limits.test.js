import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  HOUR, periodBounds, trackerState, recordUse, undoUse, markExhausted, resetTracker, formatDuration,
} from '../app/limits.js';

const at = (s) => new Date(s).getTime();

test('rolling: окно открывается первым запросом и закрывается через N часов', () => {
  let t = { window: { type: 'rolling', hours: 5 }, limit: 3, events: [] };
  const t0 = at('2026-10-28T10:00:00');
  assert.equal(trackerState(t, t0).resetAt, null);

  t = recordUse(t, t0);
  t = recordUse(t, t0 + HOUR);
  let s = trackerState(t, t0 + 2 * HOUR);
  assert.equal(s.used, 2);
  assert.equal(s.remaining, 1);
  assert.equal(s.resetAt, t0 + 5 * HOUR);

  s = trackerState(t, t0 + 5 * HOUR);
  assert.equal(s.used, 0);
  assert.equal(s.resetAt, null);

  t = recordUse(t, t0 + 6 * HOUR);
  assert.equal(t.windowStart, t0 + 6 * HOUR);
  assert.equal(trackerState(t, t0 + 6 * HOUR).used, 1);
});

test('rolling: исчерпан при достижении лимита', () => {
  let t = { window: { type: 'rolling', hours: 5 }, limit: 2, events: [] };
  const t0 = at('2026-10-28T10:00:00');
  t = recordUse(recordUse(t, t0), t0 + 1000);
  assert.equal(trackerState(t, t0 + 2000).exhausted, true);
});

test('sliding: каждый запрос сгорает через N часов', () => {
  let t = { window: { type: 'sliding', hours: 3 }, limit: 2, events: [] };
  const t0 = at('2026-10-28T10:00:00');
  t = recordUse(t, t0);
  t = recordUse(t, t0 + HOUR);
  let s = trackerState(t, t0 + 2 * HOUR);
  assert.equal(s.exhausted, true);
  assert.equal(s.resetAt, t0 + 3 * HOUR);

  s = trackerState(t, t0 + 3 * HOUR + 1);
  assert.equal(s.used, 1);
  assert.equal(s.exhausted, false);
});

test('daily: границы периода', () => {
  const w = { type: 'daily', time: '03:00' };
  assert.deepEqual(periodBounds(w, at('2026-10-28T10:00:00')), {
    start: at('2026-10-28T03:00:00'), end: at('2026-10-29T03:00:00'),
  });
  assert.deepEqual(periodBounds(w, at('2026-10-28T02:00:00')), {
    start: at('2026-10-27T03:00:00'), end: at('2026-10-28T03:00:00'),
  });
});

test('weekly: границы периода', () => {
  const w = { type: 'weekly', day: 1, time: '00:00' }; // понедельник
  // 2026-10-28 — среда
  assert.deepEqual(periodBounds(w, at('2026-10-28T10:00:00')), {
    start: at('2026-10-26T00:00:00'), end: at('2026-11-02T00:00:00'),
  });
});

test('monthly: 31-е число в коротком месяце и переход через год', () => {
  const w = { type: 'monthly', date: 31, time: '00:00' };
  assert.deepEqual(periodBounds(w, at('2026-11-15T10:00:00')), {
    start: at('2026-10-31T00:00:00'), end: at('2026-11-30T00:00:00'),
  });
  const w1 = { type: 'monthly', date: 1, time: '00:00' };
  assert.deepEqual(periodBounds(w1, at('2026-12-20T10:00:00')), {
    start: at('2026-12-01T00:00:00'), end: at('2027-01-01T00:00:00'),
  });
});

test('markExhausted: время от сервиса имеет приоритет', () => {
  const t0 = at('2026-10-28T10:00:00');
  let t = { window: { type: 'daily', time: '00:00' }, limit: null, events: [] };
  t = markExhausted(t, t0, t0 + 2 * HOUR);
  let s = trackerState(t, t0);
  assert.equal(s.exhausted, true);
  assert.equal(s.resetAt, t0 + 2 * HOUR);
  assert.equal(trackerState(t, t0 + 2 * HOUR).exhausted, false);

  t = markExhausted({ ...t, blockedUntil: null }, t0);
  assert.equal(trackerState(t, t0).resetAt, at('2026-10-29T00:00:00'));
});

test('markExhausted для rolling без активного окна открывает окно', () => {
  const t0 = at('2026-10-28T10:00:00');
  const t = markExhausted({ window: { type: 'rolling', hours: 5 }, limit: null, events: [] }, t0);
  assert.equal(t.blockedUntil, t0 + 5 * HOUR);
});

test('resetTracker обнуляет счётчик, undoUse убирает запрос', () => {
  const t0 = at('2026-10-28T10:00:00');
  let t = { window: { type: 'daily', time: '00:00' }, limit: 5, events: [] };
  t = recordUse(recordUse(t, t0), t0 + 1);
  assert.equal(trackerState(t, t0 + 2).used, 2);
  t = undoUse(t);
  assert.equal(trackerState(t, t0 + 2).used, 1);
  t = resetTracker(t, t0 + 3);
  assert.equal(trackerState(t, t0 + 4).used, 0);
  t = recordUse(t, t0 + 5);
  assert.equal(trackerState(t, t0 + 6).used, 1);
});

test('formatDuration', () => {
  assert.equal(formatDuration(0), 'сейчас');
  assert.equal(formatDuration(90 * 1000), '2 мин');
  assert.equal(formatDuration(HOUR + 30 * 60000), '1 ч 30 мин');
  assert.equal(formatDuration(26 * HOUR), '1 д 2 ч');
});
