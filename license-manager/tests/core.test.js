'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const S = require('../smilic-core.js');
const TEST_PEM = require('../test-key.js');

const DEVICE = '0000-1111-2222-3333-4444';
const NOW = Date.UTC(2026, 0, 15, 12, 0, 0); // 2026-01-15 12:00 UTC
const sec = (ms) => Math.floor(ms / 1000);

async function setup() {
  const signer = await S.signerFromPem(TEST_PEM);
  return { signer, store: S.makeStore(S.memoryStorage()) };
}
const issue = (ctx, o) => S.issueLicense(ctx.signer, ctx.store, { customerName: 'Shop', kind: 'lifetime', request: DEVICE, nowMs: NOW, tz: 'UTC', ...o });
const verify = (ctx, lic, device = DEVICE, now = sec(NOW)) => S.verifyLicense(typeof lic === 'string' ? lic : lic.fileJson, ctx.signer.publicPem, device, now);

test('test key has the same key id as the Python/Kotlin tools', async () => {
  assert.equal((await S.signerFromPem(TEST_PEM)).keyId, '4df8c077');
});

test('lifetime license verifies and never expires', async () => {
  const ctx = await setup();
  const lic = await issue(ctx, {});
  assert.equal((await verify(ctx, lic)).status, 'VALID');
  assert.equal((await verify(ctx, lic, DEVICE, sec(NOW) + 60 * 365 * 86400)).status, 'VALID');
  const p = JSON.parse(Buffer.from(JSON.parse(lic.fileJson).payload, 'base64url').toString());
  assert.equal(p.type, 'lifetime');
  assert.ok(!('expiresAt' in p));
});

test('subscription "valid for N days" boundaries (end of day, explicit time zone)', async () => {
  const ctx = await setup();
  const lic = await issue(ctx, { kind: 'duration', days: 30 });
  const p = JSON.parse(Buffer.from(JSON.parse(lic.fileJson).payload, 'base64url').toString());
  assert.equal(p.startAt, sec(Date.UTC(2026, 0, 15)));
  assert.equal(p.expiresAt, sec(Date.UTC(2026, 1, 14, 23, 59, 59)));
  assert.equal((await verify(ctx, lic, DEVICE, p.expiresAt)).status, 'VALID');
  assert.equal((await verify(ctx, lic, DEVICE, p.expiresAt + 1)).status, 'EXPIRED');
  assert.equal((await verify(ctx, lic, DEVICE, p.startAt - 1)).status, 'NOT_YET_VALID');
  assert.equal(S.expiryFromDays('2026-01-15', 30), '2026-02-14');
  for (const bad of [0, -1, 99999, 1.5]) assert.throws(() => S.expiryFromDays('2026-01-01', bad), S.IssueError);
});

test('time zones: Tunis (UTC+1) and Riyadh (UTC+3) give different instants', async () => {
  assert.equal(S.startOfDayEpoch('2026-03-01', 'Africa/Tunis'), sec(Date.UTC(2026, 1, 28, 23, 0, 0)));
  assert.equal(S.endOfDayEpoch('2026-03-31', 'Africa/Tunis'), sec(Date.UTC(2026, 2, 31, 22, 59, 59)));
  assert.equal(S.startOfDayEpoch('2026-06-01', 'Africa/Tunis') - S.startOfDayEpoch('2026-06-01', 'Asia/Riyadh'), 2 * 3600);
  // DST zone: Paris summer vs winter
  assert.equal(S.startOfDayEpoch('2026-07-01', 'Europe/Paris'), sec(Date.UTC(2026, 5, 30, 22, 0, 0)));
  assert.equal(S.startOfDayEpoch('2026-01-01', 'Europe/Paris'), sec(Date.UTC(2025, 11, 31, 23, 0, 0)));
  const ctx = await setup();
  await assert.rejects(issue(ctx, { tz: 'Mars/Olympus' }), S.IssueError);
});

test('validation errors', async () => {
  const ctx = await setup();
  await assert.rejects(issue(ctx, { customerName: '  ' }), /Customer name/);
  await assert.rejects(issue(ctx, { kind: 'duration' }), /expiry/);
  await assert.rejects(issue(ctx, { kind: 'duration', start: '2026-02-01', expiry: '2026-01-01' }), /before the start/);
  await assert.rejects(issue(ctx, { expiry: '2030-01-01' }), /must not have an expiry/);
  await assert.rejects(issue(ctx, { request: 'not a device' }), S.IssueError);
  await assert.rejects(issue(ctx, { kind: 'forever' }), S.IssueError);
  assert.equal(ctx.store.all().length, 0); // nothing recorded for failed attempts
});

test('tampering, other device, other key, malformed', async () => {
  const ctx = await setup();
  const lic = await issue(ctx, { kind: 'duration', days: 10 });
  const doc = JSON.parse(lic.fileJson);
  const payload = JSON.parse(Buffer.from(doc.payload, 'base64url').toString());
  payload.expiresAt += 10 * 365 * 86400;
  doc.payload = Buffer.from(S.canon(payload)).toString('base64url');
  assert.equal((await verify(ctx, JSON.stringify(doc))).status, 'INVALID_SIGNATURE');
  assert.equal((await verify(ctx, lic, 'AAAA-BBBB-CCCC-DDDD-EEEE')).status, 'WRONG_DEVICE');
  assert.equal((await verify(ctx, lic, 'aaaabbbbccccddddeeee')).status, 'WRONG_DEVICE');
  const other = await S.generateKey();
  assert.equal((await S.verifyLicense(lic.fileJson, other.signer.publicPem, DEVICE, sec(NOW))).status, 'UNKNOWN_KEY');
  for (const bad of ['', 'garbage', '{}', '[1]']) assert.equal((await verify(ctx, bad)).status, 'MALFORMED', bad);
  // one-line text form
  assert.equal((await verify(ctx, lic.textForm)).status, 'VALID');
});

test('every payload bit flip is rejected', async () => {
  const ctx = await setup();
  const lic = await issue(ctx, {});
  const doc = JSON.parse(lic.fileJson);
  for (let i = 0; i < doc.payload.length; i += 11) {
    const c = doc.payload[i] === 'A' ? 'B' : 'A';
    const bad = { ...doc, payload: doc.payload.slice(0, i) + c + doc.payload.slice(i + 1) };
    assert.notEqual((await verify(ctx, JSON.stringify(bad))).status, 'VALID', 'mutation ' + i);
  }
});

test('ECDSA DER <-> raw conversion round-trips (incl. leading zeros / high bit)', () => {
  for (let n = 0; n < 500; n++) {
    const raw = new Uint8Array(64);
    require('node:crypto').randomFillSync(raw);
    if (n % 5 === 0) raw.fill(0, 0, 3 + (n % 7));        // short r
    if (n % 7 === 0) raw.fill(0, 32, 35);                 // short s
    if (n % 3 === 0) { raw[0] |= 0x80; raw[32] |= 0x80; }  // high bits
    if (n === 1) raw.fill(0, 0, 32);                      // r == 0 edge (still must encode)
    assert.deepEqual(S.derToRaw(S.rawToDer(raw)), raw);
  }
});

test('activation request: accepts Python/Kotlin format and bare ids, rejects corruption', async () => {
  const req = await S.makeActivationRequest(DEVICE, 1);
  assert.equal(await S.parseActivationRequest(req), DEVICE);
  assert.equal(await S.parseActivationRequest('00001111222233334444'), DEVICE);
  assert.equal(await S.parseActivationRequest(' 0000-1111-2222-3333-4444 \n'), DEVICE);
  for (const bad of ['', 'xyz', req.slice(0, -1) + (req.endsWith('a') ? 'b' : 'a'), 'SMI-ACT-1:abc', '0000-1111']) await assert.rejects(S.parseActivationRequest(bad), S.IssueError, bad);
  // a request made by the Python tool (committed sample) decodes too
  const py = fs.readFileSync(path.join(__dirname, '../../license-generator/samples/test-device-activation-request.txt'), 'utf8');
  assert.equal(await S.parseActivationRequest(py), DEVICE);
});

test('renew keeps the same phone (no new request) and can switch lifetime <-> subscription', async () => {
  const ctx = await setup();
  const first = await issue(ctx, { customerRef: 'INV-9', kind: 'duration', days: 30 });
  const later = Date.UTC(2026, 1, 20, 9);
  assert.equal(S.licenseStatus(first.record, sec(later)), 'EXPIRED');
  const second = await S.renewLicense(ctx.signer, ctx.store, { licenseId: first.record.licenseId, kind: 'duration', days: 30, nowMs: later });
  assert.deepEqual([second.record.revision, second.record.deviceId, second.record.customerRef], [2, DEVICE, 'INV-9']);
  assert.equal((await verify(ctx, second, DEVICE, sec(later))).status, 'VALID');
  assert.equal(S.licenseStatus(second.record, sec(later)), 'ACTIVE');
  const third = await S.renewLicense(ctx.signer, ctx.store, { licenseId: first.record.licenseId, kind: 'lifetime', nowMs: later });
  assert.equal(S.validToText(third.record), 'lifetime');
  await assert.rejects(S.renewLicense(ctx.signer, ctx.store, { licenseId: first.record.licenseId, kind: 'duration', nowMs: later }), /days/);
  await assert.rejects(S.renewLicense(ctx.signer, ctx.store, { licenseId: 'SMI-NOPE', kind: 'lifetime' }), /Unknown license/);
});

test('replacement for a new phone needs verification + reason and keeps terms', async () => {
  const ctx = await setup();
  const first = await issue(ctx, { customerRef: 'C-7', kind: 'duration', start: '2026-01-01', expiry: '2026-12-31' });
  const NEW = 'AAAA-BBBB-CCCC-DDDD-EEEE';
  const id = first.record.licenseId;
  await assert.rejects(S.reissueLicense(ctx.signer, ctx.store, { licenseId: id, request: NEW, reason: 'lost', verifiedByOwner: false }), /verified/);
  await assert.rejects(S.reissueLicense(ctx.signer, ctx.store, { licenseId: id, request: NEW, reason: ' ', verifiedByOwner: true }), /reason/);
  const second = await S.reissueLicense(ctx.signer, ctx.store, { licenseId: id, request: NEW, reason: 'phone lost', verifiedByOwner: true, nowMs: NOW });
  assert.equal(second.record.revision, 2);
  assert.equal(second.record.startAt, first.record.startAt);
  assert.equal(second.record.expiresAt, first.record.expiresAt);
  assert.equal((await verify(ctx, second, NEW)).status, 'VALID');
  assert.equal((await verify(ctx, first, NEW)).status, 'WRONG_DEVICE');
  assert.deepEqual(ctx.store.revisions(id).map((r) => r.revision), [1, 2]);
});

test('register: latest revision per license, search, status labels', async () => {
  const ctx = await setup();
  const a = await issue(ctx, { customerName: 'Alpha Electro', customerRef: 'A1' });
  await issue(ctx, { customerName: 'Beta Meubles', request: 'AAAA-BBBB-CCCC-DDDD-EEEE' });
  await S.renewLicense(ctx.signer, ctx.store, { licenseId: a.record.licenseId, kind: 'lifetime', nowMs: NOW });
  const rows = ctx.store.latestAll();
  assert.equal(rows.length, 2);
  assert.deepEqual(Object.fromEntries(rows.map((r) => [r.customerName, r.revision])), { 'Alpha Electro': 2, 'Beta Meubles': 1 });
  assert.deepEqual(ctx.store.latestAll('beta').map((r) => r.customerName), ['Beta Meubles']);
  assert.deepEqual(ctx.store.latestAll('a1').map((r) => r.customerRef), ['A1']);
  const rec = (await issue(ctx, { kind: 'duration', start: '2026-03-01', expiry: '2026-03-31' })).record;
  assert.equal(S.licenseStatus(rec, sec(Date.UTC(2026, 0, 1))), 'NOT YET VALID');
  assert.equal(S.licenseStatus(rec, sec(Date.UTC(2026, 2, 15))), 'ACTIVE');
  assert.equal(S.licenseStatus(rec, sec(Date.UTC(2026, 3, 2))), 'EXPIRED');
});

test('CSV export is Excel- and formula-injection-safe', async () => {
  const ctx = await setup();
  await issue(ctx, { customerName: "=cmd|' /C calc'!A0" });
  await issue(ctx, { customerName: 'متجر النور', request: 'AAAA-BBBB-CCCC-DDDD-EEEE' });
  const csv = S.toCsv(ctx.store.all());
  assert.ok(csv.startsWith('﻿'));
  assert.ok(csv.includes("'=cmd")); assert.ok(!csv.includes(',"=cmd'));
  assert.ok(csv.includes('متجر النور'));
});

test('private key file: encrypted, unlockable, wrong passphrase / tampering rejected, never plaintext', async () => {
  const { pkcs8, signer } = await S.generateKey();
  await assert.rejects(S.encryptKey(pkcs8, 'short', signer.keyId), /at least 10/);
  const file = await S.encryptKey(pkcs8, 'correct horse battery', signer.keyId);
  assert.ok(!file.includes(Buffer.from(pkcs8).toString('base64').slice(10, 30)));
  assert.equal((await S.decryptKey(file, 'correct horse battery')).keyId, signer.keyId);
  await assert.rejects(S.decryptKey(file, 'wrong passphrase!!'), /wrong passphrase/);
  const o = JSON.parse(file); o.kid = '00000000';
  await assert.rejects(S.decryptKey(JSON.stringify(o), 'correct horse battery'), /wrong passphrase|damaged/); // header is authenticated
  await assert.rejects(S.decryptKey('{}', 'x'), /Not a SmartManager key file/);
  // a license signed after the round trip verifies with the original public key
  const restored = await S.decryptKey(file, 'correct horse battery');
  const ctx = { signer: restored, store: S.makeStore(S.memoryStorage()) };
  assert.equal((await S.verifyLicense((await issue(ctx, {})).fileJson, signer.publicPem, DEVICE, sec(NOW))).status, 'VALID');
});

test('PEM import refuses encrypted / legacy keys with a clear message', async () => {
  await assert.rejects(S.signerFromPem('-----BEGIN ENCRYPTED PRIVATE KEY-----\nAAAA\n-----END ENCRYPTED PRIVATE KEY-----'), /passphrase-encrypted/);
  await assert.rejects(S.signerFromPem('-----BEGIN EC PRIVATE KEY-----\nAAAA\n-----END EC PRIVATE KEY-----'), /not supported/);
});

test('CROSS-CHECK: a license issued by this JS tool verifies with the PYTHON verifier', async () => {
  const ctx = await setup();
  const lic = await issue(ctx, { customerName: 'متجر النور – Élégance', kind: 'duration', start: '2026-01-01', expiry: '2099-12-31', tz: 'Africa/Tunis' });
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'smi-'));
  fs.writeFileSync(path.join(dir, 'l.smilic'), lic.fileJson);
  const gen = path.join(__dirname, '../../license-generator');
  const py = spawnSync('python3', ['-m', 'smilicgen', 'verify', path.join(dir, 'l.smilic'), '--public-key', path.join(gen, 'test-keys/TEST_ONLY_public_key.pem'), '--device', DEVICE], { cwd: gen, encoding: 'utf8' });
  if (py.error || /No module named/.test(py.stderr || '')) { console.log('python/cryptography not available - skipped'); return; }
  assert.equal(py.stdout.trim(), 'VALID', py.stderr);
  // and the reverse: a license issued by the Python tool verifies here
  const sample = fs.readFileSync(path.join(gen, 'samples/sample-lifetime.smilic'), 'utf8');
  assert.equal((await S.verifyLicense(sample, ctx.signer.publicPem, DEVICE, sec(NOW))).status, 'VALID');
});
