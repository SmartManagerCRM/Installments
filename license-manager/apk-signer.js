/*
 * SmartManager Installments - APK signer (browser + Node >= 18).
 * Signs an UNSIGNED release APK with "APK Signature Scheme v2" (enough for minSdk 26), using the owner's own app key.
 * The app key never leaves the owner's computer. WebCrypto only - no dependencies, no network.
 *
 * App key = RSA-2048 + self-signed X.509 certificate (SHA256withRSA, 30 years, same shape as the default Android debug
 * certificate, which is known to install everywhere). The certificate is created ONCE and stored with the key: Android
 * identifies the publisher by that certificate, so every future release must be signed with the same file.
 */
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(globalThis.crypto);
  else root.SMIAPK = factory(root.crypto);
})(typeof self !== 'undefined' ? self : this, function (crypto) {
  'use strict';
  const subtle = crypto.subtle;
  const enc = new TextEncoder();

  class ApkError extends Error {}

  // ---------------------------------------------------------------- small helpers
  const concat = (...parts) => {
    const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
    let o = 0;
    for (const p of parts) { out.set(p, o); o += p.length; }
    return out;
  };
  const u32 = (n) => { const b = new Uint8Array(4); new DataView(b.buffer).setUint32(0, n, true); return b; };
  const u64 = (n) => { const b = new Uint8Array(8); new DataView(b.buffer).setBigUint64(0, BigInt(n), true); return b; };
  const hex = (bytes) => Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
  const b64 = (bytes) => { let s = ''; for (const b of bytes) s += String.fromCharCode(b); return btoa(s); };
  const unb64 = (t) => { const s = atob(t); const o = new Uint8Array(s.length); for (let i = 0; i < s.length; i++) o[i] = s.charCodeAt(i); return o; };
  const sha256 = async (bytes) => new Uint8Array(await subtle.digest('SHA-256', bytes));

  // ---------------------------------------------------------------- minimal DER writer (for the certificate)
  function derLen(n) {
    if (n < 0x80) return Uint8Array.of(n);
    const bytes = [];
    for (let v = n; v > 0; v = Math.floor(v / 256)) bytes.unshift(v & 255);
    return Uint8Array.of(0x80 | bytes.length, ...bytes);
  }
  const tlv = (tag, ...content) => { const c = concat(...content); return concat(Uint8Array.of(tag), derLen(c.length), c); };
  const seq = (...c) => tlv(0x30, ...c);
  const set = (...c) => tlv(0x31, ...c);
  const derInt = (bytes) => {            // positive INTEGER from big-endian bytes
    let i = 0; while (i < bytes.length - 1 && bytes[i] === 0) i++;
    let b = bytes.slice(i);
    if (b[0] & 0x80) b = concat(Uint8Array.of(0), b);
    return tlv(0x02, b);
  };
  const derOid = (dotted) => {
    const p = dotted.split('.').map(Number);
    const out = [p[0] * 40 + p[1]];
    for (const v of p.slice(2)) {
      const chunk = [v & 0x7f];
      for (let x = Math.floor(v / 128); x > 0; x = Math.floor(x / 128)) chunk.unshift((x & 0x7f) | 0x80);
      out.push(...chunk);
    }
    return tlv(0x06, Uint8Array.from(out));
  };
  const OID_SHA256_RSA = '1.2.840.113549.1.1.11', OID_CN = '2.5.4.3', OID_O = '2.5.4.10', OID_C = '2.5.4.6';
  const utf8 = (s) => tlv(0x0c, enc.encode(s));
  const printable = (s) => tlv(0x13, enc.encode(s));
  const name = (cn, org, country) => seq(
    set(seq(derOid(OID_C), printable(country))),
    set(seq(derOid(OID_O), utf8(org))),
    set(seq(derOid(OID_CN), utf8(cn))));
  const time = (d) => {                  // UTCTime before 2050, GeneralizedTime from 2050 (RFC 5280)
    const p = (n, w = 2) => String(n).padStart(w, '0');
    const y = d.getUTCFullYear();
    const rest = p(d.getUTCMonth() + 1) + p(d.getUTCDate()) + p(d.getUTCHours()) + p(d.getUTCMinutes()) + p(d.getUTCSeconds()) + 'Z';
    return y < 2050 ? tlv(0x17, enc.encode(p(y % 100) + rest)) : tlv(0x18, enc.encode(p(y, 4) + rest));
  };

  /** Self-signed v3 certificate (SHA256withRSA) for the given RSA key pair. */
  async function makeCertificate(publicKeySpki, privateKey, { commonName, organization, country, notBefore, years }) {
    const nb = notBefore || new Date();
    nb.setUTCMilliseconds(0);
    const na = new Date(nb.getTime()); na.setUTCFullYear(na.getUTCFullYear() + (years || 30));
    const serial = crypto.getRandomValues(new Uint8Array(12)); serial[0] &= 0x7f; if (serial[0] === 0) serial[0] = 1;
    const sigAlg = seq(derOid(OID_SHA256_RSA), tlv(0x05));
    const subject = name(commonName, organization, country);
    const tbs = seq(tlv(0xa0, derInt(Uint8Array.of(2))), derInt(serial), sigAlg, subject,
      seq(time(nb), time(na)), subject, publicKeySpki);
    const sig = new Uint8Array(await subtle.sign({ name: 'RSASSA-PKCS1-v1_5' }, privateKey, tbs));
    return seq(tbs, sigAlg, tlv(0x03, Uint8Array.of(0), sig));
  }

  // ---------------------------------------------------------------- app key (create / protect / unlock)
  const FMT = 'SMI-APPKEY-1', ITER = 600000;
  const certFingerprint = async (certDer) => hex(await sha256(certDer));

  async function createAppKey({ commonName = 'SmartManager Installments', organization = 'SmartManager', country = 'TN', years = 30 } = {}) {
    const pair = await subtle.generateKey({ name: 'RSASSA-PKCS1-v1_5', modulusLength: 2048, publicExponent: Uint8Array.of(1, 0, 1), hash: 'SHA-256' }, true, ['sign', 'verify']);
    const spki = new Uint8Array(await subtle.exportKey('spki', pair.publicKey));
    const pkcs8 = new Uint8Array(await subtle.exportKey('pkcs8', pair.privateKey));
    const certDer = await makeCertificate(spki, pair.privateKey, { commonName, organization, country, years });
    return { pkcs8, certDer, fingerprint: await certFingerprint(certDer) };
  }
  async function aesKey(pass, salt, iterations) {
    const base = await subtle.importKey('raw', enc.encode(pass), 'PBKDF2', false, ['deriveKey']);
    return subtle.deriveKey({ name: 'PBKDF2', salt, iterations, hash: 'SHA-256' }, base, { name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
  }
  /** Passphrase-protected backup file (.smiapp, JSON text): certificate in clear, private key AES-256-GCM encrypted. */
  async function protectAppKey(key, passphrase) {
    if (!passphrase || passphrase.length < 10) throw new ApkError('Use a passphrase of at least 10 characters.');
    const salt = crypto.getRandomValues(new Uint8Array(16)), iv = crypto.getRandomValues(new Uint8Array(12));
    const head = { fmt: FMT, fp: key.fingerprint, cert: b64(key.certDer), iterations: ITER, salt: b64(salt), iv: b64(iv) };
    const ct = new Uint8Array(await subtle.encrypt({ name: 'AES-GCM', iv, additionalData: enc.encode(JSON.stringify(head)) }, await aesKey(passphrase, salt, ITER), key.pkcs8));
    return JSON.stringify({ ...head, ct: b64(ct) });
  }
  async function unlockAppKey(fileText, passphrase) {
    let o;
    try { o = JSON.parse(fileText); } catch (e) { throw new ApkError('This is not a SmartManager app key file (.smiapp).'); }
    if (!o || o.fmt !== FMT) throw new ApkError('This is not a SmartManager app key file (.smiapp).');
    const { ct, ...head } = o;
    try {
      const pkcs8 = new Uint8Array(await subtle.decrypt({ name: 'AES-GCM', iv: unb64(o.iv), additionalData: enc.encode(JSON.stringify(head)) },
        await aesKey(passphrase || '', unb64(o.salt), o.iterations), unb64(ct)));
      const certDer = unb64(o.cert);
      return { pkcs8, certDer, fingerprint: await certFingerprint(certDer) };
    } catch (e) { throw new ApkError('Could not unlock the app key (wrong passphrase or damaged file).'); }
  }

  // ---------------------------------------------------------------- APK Signature Scheme v2
  const SIG_RSA_PKCS1_SHA256 = 0x0103, V2_BLOCK_ID = 0x7109871a, MAGIC = enc.encode('APK Sig Block 42'), CHUNK = 1024 * 1024;

  function locate(apk) {
    const dv = new DataView(apk.buffer, apk.byteOffset, apk.byteLength);
    let eocd = -1;
    for (let i = apk.length - 22; i >= Math.max(0, apk.length - 22 - 65535); i--) if (dv.getUint32(i, true) === 0x06054b50) { eocd = i; break; }
    if (eocd < 0) throw new ApkError('This file is not an APK (zip end record not found).');
    if (dv.getUint16(eocd + 20, true) !== 0) throw new ApkError('Unsupported APK (zip comment present).');
    const cdSize = dv.getUint32(eocd + 12, true), cdOffset = dv.getUint32(eocd + 16, true);
    if (cdOffset === 0xffffffff || cdOffset + cdSize !== eocd) throw new ApkError('Unsupported or damaged APK (zip64 or inconsistent central directory).');
    return { eocd, cdSize, cdOffset };
  }
  function hasSigningBlock(apk, cdOffset) {
    if (cdOffset < 24) return false;
    const m = apk.subarray(cdOffset - 16, cdOffset);
    return MAGIC.every((b, i) => m[i] === b);
  }
  async function contentDigest(sections) {
    const chunkDigests = [];
    for (const sec of sections) {
      for (let o = 0; o < sec.length; o += CHUNK) {
        const part = sec.subarray(o, Math.min(o + CHUNK, sec.length));
        chunkDigests.push(await sha256(concat(Uint8Array.of(0xa5), u32(part.length), part)));
      }
    }
    return sha256(concat(Uint8Array.of(0x5a), u32(chunkDigests.length), ...chunkDigests));
  }
  const lp = (bytes) => concat(u32(bytes.length), bytes); // length-prefixed

  /** Signs an unsigned APK. Returns the signed APK bytes. */
  async function signApk(apkBytes, key) {
    const apk = apkBytes instanceof Uint8Array ? apkBytes : new Uint8Array(apkBytes);
    const { eocd, cdOffset } = locate(apk);
    if (hasSigningBlock(apk, cdOffset)) throw new ApkError('This APK is already signed. Use the UNSIGNED release APK.');
    const s1 = apk.subarray(0, cdOffset), s3 = apk.subarray(cdOffset, eocd), s4 = apk.subarray(eocd);
    const digest = await contentDigest([s1, s3, s4]); // unsigned input: EOCD's cd offset already equals the block start

    const priv = await subtle.importKey('pkcs8', key.pkcs8, { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['sign']);
    const jwk = await subtle.exportKey('jwk', await subtle.importKey('pkcs8', key.pkcs8, { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, true, ['sign']));
    const pubKey = await subtle.importKey('jwk', { kty: jwk.kty, n: jwk.n, e: jwk.e, ext: true, alg: 'RS256' }, { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, true, ['verify']);
    const spki = new Uint8Array(await subtle.exportKey('spki', pubKey));

    const signedData = concat(
      lp(lp(concat(u32(SIG_RSA_PKCS1_SHA256), lp(digest)))),   // digests
      lp(lp(key.certDer)),                                      // certificates
      lp(new Uint8Array(0)));                                   // additional attributes
    const signature = new Uint8Array(await subtle.sign({ name: 'RSASSA-PKCS1-v1_5' }, priv, signedData));
    const signer = concat(lp(signedData), lp(lp(concat(u32(SIG_RSA_PKCS1_SHA256), lp(signature)))), lp(spki));
    const value = lp(lp(signer));
    const pair = concat(u64(4 + value.length), u32(V2_BLOCK_ID), value);
    const blockSize = pair.length + 8 + MAGIC.length;
    const block = concat(u64(blockSize), pair, u64(blockSize), MAGIC);

    const newEocd = s4.slice();
    new DataView(newEocd.buffer).setUint32(16, cdOffset + block.length, true);
    return concat(s1, block, s3, newEocd);
  }

  /** Independent re-check of our own output (digest + signature + cert match). The real arbiter is Android's apksigner. */
  async function verifyApk(apkBytes) {
    const apk = apkBytes instanceof Uint8Array ? apkBytes : new Uint8Array(apkBytes);
    const dv = new DataView(apk.buffer, apk.byteOffset, apk.byteLength);
    const { eocd, cdOffset } = locate(apk);
    if (!hasSigningBlock(apk, cdOffset)) throw new ApkError('The APK is not signed.');
    const blockSize = Number(dv.getBigUint64(cdOffset - 24, true));
    const blockStart = cdOffset - blockSize - 8;
    let p = blockStart + 8, value = null;
    while (p < cdOffset - 24) {
      const len = Number(dv.getBigUint64(p, true));
      if (dv.getUint32(p + 8, true) === V2_BLOCK_ID) value = apk.subarray(p + 12, p + 8 + len);
      p += 8 + len;
    }
    if (!value) throw new ApkError('No v2 signature block.');
    const rd = (buf, off) => { const l = new DataView(buf.buffer, buf.byteOffset).getUint32(off, true); return [buf.subarray(off + 4, off + 4 + l), off + 4 + l]; };
    const [signers] = rd(value, 0); const [signer] = rd(signers, 0);
    const [signedData, o1] = rd(signer, 0); const [sigs, o2] = rd(signer, o1); const [spki] = rd(signer, o2);
    const [sig1] = rd(sigs, 0); const [sigBytes] = rd(sig1.subarray(4), 0);
    const [digests] = rd(signedData, 0); const [d1] = rd(digests, 0); const [digest] = rd(d1.subarray(4), 0);
    const expected = await contentDigest([apk.subarray(0, blockStart), apk.subarray(cdOffset, eocd), (() => { const e = apk.slice(eocd); new DataView(e.buffer).setUint32(16, blockStart, true); return e; })()]);
    if (hex(expected) !== hex(digest)) throw new ApkError('APK contents do not match the signature.');
    const pub = await subtle.importKey('spki', spki, { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['verify']);
    if (!(await subtle.verify({ name: 'RSASSA-PKCS1-v1_5' }, pub, sigBytes, signedData))) throw new ApkError('Signature is invalid.');
    const [certs, _] = rd(signedData, rd(signedData, 0)[1]); const [cert] = rd(certs, 0);
    return { ok: true, certFingerprint: await certFingerprint(cert) };
  }

  return { ApkError, createAppKey, protectAppKey, unlockAppKey, signApk, verifyApk, certFingerprint, sha256, hex };
});
