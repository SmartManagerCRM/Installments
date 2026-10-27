// Browser end-to-end test of index.html (needs Playwright + Chromium):  node tests/e2e/ui.e2e.js [screenshotDir]
'use strict';
const { chromium } = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs'); const os = require('node:os'); const path = require('node:path');
const S = require('../../smilic-core.js');
const shots = process.argv[2];

(async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext({ acceptDownloads: true, viewport: { width: 1100, height: 900 } });
  const page = await ctx.newPage();
  const errors = []; page.on('pageerror', (e) => errors.push(e.message)); page.on('console', (m) => m.type() === 'error' && errors.push(m.text()));
  await page.goto('file://' + path.resolve(__dirname, '../../index.html'));
  const out = () => page.textContent('#out');
  const waitOut = async (re) => { await page.waitForFunction((src) => new RegExp(src).test(document.getElementById('out').textContent), re.source, { timeout: 20000 }); return out(); };
  const DEVICE = '0000-1111-2222-3333-4444';
  const pubPem = (await S.signerFromPem(require('../../test-key.js'))).publicPem;

  // --- issue a lifetime license with the TEST key from just a Device ID
  await page.fill('#req', DEVICE); await page.fill('#cust', 'Boutique Elnour'); await page.fill('#ref', 'INV-1042');
  let [dl] = await Promise.all([page.waitForEvent('download'), page.click('#btnIssue')]);
  const f1 = path.join(os.tmpdir(), 'e2e1.smilic'); await dl.saveAs(f1);
  assert.match(dl.suggestedFilename(), /^Boutique_Elnour-SMI-\d{6}-[A-Z0-9]{6}-r1\.smilic$/);
  assert.equal((await S.verifyLicense(fs.readFileSync(f1, 'utf8'), pubPem, DEVICE)).status, 'VALID');
  assert.match(await out(), /LICENSE ISSUED/);
  if (shots) await page.screenshot({ path: path.join(shots, 'js-issue.png') });

  // --- subscription from a full activation request (30 days)
  const req = await S.makeActivationRequest('AAAA-BBBB-CCCC-DDDD-EEEE', 1);
  await page.fill('#req', req); await page.fill('#cust', 'Magasin Tunis'); await page.check('input[name=kind][value=duration]'); await page.fill('#days', '30');
  [dl] = await Promise.all([page.waitForEvent('download'), page.click('#btnIssue')]);
  const f2 = path.join(os.tmpdir(), 'e2e2.smilic'); await dl.saveAs(f2);
  const v2 = await S.verifyLicense(fs.readFileSync(f2, 'utf8'), pubPem, 'AAAA-BBBB-CCCC-DDDD-EEEE');
  assert.equal(v2.status, 'VALID'); assert.equal(v2.payload.type, 'duration');

  // --- validation message instead of a license
  await page.fill('#cust', ''); await page.click('#btnIssue');
  assert.match(await waitOut(/Customer name is required/), /Customer name is required/); assert.equal(await page.getAttribute('#out', 'class'), 'err');
  await page.fill('#cust', 'x'); await page.fill('#req', 'rubbish'); await page.click('#btnIssue'); assert.match(await waitOut(/Device ID must be/), /Device ID must be 20 hexadecimal/);

  // --- register: statuses, search, renew
  await page.click('#tabs button[data-t=register]');
  assert.equal(await page.locator('#regBody tr').count(), 2);
  assert.match(await page.textContent('#regCount'), /2 license\(s\): ACTIVE 2/);
  await page.fill('#search', 'tunis'); assert.equal(await page.locator('#regBody tr').count(), 1); await page.fill('#search', '');
  await page.click('#regBody tr:has-text("Magasin Tunis")'); await page.click('#btnRenew');
  await page.fill('#rdays', '60');
  [dl] = await Promise.all([page.waitForEvent('download'), page.click('#rGo')]);
  const f3 = path.join(os.tmpdir(), 'e2e3.smilic'); await dl.saveAs(f3);
  const v3 = await S.verifyLicense(fs.readFileSync(f3, 'utf8'), pubPem, 'AAAA-BBBB-CCCC-DDDD-EEEE');
  assert.equal(v3.status, 'VALID'); assert.equal(v3.payload.revision, 2);
  assert.match(await page.textContent('#regBody'), /Magasin Tunis[\s\S]*ACTIVE/);
  await page.click('#btnHistory'); assert.match(await waitOut(/r1[\s\S]*r2/), /r1[\s\S]*r2/);
  if (shots) await page.screenshot({ path: path.join(shots, 'js-register.png') });
  [dl] = await Promise.all([page.waitForEvent('download'), page.click('#btnCsv')]);
  assert.match(fs.readFileSync(await dl.path(), 'utf8'), /Boutique Elnour/);

  // --- production key: create, issue with passphrase, wrong passphrase refused, public key export
  await page.click('#tabs button[data-t=keys]');
  await page.fill('#newPass', 'correct horse battery');
  [dl] = await Promise.all([page.waitForEvent('download'), page.click('#btnCreateKey')]);
  const keyFile = path.join(os.tmpdir(), 'e2e.smikey'); await dl.saveAs(keyFile);
  assert.match(await page.textContent('#keyStatus'), /Production key present/);
  assert.ok(!fs.readFileSync(keyFile, 'utf8').includes('BEGIN'));  // encrypted, not a PEM
  await page.click('#tabs button[data-t=issue]'); await page.selectOption('#keySel', 'prod');
  await page.fill('#req', DEVICE); await page.fill('#cust', 'Prod Shop'); await page.check('input[name=kind][value=lifetime]');
  await page.fill('#pass', 'wrong passphrase!!'); await page.click('#btnIssue'); assert.match(await waitOut(/wrong passphrase/), /wrong passphrase/);
  await page.fill('#pass', 'correct horse battery');
  [dl] = await Promise.all([page.waitForEvent('download'), page.click('#btnIssue')]);
  const f4 = path.join(os.tmpdir(), 'e2e4.smilic'); await dl.saveAs(f4);
  await page.click('#tabs button[data-t=keys]');
  [dl] = await Promise.all([page.waitForEvent('download'), page.click('#btnExportPub')]);
  const prodPub = fs.readFileSync(await dl.path(), 'utf8');
  assert.match(prodPub, /BEGIN PUBLIC KEY/);
  assert.equal((await S.verifyLicense(fs.readFileSync(f4, 'utf8'), prodPub, DEVICE)).status, 'VALID');
  assert.equal((await S.verifyLicense(fs.readFileSync(f4, 'utf8'), pubPem, DEVICE)).status, 'UNKNOWN_KEY'); // test key must not accept it

  // --- verify tab
  await page.click('#tabs button[data-t=verify]'); await page.click('#btnVTest');
  await page.fill('#vLic', fs.readFileSync(f1, 'utf8')); await page.fill('#vDev', DEVICE); await page.click('#btnVerify');
  assert.match(await waitOut(/Result: VALID/), /Result: VALID/);
  await page.fill('#vDev', 'FFFF-FFFF-FFFF-FFFF-FFFF'); await page.click('#btnVerify'); assert.match(await waitOut(/WRONG_DEVICE/), /WRONG_DEVICE/);

  // --- customer app tab: create app key, sign an "unsigned APK", result verifies
  const { makeZip } = require('../zip-fixture.js'); const A = require('../../apk-signer.js');
  await page.click('#tabs button[data-t=app]');
  await page.fill('#appPass', 'short'); await page.click('#btnAppCreate'); assert.match(await waitOut(/at least 10 characters/), /at least 10/);
  await page.fill('#appPass', 'my long app passphrase');
  [dl] = await Promise.all([page.waitForEvent('download'), page.click('#btnAppCreate')]);
  const fk = path.join(os.tmpdir(), 'e2e.smiapp'); await dl.saveAs(fk);
  assert.match(await waitOut(/App key created/), /KEEP TWO COPIES/);
  await page.click('#btnAppCreate'); assert.match(await waitOut(/already exists/), /Do NOT create a second/);
  await page.click('#btnApkSign'); assert.match(await waitOut(/Choose the unsigned APK first/), /unsigned APK first/);
  const apkPath = path.join(os.tmpdir(), 'e2e-customer-unsigned.apk'); fs.writeFileSync(apkPath, makeZip([['AndroidManifest.xml', Buffer.from('m')], ['classes.dex', Buffer.alloc(1500000, 7)]]));
  const [chooser] = await Promise.all([page.waitForEvent('filechooser'), page.click('#btnApkPick')]); await chooser.setFiles(apkPath);
  await page.fill('#appPass', 'wrong wrong wrong'); await page.click('#btnApkSign'); assert.match(await waitOut(/wrong passphrase/), /wrong passphrase/);
  await page.fill('#appPass', 'my long app passphrase');
  [dl] = await Promise.all([page.waitForEvent('download'), page.click('#btnApkSign')]);
  assert.equal(dl.suggestedFilename(), 'e2e-customer-signed.apk');
  const signedPath = path.join(os.tmpdir(), 'e2e-signed.apk'); await dl.saveAs(signedPath);
  const chk = await A.verifyApk(new Uint8Array(fs.readFileSync(signedPath)));
  assert.equal(chk.certFingerprint, (await A.unlockAppKey(fs.readFileSync(fk, 'utf8'), 'my long app passphrase')).fingerprint);
  if (shots) await page.screenshot({ path: path.join(shots, 'js-customer-app.png') });

  // --- data persists across reload (localStorage)
  await page.reload(); await page.click('#tabs button[data-t=register]');
  assert.equal(await page.locator('#regBody tr').count(), 3);
  assert.deepEqual(errors, [], 'no console/page errors');
  await browser.close();
  console.log('UI e2e OK');
})().catch((e) => { console.error(e); process.exit(1); });
