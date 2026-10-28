import {
  HOUR, PRESETS, trackerState, recordUse, undoUse, markExhausted, resetTracker, formatDuration, describeWindow,
} from './limits.js';

const STORAGE_KEY = 'limits-ai:v1';
const $ = (sel) => document.querySelector(sel);

let trackers = load();
let editingId = null;
let exhaustingId = null;
let openMenuId = null;
const notifyTimers = new Map();

function load() {
  try {
    const data = JSON.parse(localStorage.getItem(STORAGE_KEY));
    return Array.isArray(data?.trackers) ? data.trackers : [];
  } catch {
    return [];
  }
}

function save() {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ version: 1, trackers }));
  } catch {
    // Хранилище недоступно (приватный режим) — работаем в памяти.
  }
}

function update(id, fn) {
  trackers = trackers.map((t) => (t.id === id ? fn(t) : t));
  save();
  render();
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

function clockTime(ts) {
  const d = new Date(ts);
  const sameDay = d.toDateString() === new Date().toDateString();
  const time = d.toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' });
  return sameDay ? time : `${d.toLocaleDateString('ru-RU', { day: 'numeric', month: 'short' })}, ${time}`;
}

function plural(n, one, few, many) {
  const m10 = n % 10;
  const m100 = n % 100;
  if (m10 === 1 && m100 !== 11) return one;
  if (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) return few;
  return many;
}

function cardHtml(t, now) {
  const s = trackerState(t, now);
  const usage = s.limit != null
    ? `<strong>${s.used}</strong> / ${s.limit}`
    : `<strong>${s.used}</strong> ${plural(s.used, 'запрос', 'запроса', 'запросов')}`;
  let reset = '';
  if (s.resetAt != null) {
    const label = t.window.type === 'sliding' && !s.exhausted ? 'Слот освободится' : 'Сброс';
    reset = `${label} через ${formatDuration(s.resetAt - now)}<br>${clockTime(s.resetAt)}`;
  } else if (t.window.type === 'rolling') {
    reset = 'Окно откроется с первым запросом';
  }
  const bar = s.limit != null
    ? `<div class="bar" role="progressbar" aria-valuemin="0" aria-valuemax="${s.limit}" aria-valuenow="${s.used}"><span style="width:${Math.min(100, (s.used / s.limit) * 100)}%"></span></div>`
    : '';
  const remaining = s.remaining != null && !s.exhausted ? ` · осталось ${s.remaining}` : '';

  return `
    <article class="card" style="--c:${escapeHtml(t.color)}" data-id="${t.id}">
      <div class="card-head">
        <div>
          <div class="card-name">${escapeHtml(t.name)}</div>
          <div class="muted">${describeWindow(t.window)}</div>
        </div>
        <span class="status ${s.exhausted ? 'bad' : 'ok'}">${s.exhausted ? 'Исчерпан' : 'Доступен'}${remaining}</span>
      </div>
      <div class="usage"><span>${usage}</span><span class="reset">${reset}</span></div>
      ${bar}
      <div class="card-actions">
        <button type="button" class="use" data-action="use">+1 запрос</button>
        ${s.exhausted
          ? '<button type="button" data-action="reset">Лимит вернулся</button>'
          : '<button type="button" data-action="exhausted">Лимит кончился</button>'}
        <details class="menu" ${openMenuId === t.id ? 'open' : ''}>
          <summary aria-label="Ещё">⋯</summary>
          <div class="menu-items">
            <button type="button" data-action="undo">Отменить последний запрос</button>
            <button type="button" data-action="reset">Сбросить счётчик</button>
            <button type="button" data-action="edit">Изменить</button>
            <button type="button" data-action="delete" class="danger">Удалить</button>
          </div>
        </details>
      </div>
    </article>`;
}

function render() {
  const now = Date.now();
  $('#list').innerHTML = trackers.map((t) => cardHtml(t, now)).join('');
  $('#empty').hidden = trackers.length > 0;
  scheduleNotifications(now);
}

// ---------- Уведомления о восстановлении лимита ----------

function notificationsEnabled() {
  return 'Notification' in window && Notification.permission === 'granted';
}

function refreshNotifyButton() {
  $('#notify-btn').hidden = !('Notification' in window) || Notification.permission !== 'default';
}

async function notify(title, body) {
  const reg = await navigator.serviceWorker?.getRegistration();
  if (reg) reg.showNotification(title, { body, icon: 'icons/icon-192.png', tag: title });
  else new Notification(title, { body });
}

// Таймеры работают, пока приложение открыто или висит в фоне.
function scheduleNotifications(now) {
  if (!notificationsEnabled()) return;
  const wanted = new Map();
  for (const t of trackers) {
    const s = trackerState(t, now);
    if (s.exhausted && s.resetAt != null) wanted.set(t.id, s.resetAt);
  }
  for (const [id, entry] of notifyTimers) {
    if (wanted.get(id) !== entry.at) {
      clearTimeout(entry.timer);
      notifyTimers.delete(id);
    }
  }
  for (const [id, at] of wanted) {
    if (notifyTimers.has(id) || at - now > 2 ** 31 - 1) continue;
    const timer = setTimeout(() => {
      notifyTimers.delete(id);
      const t = trackers.find((x) => x.id === id);
      if (t) notify(`${t.name}: лимит восстановлен`, 'Можно снова пользоваться.');
      render();
    }, Math.max(0, at - now));
    notifyTimers.set(id, { at, timer });
  }
}

$('#notify-btn').addEventListener('click', async () => {
  await Notification.requestPermission();
  refreshNotifyButton();
  render();
});

// ---------- Действия на карточках ----------

$('#list').addEventListener('toggle', (e) => {
  if (!(e.target instanceof HTMLDetailsElement)) return;
  const id = e.target.closest('.card').dataset.id;
  if (e.target.open) openMenuId = id;
  else if (openMenuId === id) openMenuId = null;
}, true);

document.addEventListener('click', (e) => {
  if (openMenuId && !e.target.closest('details.menu')) {
    openMenuId = null;
    document.querySelectorAll('details.menu[open]').forEach((d) => { d.open = false; });
  }
});

$('#list').addEventListener('click', (e) => {
  const btn = e.target.closest('button[data-action]');
  if (!btn) return;
  const id = btn.closest('.card').dataset.id;
  const now = Date.now();
  openMenuId = null;
  switch (btn.dataset.action) {
    case 'use': update(id, (t) => recordUse(t, now)); break;
    case 'undo': update(id, undoUse); break;
    case 'reset': update(id, (t) => resetTracker(t, now)); break;
    case 'exhausted': openExhausted(id); break;
    case 'edit': openEdit(id); break;
    case 'delete': {
      const t = trackers.find((x) => x.id === id);
      if (confirm(`Удалить «${t.name}»?`)) {
        trackers = trackers.filter((x) => x.id !== id);
        save();
        render();
      }
      break;
    }
  }
});

// ---------- Диалог «лимит исчерпан» ----------

function toLocalInput(ts) {
  const d = new Date(ts);
  d.setMinutes(d.getMinutes() - d.getTimezoneOffset());
  return d.toISOString().slice(0, 16);
}

function openExhausted(id) {
  exhaustingId = id;
  const t = trackers.find((x) => x.id === id);
  const now = Date.now();
  const guess = markExhausted(t, now).blockedUntil;
  const form = $('#exhausted-form');
  form.resetAt.value = toLocalInput(guess);
  form.resetAt.min = toLocalInput(now);
  $('#quick-hours').innerHTML = [1, 3, 5, 24]
    .map((h) => `<button type="button" data-hours="${h}">через ${h} ч</button>`).join('');
  $('#exhausted-dialog').showModal();
}

$('#quick-hours').addEventListener('click', (e) => {
  const h = Number(e.target.dataset.hours);
  if (h) $('#exhausted-form').resetAt.value = toLocalInput(Date.now() + h * HOUR);
});

$('#exhausted-form').addEventListener('submit', () => {
  const at = new Date($('#exhausted-form').resetAt.value).getTime();
  if (Number.isFinite(at)) update(exhaustingId, (t) => markExhausted(t, Date.now(), at));
});

// ---------- Диалог добавления / редактирования ----------

const editForm = $('#edit-form');

function syncWindowFields() {
  const type = editForm.type.value;
  editForm.querySelectorAll('[data-for]').forEach((el) => {
    el.hidden = !el.dataset.for.split(' ').includes(type);
  });
}

function fillForm(t) {
  editForm.name.value = t.name;
  editForm.limit.value = t.limit ?? '';
  editForm.color.value = t.color;
  editForm.type.value = t.window.type;
  editForm.hours.value = t.window.hours ?? 5;
  editForm.day.value = String(t.window.day ?? 1);
  editForm.date.value = t.window.date ?? 1;
  editForm.time.value = t.window.time ?? '00:00';
  syncWindowFields();
}

function openEdit(id = null) {
  editingId = id;
  const t = id ? trackers.find((x) => x.id === id) : PRESETS[0];
  $('#edit-title').textContent = id ? 'Изменить лимит' : 'Новый лимит';
  $('#presets').hidden = !!id;
  fillForm(t);
  $('#edit-dialog').showModal();
}

$('#presets').innerHTML = PRESETS
  .map((p, i) => `<button type="button" style="--c:${p.color}" data-preset="${i}">${escapeHtml(p.name)}</button>`).join('');
$('#presets').addEventListener('click', (e) => {
  const i = e.target.dataset.preset;
  if (i != null) fillForm(PRESETS[i]);
});

editForm.type.addEventListener('change', syncWindowFields);

editForm.addEventListener('submit', () => {
  const f = editForm;
  const type = f.type.value;
  const win = { type };
  if (type === 'rolling' || type === 'sliding') win.hours = Number(f.hours.value) || 5;
  if (type === 'weekly') win.day = Number(f.day.value);
  if (type === 'monthly') win.date = Math.min(31, Math.max(1, Number(f.date.value) || 1));
  if (type === 'daily' || type === 'weekly' || type === 'monthly') win.time = f.time.value || '00:00';
  const fields = {
    name: f.name.value.trim() || 'Без названия',
    limit: f.limit.value ? Number(f.limit.value) : null,
    color: f.color.value,
    window: win,
  };
  if (editingId) {
    // Смена типа окна делает старое окно бессмысленным — начинаем заново.
    update(editingId, (t) => (t.window.type === type ? { ...t, ...fields } : { ...t, ...fields, windowStart: null, blockedUntil: null }));
  } else {
    trackers = [...trackers, { id: crypto.randomUUID(), events: [], windowStart: null, blockedUntil: null, ...fields }];
    save();
    render();
  }
});

document.querySelectorAll('[data-close]').forEach((b) => b.addEventListener('click', () => b.closest('dialog').close()));
$('#add-btn').addEventListener('click', () => openEdit());
$('#add-btn-empty').addEventListener('click', () => openEdit());

// ---------- Экспорт / импорт ----------

$('#export-btn').addEventListener('click', () => {
  const blob = new Blob([JSON.stringify({ version: 1, trackers }, null, 2)], { type: 'application/json' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = `limits-ai-${new Date().toISOString().slice(0, 10)}.json`;
  a.click();
  URL.revokeObjectURL(a.href);
});

$('#import-input').addEventListener('change', async (e) => {
  const file = e.target.files[0];
  e.target.value = '';
  if (!file) return;
  try {
    const data = JSON.parse(await file.text());
    if (!Array.isArray(data?.trackers)) throw new Error('нет списка trackers');
    if (!confirm(`Заменить текущие лимиты (${trackers.length}) на импортированные (${data.trackers.length})?`)) return;
    trackers = data.trackers;
    save();
    render();
  } catch (err) {
    alert(`Не удалось импортировать файл: ${err.message}`);
  }
});

// ---------- Запуск ----------

window.addEventListener('storage', (e) => {
  if (e.key === STORAGE_KEY) { trackers = load(); render(); }
});
document.addEventListener('visibilitychange', () => { if (!document.hidden) render(); });
setInterval(() => { if (!document.hidden) render(); }, 30 * 1000);

if ('serviceWorker' in navigator) navigator.serviceWorker.register('sw.js').catch(() => {});
refreshNotifyButton();
render();
