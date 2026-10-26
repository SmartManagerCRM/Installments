// Regenerates samples/*.smilic signed with the published TEST key (fixed clock, so output is reproducible except the random license id).
const fs = require('node:fs'); const path = require('node:path');
const S = require('../smilic-core.js'); const PEM = require('../test-key.js');
(async () => {
  const signer = await S.signerFromPem(PEM); const store = S.makeStore(S.memoryStorage());
  const nowMs = Date.UTC(2026, 0, 15, 12); const base = { customerName: 'Boutique Test JS', customerRef: 'TEST', nowMs, tz: 'Africa/Tunis', request: '0000-1111-2222-3333-4444' };
  const out = {
    'js-lifetime.smilic': { kind: 'lifetime', start: '2026-01-01' },
    'js-duration-valid-until-2099.smilic': { kind: 'duration', start: '2026-01-01', expiry: '2099-12-31' },
    'js-duration-expired-2021.smilic': { kind: 'duration', start: '2020-01-01', expiry: '2021-01-01' },
  };
  for (const [name, o] of Object.entries(out)) fs.writeFileSync(path.join(__dirname, '../samples', name), (await S.issueLicense(signer, store, { ...base, ...o })).fileJson);
  console.log('samples written; key id', signer.keyId);
})();
