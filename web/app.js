/* Screenshotter (Web) – Screenshots zuschneiden und zu einem PDF zusammenfügen.
 * Läuft vollständig lokal im Browser. Logik für Layout und Überlappung entspricht der Android-App. */
'use strict';

const $ = (id) => document.getElementById(id);
const store = {
  get(k, d) { try { const v = localStorage.getItem(k); return v === null ? d : JSON.parse(v); } catch (e) { return d; } },
  set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch (e) { /* privat / gesperrt */ } },
};

// ---------------------------------------------------------------------------------------------
// Zustand
// ---------------------------------------------------------------------------------------------

/** @type {{file: File, url: string, name: string, time: number, w: number, h: number}[]} */
let items = [];
let active = 0;
/** Auswahl als Anteil der Bildgröße (0..1), damit sie auch bei anderen Bildgrößen passt. */
let sel = store.get('sel', null) || { x: 0, y: 0, w: 1, h: 1 };
let pdfFile = null;
let pdfUrl = null;

// ---------------------------------------------------------------------------------------------
// Dateien
// ---------------------------------------------------------------------------------------------

const collator = new Intl.Collator('de', { numeric: true, sensitivity: 'base' });

$('files').addEventListener('change', async (e) => {
  const files = Array.from(e.target.files || []);
  e.target.value = '';
  if (!files.length) return;
  for (const f of files) {
    const url = URL.createObjectURL(f);
    const it = { file: f, url, name: f.name || 'Bild', time: f.lastModified || 0, w: 0, h: 0 };
    try {
      const dim = await imageSize(url);
      it.w = dim.w; it.h = dim.h;
    } catch (err) {
      URL.revokeObjectURL(url);
      setStatus(`„${it.name}“ ist kein lesbares Bild und wurde übersprungen.`, 'error');
      continue;
    }
    items.push(it);
  }
  renderThumbs();
  if (items.length) showEditor(Math.min(active, items.length - 1));
});

function imageSize(url) {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.onload = () => resolve({ w: img.naturalWidth, h: img.naturalHeight });
    img.onerror = reject;
    img.src = url;
  });
}

$('sortName').onclick = () => { items.sort((a, b) => collator.compare(a.name, b.name)); renderThumbs(); };
$('sortTime').onclick = () => { items.sort((a, b) => a.time - b.time || collator.compare(a.name, b.name)); renderThumbs(); };
$('clearAll').onclick = () => {
  items.forEach((it) => URL.revokeObjectURL(it.url));
  items = []; active = 0;
  renderThumbs();
};

function renderThumbs() {
  const box = $('thumbs');
  box.innerHTML = '';
  items.forEach((it, i) => {
    const d = document.createElement('div');
    d.className = 'thumb' + (i === active ? ' active' : '');
    const img = document.createElement('img');
    img.src = it.url; img.alt = it.name; img.loading = 'lazy';
    img.onclick = () => showEditor(i);
    const bar = document.createElement('div');
    bar.className = 'bar';
    const mk = (label, title, fn, disabled) => {
      const b = document.createElement('button');
      b.textContent = label; b.title = title; b.setAttribute('aria-label', title);
      b.disabled = !!disabled; b.onclick = fn; return b;
    };
    bar.append(
      mk('◀', 'Nach vorne', () => move(i, -1), i === 0),
      Object.assign(document.createElement('span'), { textContent: String(i + 1) }),
      mk('▶', 'Nach hinten', () => move(i, 1), i === items.length - 1),
      mk('✕', 'Entfernen', () => removeAt(i)),
    );
    d.append(img, bar);
    box.append(d);
  });
  const n = items.length;
  $('fileInfo').textContent = n
    ? `${n} Bild(er). Reihenfolge im PDF = Reihenfolge hier; mit ◀ ▶ verschiebbar.`
    : 'Noch keine Bilder gewählt. Reihenfolge im PDF = Reihenfolge hier; mit ◀ ▶ verschiebbar.';
  ['sortName', 'sortTime', 'clearAll'].forEach((id) => { $(id).disabled = n === 0; });
  $('regionSection').classList.toggle('hidden', n === 0);
  $('optSection').classList.toggle('hidden', n === 0);
  if (n === 0) clearEditor();
}

function move(i, d) {
  const j = i + d;
  if (j < 0 || j >= items.length) return;
  [items[i], items[j]] = [items[j], items[i]];
  if (active === i) active = j; else if (active === j) active = i;
  renderThumbs();
}

function removeAt(i) {
  URL.revokeObjectURL(items[i].url);
  items.splice(i, 1);
  if (active >= items.length) active = Math.max(0, items.length - 1);
  renderThumbs();
  if (items.length) showEditor(active);
}

// ---------------------------------------------------------------------------------------------
// Bereichs-Editor
// ---------------------------------------------------------------------------------------------

const editor = $('editor');
const ectx = editor.getContext('2d');
let editorImg = null;  // verkleinertes Bild zum Anzeigen
const MAX_EDITOR = 1600;

async function showEditor(i) {
  active = i;
  document.querySelectorAll('.thumb').forEach((t, k) => t.classList.toggle('active', k === i));
  const it = items[i];
  const img = new Image();
  img.src = it.url;
  await img.decode().catch(() => {});
  const s = Math.min(1, MAX_EDITOR / Math.max(it.w, it.h));
  const c = document.createElement('canvas');
  c.width = Math.round(it.w * s); c.height = Math.round(it.h * s);
  c.getContext('2d').drawImage(img, 0, 0, c.width, c.height);
  editorImg = c;
  editor.width = c.width; editor.height = c.height;
  fitEditor();
  drawEditor();
}

function clearEditor() { editorImg = null; editor.width = 1; editor.height = 1; }

function fitEditor() {
  if (!editorImg) return;
  const wrapW = $('editorWrap').clientWidth;
  const maxH = window.innerHeight * 0.75;
  const s = Math.min(wrapW / editor.width, maxH / editor.height);
  editor.style.width = Math.floor(editor.width * s) + 'px';
  editor.style.height = Math.floor(editor.height * s) + 'px';
}
window.addEventListener('resize', fitEditor);

function selPx() {
  return { x: sel.x * editor.width, y: sel.y * editor.height, w: sel.w * editor.width, h: sel.h * editor.height };
}

function drawEditor() {
  if (!editorImg) return;
  const W = editor.width, H = editor.height;
  ectx.drawImage(editorImg, 0, 0);
  const r = selPx();
  ectx.fillStyle = 'rgba(0,0,0,0.5)';
  ectx.fillRect(0, 0, W, r.y);
  ectx.fillRect(0, r.y + r.h, W, H - r.y - r.h);
  ectx.fillRect(0, r.y, r.x, r.h);
  ectx.fillRect(r.x + r.w, r.y, W - r.x - r.w, r.h);
  const px = cssToCanvas();
  ectx.lineWidth = 3 * px;
  ectx.strokeStyle = '#1E6FD9';
  ectx.strokeRect(r.x, r.y, r.w, r.h);
  ectx.fillStyle = '#1E6FD9';
  for (const [x, y] of [[r.x, r.y], [r.x + r.w, r.y], [r.x, r.y + r.h], [r.x + r.w, r.y + r.h]]) {
    ectx.beginPath(); ectx.arc(x, y, 9 * px, 0, Math.PI * 2); ectx.fill();
  }
  const it = items[active];
  if (it) {
    const w = Math.round(sel.w * it.w), h = Math.round(sel.h * it.h);
    $('regionInfo').textContent = `Ausschnitt: ${w} × ${h} Pixel (Originalauflösung)`;
  }
}

/** Faktor Canvas-Pixel pro CSS-Pixel. */
function cssToCanvas() {
  const r = editor.getBoundingClientRect();
  return r.width ? editor.width / r.width : 1;
}

let drag = null;
editor.addEventListener('pointerdown', (e) => {
  if (!editorImg) return;
  editor.setPointerCapture(e.pointerId);
  const p = toCanvas(e);
  const r = selPx();
  const slop = 28 * cssToCanvas();
  const nearL = Math.abs(p.x - r.x) < slop, nearR = Math.abs(p.x - (r.x + r.w)) < slop;
  const nearT = Math.abs(p.y - r.y) < slop, nearB = Math.abs(p.y - (r.y + r.h)) < slop;
  const inX = p.x > r.x - slop && p.x < r.x + r.w + slop;
  const inY = p.y > r.y - slop && p.y < r.y + r.h + slop;
  const d = { start: p, rect: r, l: nearL && inY, r: false, t: nearT && inX, b: false };
  d.r = nearR && inY && !d.l;
  d.b = nearB && inX && !d.t;
  const edge = d.l || d.r || d.t || d.b;
  const inside = p.x > r.x && p.x < r.x + r.w && p.y > r.y && p.y < r.y + r.h;
  const nearlyFull = sel.w > 0.97 && sel.h > 0.97; // dann ergibt Verschieben keinen Sinn -> neu aufziehen
  d.mode = edge && !(nearlyFull && inside && !nearEdgeStrict(p, r)) ? 'resize' : inside && !nearlyFull ? 'move' : 'create';
  drag = d;
  e.preventDefault();
});
editor.addEventListener('pointermove', (e) => {
  if (!drag) return;
  const p = toCanvas(e);
  const W = editor.width, H = editor.height, min = 24 * cssToCanvas();
  const dx = p.x - drag.start.x, dy = p.y - drag.start.y;
  let { x, y, w, h } = drag.rect;
  if (drag.mode === 'move') {
    x = clamp(x + dx, 0, W - w); y = clamp(y + dy, 0, H - h);
  } else if (drag.mode === 'create') {
    const x2 = clamp(p.x, 0, W), y2 = clamp(p.y, 0, H);
    const sx = clamp(drag.start.x, 0, W), sy = clamp(drag.start.y, 0, H);
    x = Math.min(sx, x2); y = Math.min(sy, y2); w = Math.abs(x2 - sx); h = Math.abs(y2 - sy);
  } else {
    let l = x, t = y, rr = x + w, b = y + h;
    if (drag.l) l = clamp(l + dx, 0, rr - min);
    if (drag.r) rr = clamp(rr + dx, l + min, W);
    if (drag.t) t = clamp(t + dy, 0, b - min);
    if (drag.b) b = clamp(b + dy, t + min, H);
    x = l; y = t; w = rr - l; h = b - t;
  }
  setSelPx(x, y, w, h);
  drawEditor();
  e.preventDefault();
});
const endDrag = () => {
  if (!drag) return;
  const min = 24 * cssToCanvas();
  if (drag.mode === 'create' && (sel.w * editor.width < min || sel.h * editor.height < min)) {
    const r = drag.rect; setSelPx(r.x, r.y, r.w, r.h); // zu klein -> verwerfen
  }
  drag = null;
  store.set('sel', sel);
  drawEditor();
};
editor.addEventListener('pointerup', endDrag);
editor.addEventListener('pointercancel', endDrag);

/** Bei vollem Rahmen nur sehr nah an der Kante als Größenänderung werten. */
function nearEdgeStrict(p, r) {
  const s = 12 * cssToCanvas();
  return Math.abs(p.x - r.x) < s || Math.abs(p.x - (r.x + r.w)) < s ||
    Math.abs(p.y - r.y) < s || Math.abs(p.y - (r.y + r.h)) < s;
}

function toCanvas(e) {
  const r = editor.getBoundingClientRect();
  return { x: (e.clientX - r.left) * editor.width / r.width, y: (e.clientY - r.top) * editor.height / r.height };
}
function setSelPx(x, y, w, h) {
  sel = { x: x / editor.width, y: y / editor.height, w: w / editor.width, h: h / editor.height };
}
function clamp(v, a, b) { return Math.max(a, Math.min(b, v)); }

$('fullImage').onclick = () => { sel = { x: 0, y: 0, w: 1, h: 1 }; store.set('sel', sel); drawEditor(); };

// ---------------------------------------------------------------------------------------------
// Optionen
// ---------------------------------------------------------------------------------------------

const contBox = $('continuous'), overlapBox = $('overlap'), nameField = $('name');
contBox.checked = store.get('continuous', true);
overlapBox.checked = store.get('overlap', true);
overlapBox.disabled = !contBox.checked;
contBox.onchange = () => { store.set('continuous', contBox.checked); overlapBox.disabled = !contBox.checked; };
overlapBox.onchange = () => store.set('overlap', overlapBox.checked);
nameField.value = defaultName();

function defaultName() {
  const d = new Date(), p = (n) => String(n).padStart(2, '0');
  return `Screenshotter_${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}_${p(d.getHours())}${p(d.getMinutes())}`;
}
function sanitizeName(s) {
  let n = (s || '').trim().replace(/[\\/:*?"<>|\u0000-\u001f]/g, '_');
  if (/\.pdf$/i.test(n)) n = n.slice(0, -4).trim();
  if (!n) n = defaultName();
  return n.slice(0, 120) + '.pdf';
}

function setStatus(msg, kind) {
  const s = $('status');
  s.textContent = msg || '';
  s.className = 'status' + (kind ? ' ' + kind : '');
}

// ---------------------------------------------------------------------------------------------
// Überlappungserkennung (wie Android-App)
// ---------------------------------------------------------------------------------------------

/** Zeilen-Fingerabdrücke eines Canvas. Rechte 6 % werden ignoriert (Scrollbalken). */
function rowSignature(canvas) {
  const w = canvas.width, h = canvas.height;
  const data = new Uint32Array(canvas.getContext('2d').getImageData(0, 0, w, h).data.buffer);
  const usable = Math.max(1, Math.floor(w * 0.94));
  const h1 = new Int32Array(h), h2 = new Int32Array(h), uniform = new Uint8Array(h);
  for (let y = 0; y < h; y++) {
    let a = 0x811c9dc5 | 0, b = 0;
    const off = y * w, first = data[off];
    let same = 1;
    for (let x = 0; x < usable; x++) {
      const p = data[off + x];
      if (p !== first) same = 0;
      a = Math.imul(a ^ p, 0x01000193);
      b = (b * 31 + p) | 0;
    }
    h1[y] = a; h2[y] = b; uniform[y] = same;
  }
  return { w, h, h1, h2, uniform };
}

/** Anzahl Zeilen oben in `next`, die schon unten in `prev` zu sehen waren (0 = keine). */
function findOverlap(prev, next) {
  if (prev.w !== next.w) return 0;
  const maxK = Math.min(prev.h, next.h);
  const minK = Math.max(12, Math.floor(maxK / 30));
  for (let k = maxK; k >= minK; k--) {
    const allowed = Math.floor(k / 10);
    const off = prev.h - k;
    let mism = 0, informative = 0, ok = true;
    for (let i = 0; i < k; i++) {
      if (prev.h1[off + i] !== next.h1[i] || prev.h2[off + i] !== next.h2[i]) {
        if (++mism > allowed) { ok = false; break; }
      } else if (!next.uniform[i]) informative++;
    }
    if (ok && informative >= Math.max(8, Math.floor(k / 5))) return k;
  }
  return 0;
}

/**
 * Sucht die Zeile y <= limit, die in einem einfarbigen Band (mind. 4 Zeilen) liegt und limit am
 * nächsten ist; 0, wenn es keine gibt. Ab dort beginnt die Aufnahme ohne angeschnittenen Inhalt.
 */
function whiteGapAtOrAbove(sig, limit) {
  let run = 0;
  for (let y = Math.min(limit, sig.h - 1); y >= 0; y--) {
    run = sig.uniform[y] ? run + 1 : 0;
    if (run >= 4) return y + Math.floor(run / 2); // Mitte des bisher gefundenen Bands
  }
  return 0;
}

// ---------------------------------------------------------------------------------------------
// PDF-Erstellung
// ---------------------------------------------------------------------------------------------

const A4_W = 595.28, A4_H = 841.89, MARGIN = 24, GAP = 8;

async function cropToCanvas(it) {
  const img = new Image();
  img.src = it.url;
  await img.decode();
  const W = img.naturalWidth, H = img.naturalHeight;
  const x = clamp(Math.round(sel.x * W), 0, W - 1), y = clamp(Math.round(sel.y * H), 0, H - 1);
  const w = clamp(Math.round(sel.w * W), 1, W - x), h = clamp(Math.round(sel.h * H), 1, H - y);
  const c = document.createElement('canvas');
  c.width = w; c.height = h;
  const ctx = c.getContext('2d');
  ctx.imageSmoothingEnabled = false;
  ctx.drawImage(img, x, y, w, h, 0, 0, w, h);
  return c;
}

/** Teil eines Canvas (Zeilen top..bottom) als neues Canvas. */
function sliceCanvas(c, top) {
  const s = document.createElement('canvas');
  s.width = c.width; s.height = c.height - top;
  s.getContext('2d').drawImage(c, 0, top, c.width, s.height, 0, 0, c.width, s.height);
  return s;
}

function canvasPng(c) {
  return new Promise((resolve, reject) => c.toBlob((b) => {
    if (!b) return reject(new Error('Bild konnte nicht kodiert werden (zu groß?)'));
    b.arrayBuffer().then(resolve, reject);
  }, 'image/png'));
}

function freeCanvas(c) { if (c) { c.width = 0; c.height = 0; } }

async function buildPdf() {
  const { PDFDocument } = PDFLib;
  const continuous = contBox.checked;
  const removeOverlap = continuous && overlapBox.checked;
  const doc = await PDFDocument.create();
  doc.setCreator('Screenshotter');
  doc.setProducer('Screenshotter (pdf-lib)');
  doc.setTitle(sanitizeName(nameField.value).slice(0, -4));

  const prog = $('progress');
  prog.classList.remove('hidden');
  prog.max = items.length; prog.value = 0;

  const contentW = A4_W - 2 * MARGIN, contentH = A4_H - 2 * MARGIN, bottom = A4_H - MARGIN;
  let page = null, cursorY = MARGIN, prevSig = null, prevScale = 0;
  const newPage = () => { page = doc.addPage([A4_W, A4_H]); cursorY = MARGIN; };
  let skipped = 0, joined = 0, trimmed = 0;

  for (let i = 0; i < items.length; i++) {
    setStatus(`Verarbeite Bild ${i + 1} von ${items.length} …`);
    const c = await cropToCanvas(items[i]);
    if (!continuous) {
      const png = await doc.embedPng(await canvasPng(c));
      const h = c.height * A4_W / c.width;
      const p = doc.addPage([A4_W, h]);
      p.drawImage(png, { x: 0, y: 0, width: A4_W, height: h });
    } else {
      if (!page) newPage();
      const sig = removeOverlap ? rowSignature(c) : null;
      const overlap = sig && prevSig ? findOverlap(prevSig, sig) : 0;
      prevSig = sig;
      const scale = Math.min(contentW / c.width, contentH / c.height);
      const drawW = c.width * scale;
      const x = MARGIN + (contentW - drawW) / 2;
      const sameScale = Math.abs(scale - prevScale) < 1e-6;
      prevScale = scale;

      if (overlap >= c.height && sameScale) {
        skipped++; // reine Wiederholung
      } else {
        const restH = (c.height - overlap) * scale;
        if (overlap > 0 && cursorY > MARGIN && sameScale && cursorY + restH <= bottom + 0.5) {
          const part = sliceCanvas(c, overlap);
          const png = await doc.embedPng(await canvasPng(part));
          freeCanvas(part);
          page.drawImage(png, { x, y: A4_H - cursorY - restH, width: drawW, height: restH });
          cursorY += restH;
          joined++;
        } else {
          // Bei erkannter Überlappung an einer weißen Lücke oberhalb der Überlappungsgrenze beginnen:
          // so steht nichts angeschnitten am Seitenanfang und es wiederholt sich möglichst wenig.
          const start = overlap > 0 ? whiteGapAtOrAbove(sig, overlap) : 0;
          const part = start > 0 ? sliceCanvas(c, start) : c;
          const partH = part.height * scale;
          const gap = cursorY > MARGIN ? GAP : 0;
          if (cursorY + gap + partH > bottom + 0.5) newPage(); else cursorY += gap;
          const png = await doc.embedPng(await canvasPng(part));
          if (part !== c) { freeCanvas(part); trimmed++; }
          page.drawImage(png, { x, y: A4_H - cursorY - partH, width: drawW, height: partH });
          cursorY += partH;
        }
      }
    }
    freeCanvas(c);
    prog.value = i + 1;
    await new Promise((r) => setTimeout(r, 0)); // Oberfläche aktualisieren lassen
  }

  setStatus('PDF wird geschrieben …');
  const bytes = await doc.save();
  prog.classList.add('hidden');
  return { bytes, pages: doc.getPageCount(), skipped, joined, trimmed };
}

$('build').onclick = async () => {
  if (!items.length) return;
  const btn = $('build');
  btn.disabled = true;
  $('resultRow').classList.add('hidden');
  try {
    const r = await buildPdf();
    const name = sanitizeName(nameField.value);
    pdfFile = new File([r.bytes], name, { type: 'application/pdf' });
    if (pdfUrl) URL.revokeObjectURL(pdfUrl);
    pdfUrl = URL.createObjectURL(pdfFile);
    const mb = (r.bytes.length / 1048576).toFixed(1);
    let msg = `„${name}“ erstellt: ${r.pages} Seite(n), ${mb} MB.`;
    if (r.joined) msg += ` ${r.joined}× Überlappung entfernt.`;
    if (r.trimmed) msg += ` ${r.trimmed}× Wiederholung am Seitenanfang gekürzt.`;
    if (r.skipped) msg += ` ${r.skipped} doppelte(s) Bild(er) ausgelassen.`;
    setStatus(msg, 'ok');
    $('resultRow').classList.remove('hidden');
    $('share').classList.toggle('hidden', !(navigator.canShare && navigator.canShare({ files: [pdfFile] })));
  } catch (err) {
    console.error(err);
    $('progress').classList.add('hidden');
    setStatus('Fehler beim Erstellen: ' + (err && err.message ? err.message : err), 'error');
  } finally {
    btn.disabled = false;
  }
};

$('share').onclick = async () => {
  if (!pdfFile) return;
  try {
    await navigator.share({ files: [pdfFile], title: pdfFile.name });
  } catch (err) {
    if (err && err.name !== 'AbortError') setStatus('Teilen nicht möglich: ' + err.message, 'error');
  }
};
$('download').onclick = () => {
  if (!pdfUrl) return;
  const a = document.createElement('a');
  a.href = pdfUrl; a.download = pdfFile.name;
  document.body.append(a); a.click(); a.remove();
};
$('view').onclick = () => { if (pdfUrl) window.open(pdfUrl, '_blank'); };

// ---------------------------------------------------------------------------------------------
// Offline-Unterstützung
// ---------------------------------------------------------------------------------------------
if ('serviceWorker' in navigator && location.protocol === 'https:') {
  navigator.serviceWorker.register('sw.js').catch(() => {});
}
