'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const nodeCrypto = require('node:crypto');
const A = require('../apk-signer.js');

const { makeZip } = require('./zip-fixture.js');
const smallApk = () => makeZip([['AndroidManifest.xml', Buffer.from('manifest')], ['classes.dex', Buffer.from('dex bytes')]]);
const bigApk = () => makeZip([['classes.dex', nodeCrypto.randomBytes(2.5 * 1024 * 1024)], ['resources.arsc', Buffer.from('r')]]);

test('certificate is a valid self-signed SHA256withRSA X.509 certificate valid ~30 years', async () => {
  const key = await A.createAppKey();
  const x = new nodeCrypto.X509Certificate(Buffer.from(key.certDer));
  assert.equal(x.verify(x.publicKey), true);
  assert.match(x.subject, /CN=SmartManager Installments/);
  assert.equal(x.publicKey.asymmetricKeyDetails.modulusLength, 2048);
  const years = (new Date(x.validTo) - new Date(x.validFrom)) / (365.25 * 864e5);
  assert.ok(years > 29.9 && years < 30.1, 'validity ' + years);
  assert.equal(nodeCrypto.createHash('sha256').update(key.certDer).digest('hex'), key.fingerprint);
});

test('sign then verify (small and multi-chunk APK); contents are untouched', async () => {
  const key = await A.createAppKey();
  for (const apk of [smallApk(), bigApk()]) {
    const signed = await A.signApk(apk, key);
    assert.ok(signed.length > apk.length);
    const r = await A.verifyApk(signed);
    assert.equal(r.certFingerprint, key.fingerprint);
    assert.deepEqual(Buffer.from(signed.subarray(0, 100)), Buffer.from(apk.subarray(0, 100)));
  }
});

test('any change to the signed APK breaks verification', async () => {
  const key = await A.createAppKey();
  const signed = await A.signApk(bigApk(), key);
  for (const at of [10, 1_000_000, signed.length - 30]) {
    const bad = signed.slice(); bad[at] ^= 1;
    await assert.rejects(() => A.verifyApk(bad));
  }
});

test('refuses already-signed and non-APK input', async () => {
  const key = await A.createAppKey();
  const once = await A.signApk(smallApk(), key);
  await assert.rejects(() => A.signApk(once, key), /already signed/);
  await assert.rejects(() => A.signApk(new Uint8Array(100), key), /not an APK/);
});

test('app key file: round trip, wrong passphrase, short passphrase, tampered file', async () => {
  const key = await A.createAppKey();
  const file = await A.protectAppKey(key, 'correct horse battery');
  assert.ok(!file.includes(Buffer.from(key.pkcs8.subarray(40, 80)).toString('base64')), 'private key must not appear in clear');
  const back = await A.unlockAppKey(file, 'correct horse battery');
  assert.equal(back.fingerprint, key.fingerprint);
  const signed = await A.signApk(smallApk(), back);
  assert.equal((await A.verifyApk(signed)).certFingerprint, key.fingerprint);
  await assert.rejects(() => A.unlockAppKey(file, 'wrong wrong wrong'), /wrong passphrase/);
  await assert.rejects(() => A.protectAppKey(key, 'short'), /at least 10/);
  const o = JSON.parse(file); o.fp = '00'; await assert.rejects(() => A.unlockAppKey(JSON.stringify(o), 'correct horse battery'));
  await assert.rejects(() => A.unlockAppKey('{"fmt":"x"}', 'x'), /not a SmartManager app key/);
});

test('same key signs two releases with the same publisher certificate', async () => {
  const key = await A.createAppKey();
  const a = await A.verifyApk(await A.signApk(smallApk(), key));
  const b = await A.verifyApk(await A.signApk(bigApk(), key));
  assert.equal(a.certFingerprint, b.certFingerprint);
});
