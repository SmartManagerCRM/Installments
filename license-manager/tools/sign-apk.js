#!/usr/bin/env node
// Command-line twin of the "Customer app" tab:  node tools/sign-apk.js --in unsigned.apk --out signed.apk (--key my.smiapp --pass "..." | --throwaway)
'use strict';
const fs = require('fs');
const A = require('../apk-signer.js');
const args = Object.fromEntries(process.argv.slice(2).map((a, i, all) => a.startsWith('--') ? [a.slice(2), all[i + 1] && !all[i + 1].startsWith('--') ? all[i + 1] : true] : []).filter((x) => x.length));
(async () => {
  if (!args.in || !args.out) throw new Error('usage: sign-apk.js --in unsigned.apk --out signed.apk (--key file.smiapp --pass "..." | --throwaway)');
  const key = args.throwaway ? await A.createAppKey({ commonName: 'CI throwaway', years: 1 }) : await A.unlockAppKey(fs.readFileSync(args.key, 'utf8'), args.pass);
  const signed = await A.signApk(new Uint8Array(fs.readFileSync(args.in)), key);
  const check = await A.verifyApk(signed);
  fs.writeFileSync(args.out, signed);
  console.log(`signed ${args.out}  size=${signed.length}  sha256=${A.hex(await A.sha256(signed))}  signer-cert-sha256=${check.certFingerprint}`);
})().catch((e) => { console.error('ERROR: ' + e.message); process.exit(1); });
