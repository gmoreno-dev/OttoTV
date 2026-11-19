'use strict';

// "Connect phone" screen on the TV, which is also the first-run screen.
// Offline it asks for Wi-Fi first and reloads into the QR code once the TV
// is on a network; online it switches to a confirmation when a phone pairs.
// window.OttoApp is the TV app's bridge (tvapp/, ConnectActivity).
const shownAt = Date.now();
const app = window.OttoApp;
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
  const visible = buttons.filter((b) => !b.hidden);
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

async function poll() {
  try {
    const res = await fetch('/tv/status', { cache: 'no-store' });
    const s = await res.json();
    if (!document.getElementById('qr') && s.url) {
      location.reload(); // just got online: show the QR code
      return;
    }
    const pin = document.getElementById('pin');
    if (pin && s.pin) pin.textContent = s.pin;
    const url = document.getElementById('url');
    if (url && s.url) url.textContent = s.url;
    if (s.pairedAt > shownAt) {
      document.getElementById('connect').hidden = true;
      document.getElementById('done').hidden = false;
      if (app && app.close) setTimeout(() => app.close(), 4000);
      return;
    }
  } catch {
    // The service may be restarting: keep trying.
  }
  setTimeout(poll, 2000);
}

poll();
