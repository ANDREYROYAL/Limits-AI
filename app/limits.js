// Логика лимитов: окна сброса, подсчёт использования, блокировки.
// Чистые функции без DOM, чтобы их можно было тестировать в Node.

export const HOUR = 3600 * 1000;
const KEEP_EVENTS_MS = 60 * 24 * HOUR;

// Типы окон:
//  rolling — окно открывается первым запросом и длится N часов (как сессия Claude);
//  sliding — каждый запрос «сгорает» через N часов (скользящее окно);
//  daily / weekly / monthly — сброс в фиксированное время.
export const WINDOW_TYPES = ['rolling', 'sliding', 'daily', 'weekly', 'monthly'];

export const PRESETS = [
  { name: 'Claude — сессия', color: '#d97757', limit: null, window: { type: 'rolling', hours: 5 } },
  { name: 'Claude — неделя', color: '#c2603f', limit: null, window: { type: 'weekly', day: 1, time: '00:00' } },
  { name: 'ChatGPT', color: '#10a37f', limit: null, window: { type: 'sliding', hours: 3 } },
  { name: 'Gemini', color: '#4285f4', limit: null, window: { type: 'daily', time: '00:00' } },
  { name: 'Cursor', color: '#7c6cf0', limit: null, window: { type: 'monthly', date: 1, time: '00:00' } },
  { name: 'Perplexity', color: '#20808d', limit: null, window: { type: 'daily', time: '00:00' } },
  { name: 'Свой лимит', color: '#8a8f98', limit: null, window: { type: 'rolling', hours: 24 } },
];

function parseTime(time) {
  const [h, m] = (time || '00:00').split(':').map(Number);
  return [h || 0, m || 0];
}

function atTime(date, time) {
  const [h, m] = parseTime(time);
  const d = new Date(date);
  d.setHours(h, m, 0, 0);
  return d;
}

function monthBoundary(year, month, date, time) {
  const last = new Date(year, month + 1, 0).getDate();
  return atTime(new Date(year, month, Math.min(date, last)), time);
}

// Начало и конец текущего периода для фиксированных окон (локальное время).
export function periodBounds(window, now) {
  const n = new Date(now);
  if (window.type === 'daily') {
    let start = atTime(n, window.time);
    if (start > n) start.setDate(start.getDate() - 1);
    const end = new Date(start);
    end.setDate(end.getDate() + 1);
    return { start: start.getTime(), end: end.getTime() };
  }
  if (window.type === 'weekly') {
    let start = atTime(n, window.time);
    start.setDate(start.getDate() - ((start.getDay() - (window.day ?? 1) + 7) % 7));
    if (start > n) start.setDate(start.getDate() - 7);
    const end = new Date(start);
    end.setDate(end.getDate() + 7);
    return { start: start.getTime(), end: end.getTime() };
  }
  if (window.type === 'monthly') {
    const date = window.date ?? 1;
    let y = n.getFullYear();
    let m = n.getMonth();
    let start = monthBoundary(y, m, date, window.time);
    if (start > n) {
      m -= 1;
      if (m < 0) { m = 11; y -= 1; }
      start = monthBoundary(y, m, date, window.time);
    }
    const ny = m === 11 ? y + 1 : y;
    const nm = (m + 1) % 12;
    return { start: start.getTime(), end: monthBoundary(ny, nm, date, window.time).getTime() };
  }
  throw new Error(`periodBounds: неизвестный тип окна ${window.type}`);
}

function rollingActive(tracker, now) {
  const ws = tracker.windowStart;
  return ws != null && now < ws + tracker.window.hours * HOUR;
}

// Текущее состояние счётчика.
// resetAt — когда лимит восстановится (для sliding — когда освободится ближайший слот).
export function trackerState(tracker, now) {
  const { window, limit } = tracker;
  // События до ручного сброса не считаются.
  const events = (tracker.events || []).filter((t) => tracker.manualResetAt == null || t > tracker.manualResetAt);
  let used = 0;
  let resetAt = null;
  let windowStart = null;

  if (window.type === 'rolling') {
    if (rollingActive(tracker, now)) {
      windowStart = tracker.windowStart;
      used = events.filter((t) => t >= windowStart).length;
      resetAt = windowStart + window.hours * HOUR;
    }
  } else if (window.type === 'sliding') {
    const from = now - window.hours * HOUR;
    const inWindow = events.filter((t) => t > from);
    used = inWindow.length;
    if (used) resetAt = Math.min(...inWindow) + window.hours * HOUR;
  } else {
    const { start, end } = periodBounds(window, now);
    windowStart = start;
    used = events.filter((t) => t >= start).length;
    resetAt = end;
  }

  const blocked = tracker.blockedUntil != null && tracker.blockedUntil > now;
  const overLimit = limit != null && used >= limit;
  if (blocked) resetAt = tracker.blockedUntil;

  return {
    used,
    limit: limit ?? null,
    remaining: limit != null ? Math.max(0, limit - used) : null,
    exhausted: blocked || overLimit,
    resetAt,
    windowStart,
  };
}

// Отметить один запрос. Возвращает новый объект счётчика.
export function recordUse(tracker, now) {
  const next = { ...tracker, events: [...(tracker.events || []), now].filter((t) => t > now - KEEP_EVENTS_MS) };
  if (tracker.window.type === 'rolling' && !rollingActive(tracker, now)) next.windowStart = now;
  return next;
}

// Убрать последний запрос (на случай случайного нажатия).
export function undoUse(tracker) {
  const events = [...(tracker.events || [])];
  events.pop();
  const next = { ...tracker, events };
  if (tracker.window.type === 'rolling' && tracker.windowStart != null && !events.some((t) => t >= tracker.windowStart)) {
    next.windowStart = null;
  }
  return next;
}

// Отметить «лимит исчерпан». resetAt — время, которое показал сервис; без него берём расчётное.
export function markExhausted(tracker, now, resetAt = null) {
  let until = resetAt;
  if (until == null) {
    const s = trackerState(tracker, now);
    until = s.resetAt ?? now + (tracker.window.hours ?? 24) * HOUR;
  }
  const next = { ...tracker, blockedUntil: until };
  if (tracker.window.type === 'rolling' && !rollingActive(tracker, now)) next.windowStart = now;
  return next;
}

// Сбросить счётчик вручную: всё, что было до этого момента, не считается.
export function resetTracker(tracker, now) {
  return { ...tracker, blockedUntil: null, windowStart: null, manualResetAt: now };
}

export function formatDuration(ms) {
  if (ms <= 0) return 'сейчас';
  const totalMin = Math.ceil(ms / 60000);
  const d = Math.floor(totalMin / 1440);
  const h = Math.floor((totalMin % 1440) / 60);
  const m = totalMin % 60;
  if (d) return `${d} д ${h} ч`;
  if (h) return `${h} ч ${m} мин`;
  return `${m} мин`;
}

export function describeWindow(window) {
  const days = ['вс', 'пн', 'вт', 'ср', 'чт', 'пт', 'сб'];
  switch (window.type) {
    case 'rolling': return `окно ${window.hours} ч с первого запроса`;
    case 'sliding': return `скользящее окно ${window.hours} ч`;
    case 'daily': return `сброс каждый день в ${window.time}`;
    case 'weekly': return `сброс по ${days[window.day ?? 1]} в ${window.time}`;
    case 'monthly': return `сброс ${window.date}-го числа в ${window.time}`;
    default: return '';
  }
}
