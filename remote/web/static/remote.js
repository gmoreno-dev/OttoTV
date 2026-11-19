'use strict';

// Phone remote: pairs with the TV, then sends key, text and pointer commands
// over a WebSocket (see server/control.go for the message format).

const TEXTS = {
  en: {
    pairTitle: 'Pair with the TV',
    pairHint: 'On the TV, open Connect phone and type the 6-digit code shown there.',
    pinLabel: '6-digit code',
    pairButton: 'Pair',
    pairing: 'Pairing…',
    wrongPin: 'That code doesn’t match. Check the TV and try again.',
    pairClosed: 'Open Connect phone on the TV, then type the code.',
    pairFailed: 'Can’t reach the TV. Check that the phone is on the same Wi-Fi.',
    reconnecting: 'Reconnecting…',
    noInput: 'The TV can’t receive commands right now',
    demo: 'Preview',
    padHint: 'Drag to move. Tap to click.\nTwo fingers to scroll, tap with two to go back.',
    typeHint: 'What you type shows up on the TV.',
    typeLabel: 'Text for the TV',
    erase: 'Erase', space: 'Space', enter: 'Enter',
    modes: 'Remote modes',
    linkOn: 'Status: connected', linkOff: 'Status: reconnecting', linkDown: 'Status: not receiving commands',
    tabButtons: 'Remote', tabTouchpad: 'Touchpad', tabKeyboard: 'Keyboard',
    skipped: 'Some characters can’t be typed on the TV and were skipped.',
    power: 'Sleep', dpad: 'Directional pad', up: 'Up', down: 'Down', left: 'Left', right: 'Right',
    back: 'Back', home: 'Home', menu: 'Menu', volume: 'Volume',
    voldown: 'Volume down', volup: 'Volume up', mute: 'Mute', play: 'Play or pause',
    laptopOff: 'Laptop screen on. Tap to turn it off.', laptopOn: 'Laptop screen off. Tap to turn it on.',
    laptopIsOff: 'Laptop screen off', laptopIsOn: 'Laptop screen on',
    screenOn: 'Screen on', screenOff: 'Screen off',
    sleeping: 'Putting the TV to sleep…',
  },
  pt: {
    pairTitle: 'Parear com a TV',
    pairHint: 'Na TV, abra Conectar celular e digite o código de 6 dígitos que aparece lá.',
    pinLabel: 'Código de 6 dígitos',
    pairButton: 'Parear',
    pairing: 'Pareando…',
    wrongPin: 'Esse código não confere. Confira na TV e tente de novo.',
    pairClosed: 'Abra Conectar celular na TV e digite o código.',
    pairFailed: 'Não deu para falar com a TV. Confira se o celular está no mesmo Wi-Fi.',
    reconnecting: 'Reconectando…',
    noInput: 'A TV não está recebendo comandos agora',
    demo: 'Prévia',
    padHint: 'Arraste para mover. Toque para clicar.\nDois dedos para rolar, toque com dois para voltar.',
    typeHint: 'O que você digitar aparece na TV.',
    typeLabel: 'Texto para a TV',
    erase: 'Apagar', space: 'Espaço', enter: 'Enter',
    modes: 'Modos do controle',
    linkOn: 'Status: conectado', linkOff: 'Status: reconectando', linkDown: 'Status: sem receber comandos',
    tabButtons: 'Controle', tabTouchpad: 'Touchpad', tabKeyboard: 'Teclado',
    skipped: 'Alguns caracteres não dá para digitar na TV e foram pulados.',
    power: 'Suspender', dpad: 'Direcional', up: 'Cima', down: 'Baixo', left: 'Esquerda', right: 'Direita',
    back: 'Voltar', home: 'Início', menu: 'Menu', volume: 'Volume',
    voldown: 'Diminuir volume', volup: 'Aumentar volume', mute: 'Silenciar', play: 'Reproduzir ou pausar',
    laptopOff: 'Tela do notebook acesa. Toque para apagar.', laptopOn: 'Tela do notebook apagada. Toque para acender.',
    laptopIsOff: 'Tela do notebook apagada', laptopIsOn: 'Tela do notebook acesa',
    screenOn: 'Tela acesa', screenOff: 'Tela apagada',
    sleeping: 'Suspendendo a TV…',
  },
};
const LANG = (navigator.language || 'en').toLowerCase().startsWith('pt') ? 'pt' : 'en';
const t = (key) => TEXTS[LANG][key] ?? TEXTS.en[key] ?? key;

// A preview build (no TV behind it) sets window.OTTO_DEMO.
const DEMO = window.OTTO_DEMO === true || new URLSearchParams(location.search).has('demo');
const TOKEN_KEY = 'otto.token';

const $ = (sel) => document.querySelector(sel);
const $$ = (sel) => document.querySelectorAll(sel);

// --- Token ------------------------------------------------------------------------

function loadToken() {
  try { return localStorage.getItem(TOKEN_KEY); } catch { return null; }
}
function saveToken(value) {
  try {
    if (value) localStorage.setItem(TOKEN_KEY, value);
    else localStorage.removeItem(TOKEN_KEY);
  } catch { /* private mode: the token lives for this visit only */ }
  token = value;
}
let token = loadToken();
// The token was just handed over (QR code or typed code): the TV says
// "Phone connected" once, when this phone is accepted.
let fresh = false;

// The QR code carries the token in the URL fragment (#t=...), which never
// reaches the server. Keep it and clean the address bar.
function takeTokenFromURL() {
  const m = location.hash.match(/[#&]t=([0-9a-f]{64})/);
  if (m) {
    saveToken(m[1]);
    fresh = true;
    history.replaceState(null, '', location.pathname);
  }
}

// --- Texts ------------------------------------------------------------------------

function applyTexts() {
  document.documentElement.lang = LANG === 'pt' ? 'pt-BR' : 'en';
  $$('[data-t]').forEach((el) => { el.textContent = t(el.dataset.t); });
  $$('[data-t-label]').forEach((el) => el.setAttribute('aria-label', t(el.dataset.tLabel)));
}

// --- Connection -------------------------------------------------------------------

let ws = null;
let ready = false;
let retries = 0;
let retryTimer = null;

// on: connected; off: reconnecting; noinput: connected, but the TV has no
// virtual keyboard or mouse (commands go nowhere); demo: the preview.
function setStatus(state) {
  const dot = $('#status-dot');
  dot.classList.toggle('warn', state === 'off');
  dot.classList.toggle('down', state === 'noinput');
  $('#status-text').textContent =
    { off: t('linkOff'), noinput: t('linkDown') }[state] || t('linkOn');
  $('#reconnecting').hidden = state !== 'off';
  $('#down').hidden = state !== 'noinput';
  $('#body').classList.toggle('dim', state === 'noinput');
  if (state === 'demo') toast(t('demo'));
}

// --- Toast ------------------------------------------------------------------------

let toastTimer = null;

function toast(text) {
  $('#toast-text').textContent = text;
  $('#toast').classList.add('on');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => $('#toast').classList.remove('on'), 2500);
}

// --- Laptop screen -------------------------------------------------------------------

// The TV says "on" or "off" when it runs on a laptop, "" otherwise.
let laptop = '';

const SCREEN = 'M4 5h16v11H4z M2 19.5h20';

function setLaptop(state, announce) {
  laptop = state || '';
  const b = $('#laptop-btn');
  b.hidden = !laptop;
  if (!laptop) return;
  const off = laptop === 'off';
  b.setAttribute('aria-pressed', String(off));
  b.setAttribute('aria-label', t(off ? 'laptopOn' : 'laptopOff'));
  $('#laptop-text').textContent = t(off ? 'screenOff' : 'screenOn');
  $('#laptop-icon').setAttribute('d', off ? SCREEN + ' M3 3l18 18' : SCREEN);
  if (announce) toast(t(off ? 'laptopIsOff' : 'laptopIsOn'));
}

function connect() {
  if (DEMO || !token) return;
  clearTimeout(retryTimer);
  if (ws && ws.readyState <= 1) return;
  const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
  ws = new WebSocket(`${scheme}://${location.host}/ws`);
  ws.onopen = () => ws.send(JSON.stringify({ type: 'auth', token, fresh }));
  ws.onmessage = (e) => {
    let m;
    try { m = JSON.parse(e.data); } catch { return; }
    if (m.type === 'ready') {
      ready = true;
      fresh = false;
      retries = 0;
      $('#tv-name').textContent = m.name || 'TV';
      setStatus(m.input ? 'on' : 'noinput');
      setLaptop(m.laptop);
    } else if (m.type === 'laptop') {
      setLaptop(m.laptop, true);
    } else if (m.type === 'error' && m.error === 'unauthorized') {
      saveToken(null); // the TV got a new token: pair again
      showPair();
    } else if (m.type === 'error' && m.error === 'no_input') {
      setStatus('noinput');
    } else if (m.type === 'typed' && m.skipped > 0) {
      $('#type-note').hidden = false;
    }
  };
  ws.onclose = () => {
    ready = false;
    if (!token) return;
    setStatus('off');
    retryTimer = setTimeout(connect, Math.min(5000, 400 * 2 ** retries++));
  };
}

function send(msg) {
  if (DEMO) {
    showDemo(msg);
    return;
  }
  if (!ws || !ready || ws.readyState !== 1) return;
  ws.send(JSON.stringify(msg));
}

// The preview has no TV behind it: show what would have been sent.
function showDemo(msg) {
  if (msg.type === 'laptop') {
    setLaptop(laptop === 'off' ? 'on' : 'off', true);
    return;
  }
  const what = {
    key: () => (msg.key === 'ok' ? 'OK' : t(msg.key === 'backspace' ? 'erase' : msg.key)),
    text: () => `“${msg.text}”`,
    move: () => t('tabTouchpad'),
    click: () => t('tabTouchpad'),
    scroll: () => t('tabTouchpad'),
  }[msg.type];
  if (!what || msg.action === 'up') return;
  toast(`${t('demo')}: ${what()}`);
}

setInterval(() => send({ type: 'ping' }), 20000);

document.addEventListener('visibilitychange', () => {
  if (document.hidden) releaseAll();
  else connect();
});

// --- Buttons -----------------------------------------------------------------------

const held = new Set();

function buzz() {
  if (navigator.vibrate) navigator.vibrate(8);
}

function releaseAll() {
  held.forEach((key) => send({ type: 'key', key, action: 'up' }));
  held.clear();
  $$('.pressed').forEach((el) => el.classList.remove('pressed'));
}

// Hold keys (arrows, OK, volume) go down on touch and up on release, so
// holding repeats on the TV. The others fire once, on release.
function bindKey(el) {
  const key = el.dataset.key;
  const hold = el.hasAttribute('data-hold');
  let down = false;

  el.addEventListener('pointerdown', (e) => {
    e.preventDefault();
    el.setPointerCapture(e.pointerId);
    el.classList.add('pressed');
    down = true;
    buzz();
    if (hold) {
      held.add(key);
      send({ type: 'key', key, action: 'down' });
    }
  });
  const end = (e) => {
    if (!down) return;
    down = false;
    el.classList.remove('pressed');
    if (hold) {
      held.delete(key);
      send({ type: 'key', key, action: 'up' });
    } else if (e.type === 'pointerup') {
      send({ type: 'key', key });
      if (key === 'power') toast(t('sleeping'));
    }
  };
  el.addEventListener('pointerup', end);
  el.addEventListener('pointercancel', end);
  el.addEventListener('lostpointercapture', end);
  // Keyboard or screen reader activation arrives as a click with no pointer.
  el.addEventListener('click', (e) => {
    if (e.detail === 0) send({ type: 'key', key });
  });
}

// --- Touchpad -------------------------------------------------------------------------

function bindTouchpad(pad) {
  const pointers = new Map();
  const dot = $('#touch');
  // Where the finger is, as in the design: a ring under it.
  const showTouch = (e) => {
    const r = pad.getBoundingClientRect();
    dot.style.left = `${e.clientX - r.left}px`;
    dot.style.top = `${e.clientY - r.top}px`;
  };
  let start = 0;
  let travel = 0;
  let maxFingers = 0;
  let moveX = 0;
  let moveY = 0;
  let scrollY = 0;
  let frame = 0;

  const flush = () => {
    frame = 0;
    if (moveX || moveY) send({ type: 'move', dx: moveX, dy: moveY });
    moveX = moveY = 0;
  };

  pad.addEventListener('pointerdown', (e) => {
    e.preventDefault();
    pad.setPointerCapture(e.pointerId);
    if (pointers.size === 0) {
      start = performance.now();
      travel = 0;
      maxFingers = 0;
      scrollY = 0;
    }
    pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
    maxFingers = Math.max(maxFingers, pointers.size);
    pad.classList.add('active');
    showTouch(e);
  });

  pad.addEventListener('pointermove', (e) => {
    const p = pointers.get(e.pointerId);
    if (!p) return;
    const dx = e.clientX - p.x;
    const dy = e.clientY - p.y;
    p.x = e.clientX;
    p.y = e.clientY;
    showTouch(e);
    travel += Math.abs(dx) + Math.abs(dy);
    if (pointers.size === 1) {
      // Faster swipes travel further, like a laptop touchpad.
      const gain = 1.6 + Math.min(2.4, Math.hypot(dx, dy) / 12);
      moveX += dx * gain;
      moveY += dy * gain;
      if (!frame) frame = requestAnimationFrame(flush);
    } else if (pointers.size === 2) {
      scrollY += dy / 2; // both fingers report the same motion
      const steps = Math.trunc(scrollY / 18);
      if (steps) {
        scrollY -= steps * 18;
        send({ type: 'scroll', dy: steps });
      }
    }
  });

  const end = (e) => {
    if (!pointers.delete(e.pointerId) || pointers.size > 0) return;
    pad.classList.remove('active');
    const quick = performance.now() - start < 250 && travel < 12;
    if (quick && maxFingers === 1) {
      buzz();
      send({ type: 'click', button: 'left' });
    } else if (quick && maxFingers === 2) {
      buzz();
      send({ type: 'key', key: 'back' });
    }
  };
  pad.addEventListener('pointerup', end);
  pad.addEventListener('pointercancel', end);
}

// --- Keyboard -------------------------------------------------------------------------

// A zero-width space keeps the field non-empty, so the delete key still
// fires when there is nothing left to erase on the phone.
const SENTINEL = '​';

function bindTyping(input) {
  const hint = $('#typing-hint');
  const showHint = () => { hint.hidden = input.value.replace(SENTINEL, '') !== ''; };
  const reset = () => { input.value = SENTINEL; showHint(); };
  reset();
  input.addEventListener('input', showHint);
  input.addEventListener('focus', () => {
    if (!input.value.startsWith(SENTINEL)) input.value = SENTINEL + input.value;
  });
  input.addEventListener('beforeinput', (e) => {
    switch (e.inputType) {
      case 'insertText':
      case 'insertReplacementText':
      case 'insertFromPaste': {
        const text = e.data ?? e.dataTransfer?.getData('text/plain') ?? '';
        if (text) send({ type: 'text', text });
        break;
      }
      case 'insertLineBreak':
      case 'insertParagraph':
        e.preventDefault();
        send({ type: 'key', key: 'enter' });
        reset();
        break;
      case 'deleteContentBackward':
        send({ type: 'key', key: 'backspace' });
        if (input.value === SENTINEL) e.preventDefault();
        break;
      default:
        break;
    }
  });
  input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      send({ type: 'key', key: 'enter' });
      reset();
    }
  });
}

// --- Screens -------------------------------------------------------------------------

function showPair() {
  $('#remote').hidden = true;
  $('#pair').hidden = false;
  $('#pin').value = '';
  showCode();
}

// The code in six boxes, three and three, as the TV shows it.
function showCode() {
  const pin = $('#pin');
  const code = pin.value.replace(/\D/g, '').slice(0, 6);
  if (pin.value !== code) pin.value = code;
  const focused = document.activeElement === pin;
  $$('.box').forEach((box, i) => {
    box.textContent = code[i] || '';
    box.classList.toggle('filled', i < code.length);
    box.classList.toggle('active', focused && i === code.length && !pairing);
  });
  $('#pair-btn').setAttribute('aria-disabled', String(code.length !== 6 && !pairing));
}

const ERROR_ICONS = {
  code: 'M12 8v5 M12 16.5h.01 M10.3 3.9L2.4 18a2 2 0 0 0 1.7 3h15.8a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z',
  net: 'M2.5 9a14 14 0 0 1 19 0 M5.5 12.5a9.5 9.5 0 0 1 13 0 M8.8 16a5 5 0 0 1 6.4 0 M12 19.5h.01 M4 3.5l16 17',
};

// kind: code (the boxes turn red) or net; no text clears it.
function pairError(kind, text) {
  $('#pair-error').hidden = !text;
  $('#boxes').classList.toggle('bad', !!text && kind === 'code');
  if (!text) return;
  $('#pair-error-icon').setAttribute('d', ERROR_ICONS[kind]);
  $('#pair-error-text').textContent = text;
}

function showRemote() {
  $('#pair').hidden = true;
  $('#remote').hidden = false;
  if (DEMO) {
    $('#tv-name').textContent = 'Otto TV';
    setStatus('demo');
  } else {
    setStatus('off');
    connect();
  }
}

function setMode(mode) {
  $$('.tabs [role="tab"]').forEach((tab) => {
    tab.setAttribute('aria-selected', String(tab.dataset.mode === mode));
  });
  $('#mode-buttons').hidden = mode !== 'buttons';
  $('#mode-touchpad').hidden = mode !== 'touchpad';
  $('#mode-keyboard').hidden = mode !== 'keyboard';
  releaseAll();
  if (mode === 'keyboard') $('#typing').focus();
}

let pairing = false;

async function pair(pin) {
  if (pairing || pin.length !== 6) return;
  pairError('', '');
  if (DEMO) { showRemote(); return; }
  pairing = true;
  $('#pair-btn').textContent = t('pairing');
  showCode();
  try {
    const res = await fetch('/api/pair', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ pin }),
    });
    const body = await res.json().catch(() => ({}));
    if (res.ok && body.token) {
      saveToken(body.token);
      fresh = true;
      showRemote();
    } else {
      pairError('code', body.error === 'wrong_pin' ? t('wrongPin') : t('pairClosed'));
    }
  } catch {
    pairError('net', t('pairFailed'));
  } finally {
    pairing = false;
    $('#pair-btn').textContent = t('pairButton');
    showCode();
  }
}

function init() {
  applyTexts();
  takeTokenFromURL();
  $$('[data-key]').forEach(bindKey);
  $('#laptop-btn').addEventListener('click', () => {
    buzz();
    send({ type: 'laptop' });
  });
  if (DEMO) setLaptop('on');
  bindTouchpad($('#pad'));
  bindTyping($('#typing'));
  $$('.tabs [role="tab"]').forEach((tab) => {
    tab.addEventListener('click', () => setMode(tab.dataset.mode));
  });
  const pin = $('#pin');
  pin.addEventListener('input', () => { pairError('', ''); showCode(); });
  pin.addEventListener('focus', showCode);
  pin.addEventListener('blur', showCode);
  const pairBtn = $('#pair-btn');
  pairBtn.addEventListener('pointerdown', () => pairBtn.classList.add('pressed'));
  ['pointerup', 'pointercancel', 'pointerleave'].forEach((ev) => pairBtn.addEventListener(ev, () => pairBtn.classList.remove('pressed')));
  $('#pair-form').addEventListener('submit', (e) => {
    e.preventDefault();
    pair(pin.value.trim());
  });
  if (token || DEMO) showRemote();
  else showPair();
}

init();
