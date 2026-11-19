'use strict';

// "Connect phone" screen on the TV, which is also the first-run screen.
// Offline it asks for Wi-Fi first and reloads into the QR code once the TV
// is on a network; online it switches to a confirmation when a phone pairs.
// window.OttoApp is the TV app's bridge (tvapp/, ConnectActivity).
const shownAt = Date.now();
const app = window.OttoApp;
const $ = (id) => document.getElementById(id);

// The page is drawn at 1920x1080: fit it to the screen.
function fit() {
  const k = Math.min(window.innerWidth / 1920, window.innerHeight / 1080);
  document.documentElement.style.setProperty('--k', String(k));
}
fit();
window.addEventListener('resize', fit);

function tick() {
  const now = new Date();
  $('clock').textContent = String(now.getHours()).padStart(2, '0') + ':' + String(now.getMinutes()).padStart(2, '0');
}
tick();
setInterval(tick, 5000);

const buttons = Array.from(document.querySelectorAll('[data-action]'));
buttons.forEach((b) => {
  if (!app || typeof app[b.dataset.action] !== 'function') {
    b.hidden = true; // opened in a plain browser: nothing to call
    return;
  }
  b.addEventListener('click', () => app[b.dataset.action]());
});

// The TV's WebView doesn't move focus with arrow keys on its own.
document.addEventListener('keydown', (e) => {
  const visible = buttons.filter((b) => !b.hidden && !b.closest('[hidden]'));
  if (!visible.length) return;
  const i = visible.indexOf(document.activeElement);
  if (['ArrowDown', 'ArrowRight'].includes(e.key)) {
    visible[Math.min(i + 1, visible.length - 1)].focus();
    e.preventDefault();
  } else if (['ArrowUp', 'ArrowLeft'].includes(e.key)) {
    visible[Math.max(i - 1, 0)].focus();
    e.preventDefault();
  }
});

const first = document.querySelector('[data-focus]');
if (first && !first.hidden) first.focus();

function showStatus(s) {
  const audio = $('audio');
  if (s.audio) {
    audio.hidden = false;
    $('audio-label').textContent = s.audio === 'tv' ? audio.dataset.tv : audio.dataset.notebook;
  } else {
    audio.hidden = true;
  }
  $('phone').classList.toggle('dim', !(s.phones > 0));
  $('phone-label').hidden = !(s.phones > 0);
}

function showPIN(pin) {
  const box = $('pin');
  if (!box || !pin || box.getAttribute('aria-label') === pin) return;
  box.setAttribute('aria-label', pin);
  box.querySelectorAll('.digit').forEach((d, i) => { d.textContent = pin[i] || ''; });
}

async function poll() {
  try {
    const res = await fetch('/tv/status', { cache: 'no-store' });
    const s = await res.json();
    if (!$('qr') && s.url) {
      // Just got online: on to the QR code, step 2 of 2 if this was step 1.
      location.replace($('step') ? '/tv?setup=1' : '/tv');
      return;
    }
    showStatus(s);
    showPIN(s.pin);
    if ($('url') && s.url) $('url').textContent = 'http://' + s.url;
    if (s.pairedAt > shownAt) {
      $('connect').hidden = true;
      $('hint').hidden = true;
      if ($('step')) $('step').hidden = true;
      $('done').hidden = false;
      if (app && app.close) setTimeout(() => app.close(), 4000);
      return;
    }
  } catch {
    // The service may be restarting: keep trying.
  }
  setTimeout(poll, 2000);
}

poll();
