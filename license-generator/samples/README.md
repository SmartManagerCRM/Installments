# Sample (TEST) licenses

These files are signed with the **published TEST key** (`../test-keys/TEST_ONLY_signing_key.pem`) and are bound to the fixed
test device id `0000-1111-2222-3333-4444` (see `test-device-activation-request.txt`). They contain **no production secret** and
are accepted only by *debug* builds of the app (and by the automated tests).

| File | Purpose | Expected result for device `0000-1111-2222-3333-4444` |
|---|---|---|
| `sample-lifetime.smilic` | lifetime license | VALID, never expires |
| `sample-duration-valid-until-2099.smilic` | custom duration, ends 2099-12-31 | VALID with remaining days |
| `sample-duration-expired-2021.smilic` | duration that ended 2021-01-01 | EXPIRED (read-only mode) |
| `sample-duration-not-yet-valid-2098.smilic` | starts 2098-01-01 | NOT_YET_VALID |
| `sample-lifetime-other-device.smilic` | bound to `AAAA-BBBB-CCCC-DDDD-EEEE` | WRONG_DEVICE |

To try a debug APK on a real phone you need a license for **that phone's** device id: copy its activation request from the
Activation screen and run

```bash
cd license-generator
python3 -m smilicgen --key test-keys/TEST_ONLY_signing_key.pem issue --customer "My Test Shop" --type lifetime --request "SMI-ACT-1:..." --out mytest.smilic
```

Verify any file from the command line:

```bash
python3 -m smilicgen verify samples/sample-lifetime.smilic --public-key test-keys/TEST_ONLY_public_key.pem --device 0000-1111-2222-3333-4444
```

Never use the test key for customers: anybody can read it in this repository.
