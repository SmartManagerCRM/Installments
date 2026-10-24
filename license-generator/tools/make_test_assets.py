"""Creates the TEST signing key and sample licenses used by unit tests and DEBUG builds of the Android app.

The test private key is published on purpose: debug builds of the app trust it so developers can try activation.
Release builds NEVER include the test public key (enforced by a Gradle check and a unit test).
Run once; outputs are committed. Never run this to create a production key - use `smilicgen init-keys`.
"""
from datetime import date, datetime, timezone
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec

from smilicgen.formats import make_activation_request
from smilicgen.history import History
from smilicgen.keys import Signer, public_pem
from smilicgen.service import issue_license

ROOT = Path(__file__).resolve().parents[1]
TEST_DEVICE = "0000-1111-2222-3333-4444"
FIXED_NOW = datetime(2026, 1, 15, 12, 0, 0, tzinfo=timezone.utc)


def main() -> None:
    keydir = ROOT / "test-keys"
    keydir.mkdir(exist_ok=True)
    priv_path = keydir / "TEST_ONLY_signing_key.pem"
    if priv_path.exists():
        key = serialization.load_pem_private_key(priv_path.read_bytes(), None)
    else:
        key = ec.generate_private_key(ec.SECP256R1())
        priv_path.write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
    signer = Signer(key)
    (keydir / "TEST_ONLY_public_key.pem").write_text(public_pem(signer.public_key), encoding="ascii")
    (keydir / "README.txt").write_text(
        "TEST KEYS ONLY. The private key here is public on purpose and protects nothing.\n"
        "It is trusted ONLY by DEBUG builds of the Android app and by automated tests.\n"
        "The production key is created by `python -m smilicgen init-keys` and never enters this repository.\n")

    samples = ROOT / "samples"
    samples.mkdir(exist_ok=True)
    hist = History(":memory:")
    req = make_activation_request(TEST_DEVICE, "1.0.0", int(FIXED_NOW.timestamp()))
    (samples / "test-device-activation-request.txt").write_text(req + "\n")
    cases = {
        "sample-lifetime.smilic": dict(kind="lifetime", start=date(2026, 1, 1)),
        "sample-duration-valid-until-2099.smilic": dict(kind="duration", start=date(2026, 1, 1), expiry=date(2099, 12, 31)),
        "sample-duration-expired-2021.smilic": dict(kind="duration", start=date(2020, 1, 1), expiry=date(2021, 1, 1)),
        "sample-duration-not-yet-valid-2098.smilic": dict(kind="duration", start=date(2098, 1, 1), expiry=date(2099, 1, 1)),
    }
    for name, kw in cases.items():
        lic = issue_license(signer, hist, customer_name="Boutique Test / متجر تجريبي", customer_ref="TEST", request_text=req, now=FIXED_NOW, **kw)
        (samples / name).write_text(lic.file_json, encoding="utf-8")
    other = issue_license(signer, hist, customer_name="Other device", kind="lifetime", start=date(2026, 1, 1),
                          request_text="AAAA-BBBB-CCCC-DDDD-EEEE", now=FIXED_NOW)
    (samples / "sample-lifetime-other-device.smilic").write_text(other.file_json, encoding="utf-8")
    print("wrote test assets; key id", signer.key_id)


if __name__ == "__main__":
    main()
