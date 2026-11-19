#!/usr/bin/env node
// Regenerates Otto TV's raster assets from the vector sources in art/brand.
// A development tool: its outputs are committed, so building the ISO doesn't
// need a browser. Run it after changing art/brand/otto-geo.js or the texts:
//
//   node tools/brand/make-assets.js        (needs Playwright with Chromium)
//
// Writes:
//   art/brand/*.svg                           the brand's SVG files (from otto-geo.js)
//   iso/boot/grub/themes/otto/logo.png        boot menu logo
//   iso/otto/bootanimation{,-first}.zip       Android boot animation
//   iso/otto/installer/branding/otto/*.png    installer images
//   iso/otto/installer/wallpaper.png          installer desktop
//   tvapp/res/drawable*/{icon,banner,preparing,loading}.png  "Connect phone" app
//   remote/web/static/icon-*.png              phone remote (home screen icon)
//   remote/web/static/{otto-*.svg,inter.woff2}  "Connect phone" and the remote
const fs = require('fs');
const os = require('os');
const path = require('path');
const { execFileSync } = require('child_process');
const { chromium } = require('playwright');

const ROOT = path.resolve(__dirname, '..', '..');
const G = require(path.join(ROOT, 'art/brand/otto-geo.js'));
const svgFile = (name) => fs.readFileSync(path.join(ROOT, 'art/brand', name), 'utf8');
const inner = (svg) => svg.replace(/^[\s\S]*?<svg[^>]*>/, '').replace(/<\/svg>\s*$/, '');

const FONT = fs.readFileSync(path.join(ROOT, 'art/fonts/inter-latin-wght-normal.woff2')).toString('base64');
const CSS =
  "@font-face{font-family:Inter;src:url(data:font/woff2;base64," + FONT + ") format('woff2');font-weight:100 900}" +
  'html,body{margin:0;background:transparent}body{font-family:Inter;color:#F4F6F9}' +
  '#a{display:inline-block}';

const BG = '#161B22';
const PANEL = '#1D232C';

async function render(browser, html, out, { scale = 1, opaque = false } = {}) {
  const page = await browser.newPage({ deviceScaleFactor: scale });
  await page.setContent('<!doctype html><meta charset="utf-8"><style>' + CSS + '</style><div id="a">' + html + '</div>');
  await page.evaluate(() => document.fonts.ready);
  fs.mkdirSync(path.dirname(out), { recursive: true });
  await page.locator('#a').screenshot({ path: out, omitBackground: !opaque });
  await page.close();
  console.log('wrote', path.relative(ROOT, out));
}

// The brand's SVG files came from the design's generator, which used this
// same geometry: they are rebuilt from it, keeping each file's <svg> line
// (size, accessible name) and, in the banner, the background and the name.
function brandSvgs() {
  const logoAt = (tr, art) => '<g transform="' + tr + '">' + art + '</g>';
  const files = {
    'logo.svg': G.LOGO,
    'icone-frente.svg': logoAt('translate(134.4 125.85) scale(3.8)', G.LOGO),
    'icone-uma-cor.svg': logoAt('translate(134.4 125.85) scale(3.8)', G.logoMono('#FFFFFF')),
    'otto-neutro.svg': G.pose('neutral'),
    'otto-acenando.svg': G.pose('wave'),
    'otto-comemorando.svg': G.pose('cheer'),
    'otto-dormindo.svg': G.pose('sleep'),
    'otto-pensando.svg': G.pose('think'),
  };
  for (const [name, art] of Object.entries(files)) {
    const file = path.join(ROOT, 'art/brand', name);
    const head = fs.readFileSync(file, 'utf8').match(/^[\s\S]*?<svg[^>]*>\n?/)[0];
    fs.writeFileSync(file, head + art + '\n</svg>\n');
    console.log('wrote', path.relative(ROOT, file));
  }
  const banner = path.join(ROOT, 'art/brand/banner-android-tv.svg');
  const old = fs.readFileSync(banner, 'utf8');
  const tr = 'translate(31 38.6) scale(1.5)';
  const at = old.indexOf('<g transform="' + tr + '">');
  const end = old.indexOf('</g>', at);
  if (at < 0 || end < 0) throw new Error('banner-android-tv.svg: logo group not found');
  fs.writeFileSync(banner, old.slice(0, at) + logoAt(tr, G.LOGO) + old.slice(end + 4));
  console.log('wrote', path.relative(ROOT, banner));
}

const mark = (size) => '<svg viewBox="0 0 64 64" width="' + size + '" height="' + size + '">' + G.LOGO + '</svg>';
const word = (size, extra = '') =>
  // The right padding keeps the V's tip: negative tracking narrows the box.
  '<span style="font-size:' + size + 'px;font-weight:800;line-height:1;letter-spacing:-0.03em;padding-right:0.08em;' + extra + '">Otto TV</span>';
const row = (gap, ...parts) => '<div style="display:flex;align-items:center;gap:' + gap + 'px">' + parts.join('') + '</div>';

async function bootMenuLogo(browser) {
  // Bootmenu-768: an 84 px mark, 18 px gap and the name at 46 px.
  await render(browser, row(18, mark(84), word(46)), path.join(ROOT, 'iso/boot/grub/themes/otto/logo.png'));
}

// Boot-animation: 336 px Otto with the name under it, centered in a 640x640
// square; the rest of the screen is the background color. The first boot's
// (bootanimation-first.zip, chosen by iso/scripts/9-otto) adds two lines:
// that boot takes minutes.
async function bootAnimation(browser, name, caption) {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'otto-boot-'));
  const part = path.join(tmp, 'part0');
  fs.mkdirSync(part);
  const lines = caption
    ? '<p style="margin:12px 0 0;font-size:24px;font-weight:500;line-height:32px;color:#A9B3C1;text-align:center">' + caption.join('<br>') + '</p>'
    : '';
  for (let n = 0; n < G.BOOT_FRAMES; n++) {
    const html =
      '<div style="width:640px;height:640px;background:' + BG + ';display:flex;flex-direction:column;align-items:center;justify-content:center">' +
      '<svg viewBox="30 40 140 140" width="336" height="336">' + G.bootOtto(n) + '</svg>' +
      '<p style="margin:16px 0 0;font-size:56px;font-weight:800;line-height:68px;letter-spacing:-0.03em">Otto TV</p>' + lines + '</div>';
    const page = await browser.newPage();
    await page.setContent('<!doctype html><meta charset="utf-8"><style>' + CSS + '</style><div id="a">' + html + '</div>');
    await page.evaluate(() => document.fonts.ready);
    await page.locator('#a').screenshot({ path: path.join(part, String(n).padStart(5, '0') + '.png') });
    await page.close();
  }
  // TYPE COUNT PAUSE PATH #RGBHEX: the color fills the rest of the screen.
  // (A FADE count before the color is for "f" parts only: in 0.4.2 it made
  // Android skip the color, and the square showed on black.)
  fs.writeFileSync(path.join(tmp, 'desc.txt'), '640 640 ' + G.BOOT_FPS + '\np 0 0 part0 #161B22\n');
  const out = path.join(ROOT, 'iso/otto', name);
  fs.rmSync(out, { force: true });
  // Android reads the frames in place: the zip must be stored, not deflated.
  execFileSync('zip', ['-q', '-0', '-X', '-r', out, 'desc.txt', 'part0'], { cwd: tmp });
  fs.rmSync(tmp, { recursive: true, force: true });
  console.log('wrote', path.relative(ROOT, out), G.BOOT_FRAMES + ' frames');
}

// Otto pressing a key (Installer-slide3), labeled Esc. The tentacle starts
// just left of the body's shaded side (was 130): its square-cut root showed
// as a step on the shade.
function escKey() {
  const reach = [[[127, 104], [142, 102], [150, 108], [153, 118]], [[153, 118], [154, 127], [161, 131], [165, 127]]];
  const key = G.P(G.rrect(140, 132, 52, 30, 7), '#9AA5B3') + G.P(G.rrect(140, 128, 52, 28, 7), '#E3E8EE') +
    '<text x="166" y="146.5" text-anchor="middle" font-family="Inter" font-weight="700" font-size="12" fill="#151A21">Esc</text>';
  const arm = G.P(G.taper(reach, G.AW0, 2.5 * G.K), G.COL.body) + G.P(G.suck(reach, G.AW0, 2.5 * G.K, 3), G.COL.suck);
  return G.pose('neutral', { sides: [G.A.sideL], look: [3, 2], after: key + arm });
}

async function installer(browser) {
  const dir = path.join(ROOT, 'iso/otto/installer/branding/otto');
  // Sidebar header and window icon.
  await render(browser, row(10, mark(44), word(26)), path.join(dir, 'logo.png'), { scale: 2 });
  await render(browser, mark(64), path.join(dir, 'icon.png'), { scale: 2 });
  // Welcome page: Otto waving.
  await render(browser, '<svg viewBox="28 22 150 150" width="200" height="200">' + G.pose('wave') + '</svg>',
    path.join(dir, 'welcome.png'), { scale: 2 });
  // Slides shown while installing (Installer-slide1..3, and a fourth).
  const phone = '<g transform="rotate(12 146 62)">' + G.P(G.rrect(135, 43, 22, 38, 4.5), '#3A4352') +
    G.P(G.rrect(137.5, 46.5, 17, 29, 2.5), PANEL) + G.P(G.circ(146, 66, 3.4), '#F2B25C') + '</g>';
  // Otto TV: a fourth one, for the end (iso/otto/installer/otto-restart).
  const stick = '<g transform="rotate(12 146 62)">' + G.P(G.rrect(139.5, 41, 13, 14, 2), '#C9D1DB') +
    G.P(G.rrect(142, 44.5, 3, 3, 0.6) + G.rrect(147, 44.5, 3, 3, 0.6), '#8A95A5') +
    G.P(G.rrect(136, 53, 20, 30, 5), '#3A4352') + G.P(G.rrect(140, 58, 12, 9, 2.5), '#F2B25C') + '</g>';
  const slides = [
    ['0 0 64 64', G.LOGO, 'Seu computador antigo vai virar uma TV.'],
    ['28 22 150 150', G.pose('wave', { held: phone }), 'O celular vira controle e teclado.'],
    ['28 30 170 170', escKey(), 'Esc volta e a tecla Windows leva ao início.'],
    ['28 22 150 150', G.pose('wave', { held: stick }), 'No fim, a tela avisa a hora de tirar o pen-drive.'],
  ];
  for (let i = 0; i < slides.length; i++) {
    const [vb, art, text] = slides[i];
    const html =
      '<div style="width:480px;height:236px;box-sizing:border-box;border-radius:20px;background:' + PANEL + ';' +
      'padding:0 28px 0 20px;display:flex;align-items:center;gap:16px">' +
      '<svg viewBox="' + vb + '" width="176" height="176" style="flex:none">' + art + '</svg>' +
      '<p style="margin:0;font-size:28px;font-weight:700;line-height:36px;letter-spacing:-0.01em">' + text + '</p></div>';
    await render(browser, html, path.join(dir, 'slide' + (i + 1) + '.png'), { scale: 2 });
  }
}

async function installerWallpaper(browser) {
  // The installer's little desktop. A tile, repeated: with a TV plugged in,
  // that desktop spans the laptop and the TV, and a centered picture got cut
  // between them. Two small Ottos per tile, half a tile apart.
  const otto = (x, y) =>
    '<svg viewBox="30 40 140 140" width="96" height="96" style="position:absolute;left:' + x + 'px;top:' + y + 'px;filter:grayscale(1) brightness(1.5);opacity:0.11">' +
    G.bootOtto(0) + '</svg>';
  const html =
    '<div style="width:400px;height:400px;position:relative;overflow:hidden;background:' + BG + '">' +
    otto(52, 52) + otto(252, 252) + '</div>';
  await render(browser, html, path.join(ROOT, 'iso/otto/installer/wallpaper.png'), { opaque: true });
}

async function connectApp(browser) {
  // Legacy launcher icon: the adaptive icon's two layers, rounded. No mask
  // trims the adaptive margin here, so the front layer is drawn larger.
  const icon =
    '<div style="width:192px;height:192px;border-radius:42px;overflow:hidden;position:relative">' +
    '<svg viewBox="0 0 512 512" width="192" height="192" style="position:absolute;inset:0">' + inner(svgFile('icone-fundo.svg')) + '</svg>' +
    '<svg viewBox="76 76 360 360" width="192" height="192" style="position:absolute;inset:0">' + inner(svgFile('icone-frente.svg')) + '</svg></div>';
  await render(browser, icon, path.join(ROOT, 'tvapp/res/drawable-nodpi/icon.png'));
  // Banners: Otto holding a phone, and the app's name.
  const banner = (l1, l2) =>
    '<div style="width:320px;height:180px;box-sizing:border-box;background:linear-gradient(135deg,#2B323E,' + BG + ');' +
    'display:flex;align-items:center;gap:6px;padding:0 18px 0 6px">' +
    '<svg viewBox="28 22 150 150" width="136" height="136" style="flex:none">' +
    G.pose('wave', { held: '<g transform="rotate(12 146 62)">' + G.P(G.rrect(135, 43, 22, 38, 4.5), '#3A4352') +
      G.P(G.rrect(137.5, 46.5, 17, 29, 2.5), PANEL) + G.P(G.circ(146, 66, 3.4), '#F2B25C') + '</g>' }) + '</svg>' +
    '<p style="margin:0;font-size:30px;font-weight:800;line-height:34px;letter-spacing:-0.02em">' + l1 + '<br>' + l2 + '</p></div>';
  await render(browser, banner('Conectar', 'celular'), path.join(ROOT, 'tvapp/res/drawable-pt-nodpi/banner.png'), { opaque: true });
  // "Almost ready" (PreparingActivity): Otto waving, drawn at 200 dp on
  // screens up to xhdpi.
  await render(browser, '<svg viewBox="28 22 150 150" width="400" height="400">' + G.pose('wave') + '</svg>',
    path.join(ROOT, 'tvapp/res/drawable-nodpi/preparing.png'));
  // "Loading..." ("Connect phone" while its page starts): Otto thinking.
  await render(browser, '<svg viewBox="20 16 160 160" width="400" height="400">' + G.pose('think') + '</svg>',
    path.join(ROOT, 'tvapp/res/drawable-nodpi/loading.png'));
  await render(browser, banner('Connect', 'phone'), path.join(ROOT, 'tvapp/res/drawable-nodpi/banner.png'), { opaque: true });
}

async function phoneRemote(browser) {
  // Home screen icon for the phone remote: full-bleed (iOS rounds it).
  const icon = (size) =>
    '<div style="width:' + size + 'px;height:' + size + 'px;position:relative">' +
    '<svg viewBox="0 0 512 512" width="' + size + '" height="' + size + '" style="position:absolute;inset:0">' + inner(svgFile('icone-fundo.svg')) + '</svg>' +
    '<svg viewBox="76 76 360 360" width="' + size + '" height="' + size + '" style="position:absolute;inset:0">' + inner(svgFile('icone-frente.svg')) + '</svg></div>';
  await render(browser, icon(180), path.join(ROOT, 'remote/web/static/icon-180.png'), { opaque: true });
  await render(browser, icon(512), path.join(ROOT, 'remote/web/static/icon-512.png'), { opaque: true });
  fs.writeFileSync(path.join(ROOT, 'remote/web/static/icon.svg'), svgFile('logo.svg'));
  console.log('wrote remote/web/static/icon.svg');
  // Otto on the "Connect phone" screen and the remote, sized like the
  // design's (its body spans 60 of 120 units there).
  const otto = (name, vb, art) => {
    const out = path.join(ROOT, 'remote/web/static', name);
    fs.writeFileSync(out, '<svg xmlns="http://www.w3.org/2000/svg" viewBox="' + vb + '">' + art + '</svg>\n');
    console.log('wrote', path.relative(ROOT, out));
  };
  otto('otto-wave.svg', '32 31 136 136', G.pose('wave'));
  otto('otto-cheer.svg', '20 16 160 160', G.pose('cheer'));
  otto('otto-think.svg', '32 31 136 136', G.pose('think'));
  // Inter, the brand's typeface (SIL OFL, art/fonts/LICENSE-Inter.txt).
  fs.copyFileSync(path.join(ROOT, 'art/fonts/inter-latin-wght-normal.woff2'), path.join(ROOT, 'remote/web/static/inter.woff2'));
  console.log('wrote remote/web/static/inter.woff2');
}

(async () => {
  const browser = await chromium.launch();
  try {
    brandSvgs();
    await bootMenuLogo(browser);
    await bootAnimation(browser, 'bootanimation.zip');
    await bootAnimation(browser, 'bootanimation-first.zip', ['Preparando tudo pela primeira vez.', 'Isso leva alguns minutos.']);
    await installer(browser);
    await installerWallpaper(browser);
    await connectApp(browser);
    await phoneRemote(browser);
  } finally {
    await browser.close();
  }
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
