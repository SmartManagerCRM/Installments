/*
 * SmartManager Installments - license manager core (browser + Node >= 18).
 * Produces exactly the license format the Android app verifies: ECDSA P-256 / SHA-256 ("ES256"), DER signature over the
 * canonical payload bytes. All crypto is the platform's WebCrypto - no dependencies, no network.
 */
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(globalThis.crypto);
  else root.SMI = factory(root.crypto);
})(typeof self !== 'undefined' ? self : this, function (crypto) {
  'use strict';
  const subtle = crypto.subtle;
  const enc = new TextEncoder();
  const dec = new TextDecoder();

  const PRODUCT_ID = 'smartmanager-installments';
  const SCHEMA_VERSION = 1;
  const ACTIVATION_PREFIX = 'SMI-ACT-1:';
  const LICENSE_TEXT_PREFIX = 'SMI-LIC-1.';
  const KEY_FILE_FORMAT = 'SMI-KEY-1';
  const PBKDF2_ITERATIONS = 600000;
  const ID_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'; // no look-alike characters

  class IssueError extends Error {}

  // ---------------------------------------------------------------- bytes / encoding
  function b64uEncode(bytes) {
    let s = '';
    for (const b of bytes) s += String.fromCharCode(b);
    return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  }
  function b64uDecode(text) {
    let t = String(text).trim().replace(/-/g, '+').replace(/_/g, '/');
    while (t.length % 4) t += '=';
    const s = atob(t);
    const out = new Uint8Array(s.length);
    for (let i = 0; i < s.length; i++) out[i] = s.charCodeAt(i);
    return out;
  }
  const hex = (bytes) => Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
  async function sha256(bytes) { return new Uint8Array(await subtle.digest('SHA-256', bytes)); }
  function randomBytes(n) { const a = new Uint8Array(n); crypto.getRandomValues(a); return a; }

  /** Sorted keys, no whitespace, UTF-8: the signature covers exactly these bytes (same as the Python generator). */
  function canon(v) {
    if (v === null || typeof v !== 'object') return JSON.stringify(v);
    if (Array.isArray(v)) return '[' + v.map(canon).join(',') + ']';
    return '{' + Object.keys(v).sort().map((k) => JSON.stringify(k) + ':' + canon(v[k])).join(',') + '}';
  }

  function pemToBytes(pem) {
    const body = pem.split('\n').filter((l) => !l.startsWith('-----')).join('').replace(/\s+/g, '');
    const s = atob(body);
    const out = new Uint8Array(s.length);
    for (let i = 0; i < s.length; i++) out[i] = s.charCodeAt(i);
    return out;
  }
  function bytesToPem(bytes, label) {
    let s = '';
    for (const b of bytes) s += String.fromCharCode(b);
    const b64 = btoa(s).match(/.{1,64}/g).join('\n');
    return `-----BEGIN ${label}-----\n${b64}\n-----END ${label}-----\n`;
  }

  // ---------------------------------------------------------------- ECDSA signature: WebCrypto raw (r||s) <-> DER
  function rawToDer(raw) {
    const trim = (x) => { let i = 0; while (i < x.length - 1 && x[i] === 0) i++; x = x.slice(i); return x[0] & 0x80 ? Uint8Array.from([0, ...x]) : x; };
    const r = trim(raw.slice(0, 32)), s = trim(raw.slice(32, 64));
    const body = [0x02, r.length, ...r, 0x02, s.length, ...s];
    return Uint8Array.from([0x30, body.length, ...body]);
  }
  function derToRaw(der) {
    let i = 2;
    if (der[1] & 0x80) i = 2 + (der[1] & 0x7f);
    const read = () => { if (der[i++] !== 0x02) throw new Error('bad DER'); const len = der[i++]; let x = der.slice(i, i + len); i += len; while (x.length > 32 && x[0] === 0) x = x.slice(1); const out = new Uint8Array(32); out.set(x, 32 - x.length); return out; };
    const r = read(), s = read();
    return Uint8Array.from([...r, ...s]);
  }

  // ---------------------------------------------------------------- keys
  async function keyIdOf(spki) { return hex(await sha256(spki)).slice(0, 8); }

  /** Builds a signer from PKCS#8 bytes. */
  async function signerFromPkcs8(pkcs8) {
    let priv;
    try { priv = await subtle.importKey('pkcs8', pkcs8, { name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign']); }
    catch (e) { throw new IssueError('This is not an unencrypted ECDSA P-256 (PKCS#8) private key.'); }
    const jwk = await subtle.exportKey('jwk', priv);
    const pub = await subtle.importKey('jwk', { kty: jwk.kty, crv: jwk.crv, x: jwk.x, y: jwk.y, ext: true }, { name: 'ECDSA', namedCurve: 'P-256' }, true, ['verify']);
    const spki = new Uint8Array(await subtle.exportKey('spki', pub));
    const keyId = await keyIdOf(spki);
    return {
      keyId, spki, publicPem: bytesToPem(spki, 'PUBLIC KEY'),
      async sign(bytes) { return rawToDer(new Uint8Array(await subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, priv, bytes))); },
      toString() { return `Signer(${keyId})`; }, // never leak key material into logs
    };
  }
  async function generateKey() {
    const pair = await subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify']);
    const pkcs8 = new Uint8Array(await subtle.exportKey('pkcs8', pair.privateKey));
    return { pkcs8, signer: await signerFromPkcs8(pkcs8) };
  }
  async function signerFromPem(pem) {
    if (/BEGIN ENCRYPTED PRIVATE KEY/.test(pem)) throw new IssueError('This PEM is passphrase-encrypted (made by the Python tool). Use a .smikey file from this tool, or the TEST key.');
    if (/BEGIN EC PRIVATE KEY/.test(pem)) throw new IssueError('Old-style EC key (SEC1) is not supported; use a PKCS#8 "BEGIN PRIVATE KEY" file.');
    return signerFromPkcs8(pemToBytes(pem));
  }

  async function deriveAesKey(pass, salt, iterations) {
    const base = await subtle.importKey('raw', enc.encode(pass), 'PBKDF2', false, ['deriveKey']);
    return subtle.deriveKey({ name: 'PBKDF2', salt, iterations, hash: 'SHA-256' }, base, { name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
  }
  /** Passphrase-encrypted private key file (.smikey): PBKDF2-SHA256 (600k) + AES-256-GCM, header bound as AAD. */
  async function encryptKey(pkcs8, passphrase, keyId) {
    if (!passphrase || passphrase.length < 10) throw new IssueError('Use a passphrase of at least 10 characters.');
    const salt = randomBytes(16), iv = randomBytes(12);
    const head = { fmt: KEY_FILE_FORMAT, kdf: 'PBKDF2-SHA256', iterations: PBKDF2_ITERATIONS, kid: keyId, salt: b64uEncode(salt), iv: b64uEncode(iv) };
    const ct = new Uint8Array(await subtle.encrypt({ name: 'AES-GCM', iv, additionalData: enc.encode(canon(head)) }, await deriveAesKey(passphrase, salt, PBKDF2_ITERATIONS), pkcs8));
    return canon({ ...head, ct: b64uEncode(ct) });
  }
  async function decryptKey(fileText, passphrase) {
    let o;
    try { o = JSON.parse(fileText); } catch (e) { throw new IssueError('Not a SmartManager key file.'); }
    if (o.fmt !== KEY_FILE_FORMAT) throw new IssueError('Not a SmartManager key file.');
    const { ct, ...head } = o;
    try {
      const pkcs8 = new Uint8Array(await subtle.decrypt({ name: 'AES-GCM', iv: b64uDecode(o.iv), additionalData: enc.encode(canon(head)) },
        await deriveAesKey(passphrase || '', b64uDecode(o.salt), o.iterations), b64uDecode(ct)));
      return signerFromPkcs8(pkcs8);
    } catch (e) {
      if (e instanceof IssueError) throw e;
      throw new IssueError('Could not unlock the key (wrong passphrase or damaged file).');
    }
  }

  // ---------------------------------------------------------------- dates / time zones (explicit and testable)
  function tzOffsetMs(tz, epochMs) {
    const dtf = new Intl.DateTimeFormat('en-US', { timeZone: tz, hourCycle: 'h23', year: 'numeric', month: 'numeric', day: 'numeric', hour: 'numeric', minute: 'numeric', second: 'numeric' });
    const p = {};
    for (const { type, value } of dtf.formatToParts(new Date(epochMs))) p[type] = +value;
    return Date.UTC(p.year, p.month - 1, p.day, p.hour, p.minute, p.second) - Math.floor(epochMs / 1000) * 1000;
  }
  /** Local wall-clock time in `tz` -> UTC epoch seconds. */
  function zonedEpoch(ymd, h, mi, s, tz) {
    const [y, m, d] = ymd.split('-').map(Number);
    const guess = Date.UTC(y, m - 1, d, h, mi, s);
    let t = guess - tzOffsetMs(tz, guess);
    const off2 = tzOffsetMs(tz, t);
    t = guess - off2;
    return Math.floor(t / 1000);
  }
  const startOfDayEpoch = (ymd, tz) => zonedEpoch(ymd, 0, 0, 0, tz);
  const endOfDayEpoch = (ymd, tz) => zonedEpoch(ymd, 23, 59, 59, tz);
  function addDays(ymd, n) {
    const [y, m, d] = ymd.split('-').map(Number);
    return new Date(Date.UTC(y, m - 1, d + n)).toISOString().slice(0, 10);
  }
  function todayIn(tz, nowMs) {
    const p = {};
    for (const { type, value } of new Intl.DateTimeFormat('en-US', { timeZone: tz, year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(new Date(nowMs))) p[type] = value;
    return `${p.year}-${p.month}-${p.day}`;
  }
  function fmtTs(ts, tz) {
    if (ts === null || ts === undefined) return '-';
    return new Intl.DateTimeFormat('sv-SE', { timeZone: tz, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' }).format(new Date(ts * 1000)) + ' ' + tz;
  }
  /** 'Valid for N days' = works until the END of the day that is N days after the start date. */
  function expiryFromDays(startYmd, days) {
    if (!Number.isInteger(days) || days < 1 || days > 3660) throw new IssueError('Number of days must be between 1 and 3660');
    return addDays(startYmd, days);
  }

  // ---------------------------------------------------------------- activation request / device id
  const normalizeDeviceId = (id) => id.toUpperCase().replace(/[^A-Z0-9]/g, '');
  const formatDeviceId = (id) => normalizeDeviceId(id).match(/.{1,4}/g).join('-');

  /** Accepts the full `SMI-ACT-1:...` request or a bare device id; returns the formatted device id. */
  async function parseActivationRequest(text) {
    const t = String(text || '').trim();
    if (!t) throw new IssueError('Paste the Device ID or the activation request');
    let device = t;
    if (t.startsWith(ACTIVATION_PREFIX)) {
      try {
        const body = t.slice(ACTIVATION_PREFIX.length);
        const dot = body.lastIndexOf('.');
        const raw = b64uDecode(body.slice(0, dot));
        if (hex(await sha256(raw)).slice(0, 6) !== body.slice(dot + 1)) throw new IssueError('Activation request checksum mismatch (was it copied completely?)');
        const data = JSON.parse(dec.decode(raw));
        if (data.product !== PRODUCT_ID) throw new IssueError('Activation request is for a different product');
        device = data.deviceId;
      } catch (e) {
        if (e instanceof IssueError) throw e;
        throw new IssueError('Malformed activation request');
      }
    }
    const n = normalizeDeviceId(device);
    if (!/^[0-9A-F]{20}$/.test(n)) throw new IssueError('Device ID must be 20 hexadecimal characters (XXXX-XXXX-XXXX-XXXX-XXXX)');
    return formatDeviceId(n);
  }
  async function makeActivationRequest(deviceId, nowSec) {
    const raw = enc.encode(canon({ v: 1, product: PRODUCT_ID, deviceId, app: '1.0.0', at: nowSec }));
    return ACTIVATION_PREFIX + b64uEncode(raw) + '.' + hex(await sha256(raw)).slice(0, 6);
  }

  // ---------------------------------------------------------------- register (history)
  function makeStore(storage, key = 'smi_licenses_v1') {
    const read = () => { try { return JSON.parse(storage.getItem(key) || '[]'); } catch (e) { return []; } };
    return {
      all: read,
      add(rec) { const rows = read(); rows.push(rec); storage.setItem(key, JSON.stringify(rows)); },
      revisions(id) { return read().filter((r) => r.licenseId === id).sort((a, b) => a.revision - b.revision); },
      latest(id) { const r = this.revisions(id); return r.length ? r[r.length - 1] : null; },
      /** One row per license id (its latest revision), newest first, optionally filtered. */
      latestAll(text = '') {
        const q = text.trim().toLowerCase();
        const best = new Map();
        for (const r of read()) if (!best.has(r.licenseId) || best.get(r.licenseId).revision < r.revision) best.set(r.licenseId, r);
        return [...best.values()].filter((r) => !q || [r.licenseId, r.customerName, r.customerRef, r.deviceId, r.notes].some((v) => (v || '').toLowerCase().includes(q)))
          .sort((a, b) => b.issuedAt - a.issuedAt);
      },
      replaceAll(rows) { storage.setItem(key, JSON.stringify(rows)); },
    };
  }
  function memoryStorage() { const m = new Map(); return { getItem: (k) => (m.has(k) ? m.get(k) : null), setItem: (k, v) => m.set(k, String(v)) }; }

  // ---------------------------------------------------------------- issuing
  function newLicenseId(nowMs) {
    const d = new Date(nowMs);
    let r = '';
    for (const b of randomBytes(6)) r += ID_ALPHABET[b % ID_ALPHABET.length];
    return `SMI-${d.getUTCFullYear()}${String(d.getUTCMonth() + 1).padStart(2, '0')}-${r}`;
  }

  function humanSummary(p) {
    const tz = p.tz || 'UTC';
    return [
      'SmartManager Installments - License summary',
      `License ID : ${p.licenseId} (revision ${p.revision || 1})`,
      `Customer   : ${p.customerName}` + (p.customerRef ? `  [${p.customerRef}]` : ''),
      `Type       : ${p.type === 'lifetime' ? 'Lifetime' : 'Custom duration'}`,
      `Device     : ${p.deviceId}`,
      `Issued     : ${fmtTs(p.issuedAt, tz)}`,
      `Valid from : ${fmtTs(p.startAt, tz)}`,
      `Valid until: ${p.type === 'lifetime' ? 'never (lifetime)' : fmtTs(p.expiresAt, tz)}`,
      `Features   : ${(p.features || []).join(', ')}`,
      `Time zone  : ${tz}`,
    ].join('\n');
  }

  async function build(signer, store, o) {
    const name = (o.customerName || '').trim();
    if (!name) throw new IssueError('Customer name is required');
    if (o.kind !== 'lifetime' && o.kind !== 'duration') throw new IssueError("License type must be 'lifetime' or 'duration'");
    const deviceId = await parseActivationRequest(o.request);
    const tz = o.tz || 'Africa/Tunis';
    const nowMs = o.nowMs ?? Date.now();
    let startAt, expiresAt = null;
    try {
      startAt = startOfDayEpoch(o.start, tz);
      if (o.kind === 'duration') {
        if (!o.expiry) throw new IssueError('A custom-duration license needs an expiry date');
        if (o.expiry < o.start) throw new IssueError('Expiry date must not be before the start date');
        expiresAt = endOfDayEpoch(o.expiry, tz);
      } else if (o.expiry) throw new IssueError('A lifetime license must not have an expiry date');
    } catch (e) {
      if (e instanceof RangeError) throw new IssueError(`Unknown time zone: ${tz}`);
      throw e;
    }
    const payload = {
      v: SCHEMA_VERSION, product: PRODUCT_ID, licenseId: o.licenseId, revision: o.revision, customerName: name,
      customerRef: (o.customerRef || '').trim() || null, type: o.kind, issuedAt: Math.floor(nowMs / 1000), startAt, deviceId,
      features: ['core'], tz,
    };
    if (o.kind === 'duration') payload.expiresAt = expiresAt;
    const payloadBytes = enc.encode(canon(payload));
    const sig = await signer.sign(payloadBytes);
    const fileJson = canon({ fmt: 'SMI-LIC', v: SCHEMA_VERSION, alg: 'ES256', kid: signer.keyId, payload: b64uEncode(payloadBytes), sig: b64uEncode(sig) });
    const record = {
      licenseId: o.licenseId, revision: o.revision, customerName: name, customerRef: payload.customerRef, kind: o.kind, deviceId,
      issuedAt: payload.issuedAt, startAt, expiresAt, keyId: signer.keyId, tz, notes: (o.notes || '').trim() || null,
      reissueReason: o.reissueReason || null, licenseText: fileJson,
    };
    store.add(record);
    return {
      fileJson, textForm: LICENSE_TEXT_PREFIX + b64uEncode(enc.encode(fileJson)), summary: humanSummary(payload), record,
      suggestedFilename: `${o.licenseId}-r${o.revision}.smilic`,
    };
  }

  /** New license. Validity: kind 'lifetime', or 'duration' with `days` (from `start`, default today) or an explicit `expiry` (YYYY-MM-DD). */
  async function issueLicense(signer, store, o) {
    const nowMs = o.nowMs ?? Date.now();
    const tz = o.tz || 'Africa/Tunis';
    let start = o.start;
    try { start = start || todayIn(tz, nowMs); } catch (e) { throw new IssueError(`Unknown time zone: ${tz}`); }
    let expiry = o.expiry;
    if (o.kind === 'duration' && !expiry && o.days !== undefined && o.days !== null) expiry = expiryFromDays(start, Number(o.days));
    return build(signer, store, { ...o, nowMs, tz, start, expiry, licenseId: newLicenseId(nowMs), revision: 1 });
  }

  /** New code for the SAME phone (revision + 1): renew, change length, or switch lifetime <-> subscription. No new request needed. */
  async function renewLicense(signer, store, o) {
    const prev = store.latest(o.licenseId);
    if (!prev) throw new IssueError(`Unknown license id ${o.licenseId}`);
    const nowMs = o.nowMs ?? Date.now();
    const start = o.start || todayIn(prev.tz, nowMs);
    let expiry = null;
    if (o.kind === 'duration') {
      expiry = o.expiry || (o.days !== undefined && o.days !== null ? expiryFromDays(start, Number(o.days)) : null);
      if (!expiry) throw new IssueError('Enter the number of days (or an expiry date)');
    }
    return build(signer, store, { customerName: prev.customerName, customerRef: prev.customerRef, kind: o.kind, start, expiry, tz: prev.tz,
      request: prev.deviceId, notes: prev.notes, reissueReason: (o.reason || 'renewal').trim(), nowMs, licenseId: prev.licenseId, revision: prev.revision + 1 });
  }

  /** Replacement for a NEW phone after the owner verified the customer. Terms are copied from the latest revision. */
  async function reissueLicense(signer, store, o) {
    if (!o.verifiedByOwner) throw new IssueError("Confirm that you verified the customer's identity and replacement request");
    if (!(o.reason || '').trim()) throw new IssueError("A reason for the replacement is required (e.g. 'phone lost')");
    const prev = store.latest(o.licenseId);
    if (!prev) throw new IssueError(`Unknown license id ${o.licenseId}`);
    const nowMs = o.nowMs ?? Date.now();
    const fmtDay = (ts) => new Intl.DateTimeFormat('en-CA', { timeZone: prev.tz, year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date(ts * 1000));
    return build(signer, store, { customerName: prev.customerName, customerRef: prev.customerRef, kind: prev.kind, start: fmtDay(prev.startAt),
      expiry: prev.expiresAt ? fmtDay(prev.expiresAt) : null, tz: prev.tz, request: o.request, notes: prev.notes, reissueReason: o.reason.trim(), nowMs,
      licenseId: prev.licenseId, revision: prev.revision + 1 });
  }

  function licenseStatus(rec, nowSec = Math.floor(Date.now() / 1000)) {
    if (rec.startAt - 86400 > nowSec) return 'NOT YET VALID';
    if (rec.expiresAt !== null && rec.expiresAt !== undefined && nowSec > rec.expiresAt) return 'EXPIRED';
    return 'ACTIVE';
  }
  function validToText(rec) {
    if (rec.expiresAt === null || rec.expiresAt === undefined) return 'lifetime';
    return new Intl.DateTimeFormat('en-CA', { timeZone: rec.tz, year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date(rec.expiresAt * 1000));
  }

  /** UTF-8 CSV with BOM (Excel reads Arabic); cells starting with = + - @ are neutralised against formula injection. */
  function toCsv(rows) {
    const safe = (v) => { v = v === null || v === undefined ? '' : String(v); return '=+-@\t\r'.includes(v[0] || ' ') && v ? "'" + v : v; };
    const cell = (v) => '"' + safe(v).replace(/"/g, '""') + '"';
    const day = (ts, tz) => new Intl.DateTimeFormat('en-CA', { timeZone: tz, year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date(ts * 1000));
    const lines = [['license_id', 'revision', 'customer', 'order_reference', 'type', 'device_id', 'issued_at_utc', 'valid_from', 'valid_to', 'key_id', 'note', 'reason'].map(cell).join(',')];
    for (const r of rows) lines.push([r.licenseId, r.revision, r.customerName, r.customerRef, r.kind, r.deviceId, new Date(r.issuedAt * 1000).toISOString().slice(0, 16).replace('T', ' '),
      day(r.startAt, r.tz), validToText(r), r.keyId, r.notes, r.reissueReason].map(cell).join(','));
    return '﻿' + lines.join('\r\n') + '\r\n';
  }

  // ---------------------------------------------------------------- verification (same checks as the app)
  async function verifyLicense(text, publicPem, deviceId, nowSec = Math.floor(Date.now() / 1000)) {
    let doc, payloadBytes, sig, payload;
    try {
      let t = String(text).trim().replace(/^﻿/, '').trim();
      if (t.startsWith(LICENSE_TEXT_PREFIX)) t = dec.decode(b64uDecode(t.slice(LICENSE_TEXT_PREFIX.length)));
      doc = JSON.parse(t);
      if (doc.fmt !== 'SMI-LIC' || doc.alg !== 'ES256') throw new Error('format');
      payloadBytes = b64uDecode(doc.payload);
      sig = b64uDecode(doc.sig);
    } catch (e) { return { status: 'MALFORMED' }; }
    let spki;
    try { spki = pemToBytes(publicPem); } catch (e) { return { status: 'MALFORMED' }; }
    if ((await keyIdOf(spki)) !== doc.kid) return { status: 'UNKNOWN_KEY' };
    const pub = await subtle.importKey('spki', spki, { name: 'ECDSA', namedCurve: 'P-256' }, false, ['verify']);
    let ok = false;
    try { ok = await subtle.verify({ name: 'ECDSA', hash: 'SHA-256' }, pub, derToRaw(sig), payloadBytes); } catch (e) { ok = false; }
    if (!ok) return { status: 'INVALID_SIGNATURE' };
    try { payload = JSON.parse(dec.decode(payloadBytes)); } catch (e) { return { status: 'MALFORMED' }; }
    if (payload.v > SCHEMA_VERSION) return { status: 'UNSUPPORTED_VERSION', payload };
    if (payload.product !== PRODUCT_ID) return { status: 'WRONG_PRODUCT', payload };
    if (normalizeDeviceId(payload.deviceId) !== normalizeDeviceId(deviceId || '')) return { status: 'WRONG_DEVICE', payload };
    if (payload.type === 'duration') {
      if (nowSec < payload.startAt) return { status: 'NOT_YET_VALID', payload };
      if (nowSec > payload.expiresAt) return { status: 'EXPIRED', payload };
    } else if (nowSec < payload.startAt - 86400) return { status: 'NOT_YET_VALID', payload };
    return { status: 'VALID', payload };
  }

  return {
    IssueError, PRODUCT_ID, canon, b64uEncode, b64uDecode, hex, sha256, rawToDer, derToRaw, pemToBytes, bytesToPem,
    generateKey, signerFromPkcs8, signerFromPem, encryptKey, decryptKey, keyIdOf,
    startOfDayEpoch, endOfDayEpoch, addDays, todayIn, expiryFromDays, fmtTs,
    parseActivationRequest, makeActivationRequest, normalizeDeviceId, formatDeviceId,
    makeTextForm: (fileJson) => LICENSE_TEXT_PREFIX + b64uEncode(enc.encode(fileJson)),
    makeStore, memoryStorage, issueLicense, renewLicense, reissueLicense, licenseStatus, validToText, toCsv, humanSummary, verifyLicense,
  };
});
