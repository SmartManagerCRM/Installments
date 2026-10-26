// PUBLISHED TEST KEY - public on purpose, protects nothing. Trusted only by DEBUG builds of the app.
// Never use it for customers. (Same key as license-generator/test-keys/TEST_ONLY_signing_key.pem)
(function (root, factory) { if (typeof module === 'object' && module.exports) module.exports = factory(); else root.SMI_TEST_KEY = factory(); })(
  typeof self !== 'undefined' ? self : this,
  function () {
    return `-----BEGIN PRIVATE KEY-----
MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgGuoEUfgD0788mAWg
M89iEVWM3bvf7r0uXPaSkdVkbkyhRANCAATV4gZlGxb1h+p7qYoEZ6wQSpzMDhgZ
QPXFztiMWDKkxwQDmBOzcEcKzUKKq9HpilR1Zl21v1OlwmFXXAnI/sn4
-----END PRIVATE KEY-----
`;
  });
