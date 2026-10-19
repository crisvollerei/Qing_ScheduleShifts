import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * 静态资源与页面结构检查。
 *
 * 上游仓库的这些断言同时覆盖 Vercel / Cloudflare / _headers 等部署配置；
 * 本仓库是 Android 封装，只保留对打包内嵌资源仍然成立的检查。
 */
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..','app','src','main','assets');
const html = fs.readFileSync(path.join(root,'index.html'),'utf8');

test('HTML uses external scripts and event listeners compatible with a strict CSP',() => {
  assert.doesNotMatch(html,/\son\w+\s*=/i);
  for (const script of html.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/g)) {
    assert.match(script[1],/src=/);assert.equal(script[2].trim(),'');
  }
  assert.match(html,/<script type="module" src="assets\/main\.js/);
  assert.match(html,/http-equiv="Content-Security-Policy"/);
});

test('all directly referenced assets exist and have content-derived cache versions',() => {
  for (const [,url] of html.matchAll(/(?:src|href|data-src)="(assets\/[^\"]+)"/g)) {
    assert.match(url,/\?v=[a-f0-9]{12}$/);
    assert.ok(fs.existsSync(path.join(root,url.split('?')[0])),url);
  }
});

test('small optimized images replace large originals while source artwork is preserved',() => {
  for (const [output,limit,original] of [['assets/avatar.webp',6000,null],['assets/chibi-day.webp',45000,'assets/chibi-day.png'],['assets/chibi-night.webp',25000,'assets/chibi-night.png']]) {
    assert.ok(fs.statSync(path.join(root,output)).size<limit,output);
    // chibi 原图仍是小部件构建的输入源，必须保留；avatar 原图在本仓库已不再使用。
    if (original) assert.ok(fs.existsSync(path.join(root,original)),original);
    if (original) assert.equal(html.includes(`src="${original}"`),false);
  }
  for (const tag of html.matchAll(/<img[^>]+data-image-theme[^>]*>/g)) assert.doesNotMatch(tag[0],/\ssrc=/);
});

test('orbit travelers use the exact visible path inside the same tilted SVG plane',() => {
  const css = fs.readFileSync(path.join(root,'assets/themes.css'),'utf8');
  const rings = [...html.matchAll(/<g class="orbit-ring ([^"]+)" transform="([^"]+)">\s*<path class="orbit-track" d="([^"]+)"\/>/g)];
  assert.equal(rings.length,4);
  for (const [,name,transform,geometry] of rings) {
    const motionPath = css.match(new RegExp(`\\.${name} \\{ --orbit-path: path\\('([^']+)'\\)`));
    assert.equal(motionPath?.[1],geometry,name);
    assert.match(transform,/^translate\([\d ]+\)( rotate\(-?\d+\))?$/);
  }
  assert.equal([...html.matchAll(/class="orbit-marker /g)].length,10);
  assert.doesNotMatch(html,/class="scene-orbit|class="scene-particle|class="art-spark/);
  assert.doesNotMatch(html,/<[^>]+\sstyle=/);
  assert.match(css,/offset-anchor: 0px 0px; offset-rotate: 0deg/);
});

test('every orbit diamond spins its centered glyph without changing the travel path',() => {
  const css = fs.readFileSync(path.join(root,'assets/motion.css'),'utf8');
  const theme = fs.readFileSync(path.join(root,'assets/themes.css'),'utf8');
  const diamonds = [...html.matchAll(/<g class="orbit-marker marker-diamond[^\"]*"><path class="orbit-glyph" d="([^\"]+)"\/>/g)];
  assert.equal(diamonds.length,4);
  for (const [,shape] of diamonds) assert.match(shape,/^M0-/);
  assert.match(theme,/\.marker-diamond \.orbit-glyph \{ transform-box: view-box; transform-origin: 0px 0px;/);
  assert.match(css,/@keyframes facet-spin\s*\{\s*from \{ transform: rotate\(0turn\); \}\s*to \{ transform: rotate\(1turn\); \}/);
  assert.match(css,/\.marker-diamond \.orbit-glyph \{ animation: facet-spin[^}]+paused;/);
  assert.match(css,/\.ambient-visible \.marker-diamond \.orbit-glyph \{ animation-play-state: running;/);
  assert.match(css,/animation-delay: calc\(var\(--orbit-phase\) - var\(--orbit-duration\) \* var\(--marker-gap,0\)\)/);
});

test('responsive columns share height and the companion note does not stretch into a banner',() => {
  const css = fs.readFileSync(path.join(root,'assets/companion.css'),'utf8');
  assert.match(css,/@media \(min-width: 961px\)/);
  assert.match(css,/\.workspace-grid > \.calendar-section \{ align-self: stretch; display: flex; flex-direction: column;/);
  assert.match(css,/\.calendar-section \.days-grid \{ flex: 1;/);
  assert.match(css,/\.workspace-grid > \.sidebar \{ align-self: stretch; position: static;/);
  assert.match(css,/@media \(min-width: 641px\) and \(max-width: 960px\)/);
  assert.match(css,/\.sidebar \{ align-items: stretch;/);
  assert.match(css,/\.sidebar \.stats-container \{ display: flex; flex-direction: column;/);
  assert.match(css,/width: min\(100%, 420px\)/);
  assert.doesNotMatch(css,/width: min\(100%, 620px\)/);
});

test('orbital motion stays CSS-only and respects visibility, dialogs and reduced motion',() => {
  const css = fs.readFileSync(path.join(root,'assets/motion.css'),'utf8');
  assert.match(css,/@keyframes track-travel\s*\{\s*from \{ offset-distance: 0%; \}\s*to \{ offset-distance: 100%; \}/);
  assert.match(css,/\.ambient-visible \.orbit-marker/);
  assert.match(css,/\.main-container\[inert\] \*/);
  assert.match(css,/@media \(prefers-reduced-motion: reduce\)/);
  assert.match(css,/@supports not \(offset-path:/);
  assert.doesNotMatch(html,/<animateMotion|<animateTransform|<animate\s/);
});
