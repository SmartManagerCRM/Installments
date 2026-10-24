import base64
import io
import json
import os
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stdout, redirect_stderr
from datetime import date, datetime, timezone
from pathlib import Path

from cryptography.hazmat.primitives import serialization

from smilicgen import cli
from smilicgen.formats import (b64u, make_activation_request, parse_activation_request, parse_license_file, to_text_form,
                               canonical_json, start_of_day_epoch, end_of_day_epoch)
from smilicgen.history import History
from smilicgen.keys import KeyError_, backup_key, generate_key, load_signer, public_pem, inside_git_worktree
from smilicgen.service import IssueError, issue_license, reissue_license
from smilicgen.verify import verify_license

ROOT = Path(__file__).resolve().parents[1]
DEVICE = "0000-1111-2222-3333-4444"
NOW = datetime(2026, 1, 15, 12, 0, tzinfo=timezone.utc)
PASS = "correct horse battery"


class Base(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.home = Path(self.tmp.name) / "home"
        self.signer = generate_key(self.home, PASS)
        self.pub = public_pem(self.signer.public_key)
        self.hist = History(":memory:")
        self.req = make_activation_request(DEVICE, "1.0.0", 1)

    def tearDown(self):
        self.tmp.cleanup()

    def issue(self, **kw):
        args = dict(customer_name="Shop", kind="lifetime", start=date(2026, 1, 1), request_text=self.req, now=NOW)
        args.update(kw)
        return issue_license(self.signer, self.hist, **args)


class IssueTests(Base):
    def test_lifetime_license_verifies_and_never_expires(self):
        lic = self.issue()
        payload, *_ = parse_license_file(lic.file_json)
        self.assertEqual(payload["type"], "lifetime")
        self.assertNotIn("expiresAt", payload)
        self.assertEqual(verify_license(lic.file_json, self.pub, DEVICE, int(NOW.timestamp()))[0], "VALID")
        self.assertEqual(verify_license(lic.file_json, self.pub, DEVICE, int(NOW.timestamp()) + 60 * 365 * 86400)[0], "VALID")

    def test_duration_boundaries_use_the_configured_time_zone(self):
        lic = self.issue(kind="duration", start=date(2026, 3, 1), expiry=date(2026, 3, 31), tz="Africa/Tunis")
        p, *_ = parse_license_file(lic.file_json)
        # Tunis is UTC+1 all year: 2026-03-01 00:00 local == 2026-02-28 23:00 UTC
        self.assertEqual(p["startAt"], int(datetime(2026, 2, 28, 23, 0, tzinfo=timezone.utc).timestamp()))
        self.assertEqual(p["expiresAt"], int(datetime(2026, 3, 31, 22, 59, 59, tzinfo=timezone.utc).timestamp()))
        status = lambda t: verify_license(lic.file_json, self.pub, DEVICE, t)[0]
        self.assertEqual(status(p["startAt"] - 1), "NOT_YET_VALID")
        self.assertEqual(status(p["startAt"]), "VALID")
        self.assertEqual(status(p["expiresAt"]), "VALID")       # last second of the last valid day
        self.assertEqual(status(p["expiresAt"] + 1), "EXPIRED")

    def test_same_dates_in_different_zones_give_different_instants(self):
        a = start_of_day_epoch(date(2026, 6, 1), "Africa/Tunis")
        b = start_of_day_epoch(date(2026, 6, 1), "Asia/Riyadh")
        self.assertEqual(a - b, 2 * 3600)  # Riyadh UTC+3 vs Tunis UTC+1
        self.assertGreater(end_of_day_epoch(date(2026, 6, 1), "UTC"), start_of_day_epoch(date(2026, 6, 1), "UTC"))

    def test_validation_errors(self):
        with self.assertRaises(IssueError): self.issue(customer_name="  ")
        with self.assertRaises(IssueError): self.issue(kind="duration")                       # no expiry
        with self.assertRaises(IssueError): self.issue(kind="duration", expiry=date(2025, 1, 1))  # before start
        with self.assertRaises(IssueError): self.issue(expiry=date(2030, 1, 1))              # lifetime with expiry
        with self.assertRaises(IssueError): self.issue(request_text="not a device")
        with self.assertRaises(IssueError): self.issue(tz="Mars/Olympus")
        with self.assertRaises(IssueError): self.issue(kind="forever")

    def test_tampering_is_detected(self):
        lic = self.issue(kind="duration", expiry=date(2026, 12, 31))
        doc = json.loads(lic.file_json)
        payload = json.loads(base64.urlsafe_b64decode(doc["payload"] + "=="))
        payload["expiresAt"] += 10 * 365 * 86400
        doc["payload"] = b64u(canonical_json(payload))
        self.assertEqual(verify_license(json.dumps(doc), self.pub, DEVICE, int(NOW.timestamp()))[0], "INVALID_SIGNATURE")

    def test_other_device_and_wrong_key(self):
        lic = self.issue()
        self.assertEqual(verify_license(lic.file_json, self.pub, "AAAA-BBBB-CCCC-DDDD-EEEE", int(NOW.timestamp()))[0], "WRONG_DEVICE")
        other = generate_key(Path(self.tmp.name) / "other", PASS)
        self.assertEqual(verify_license(lic.file_json, public_pem(other.public_key), DEVICE, int(NOW.timestamp()))[0], "UNKNOWN_KEY")
        self.assertEqual(verify_license("garbage", self.pub, DEVICE)[0], "MALFORMED")

    def test_text_form_roundtrip(self):
        lic = self.issue()
        self.assertEqual(verify_license(lic.text_form, self.pub, DEVICE, int(NOW.timestamp()))[0], "VALID")
        self.assertTrue(to_text_form(lic.file_json).startswith("SMI-LIC-1."))

    def test_unicode_customer_name(self):
        lic = self.issue(customer_name="متجر النور – Élégance")
        self.assertEqual(parse_license_file(lic.file_json)[0]["customerName"], "متجر النور – Élégance")
        self.assertEqual(verify_license(lic.file_json, self.pub, DEVICE, int(NOW.timestamp()))[0], "VALID")


class RequestTests(unittest.TestCase):
    def test_parse(self):
        req = make_activation_request(DEVICE)
        self.assertEqual(parse_activation_request(req), DEVICE)
        self.assertEqual(parse_activation_request("00001111222233334444"), DEVICE)
        self.assertEqual(parse_activation_request(" 0000-1111-2222-3333-4444 \n"), DEVICE)

    def test_rejects_bad(self):
        req = make_activation_request(DEVICE)
        for bad in ("", "xyz", req[:-1] + ("a" if req[-1] != "a" else "b"), "SMI-ACT-1:abc", "0000-1111"):
            with self.assertRaises(ValueError, msg=bad):
                parse_activation_request(bad)


class ReissueTests(Base):
    def test_replacement_workflow(self):
        first = self.issue(kind="duration", start=date(2026, 1, 1), expiry=date(2026, 12, 31), customer_ref="C-7")
        new_device = "AAAA-BBBB-CCCC-DDDD-EEEE"
        with self.assertRaises(IssueError):  # not verified
            reissue_license(self.signer, self.hist, license_id=first.record.license_id, request_text=new_device, reason="lost", verified_by_owner=False)
        with self.assertRaises(IssueError):  # no reason
            reissue_license(self.signer, self.hist, license_id=first.record.license_id, request_text=new_device, reason=" ", verified_by_owner=True)
        with self.assertRaises(IssueError):
            reissue_license(self.signer, self.hist, license_id="SMI-NOPE", request_text=new_device, reason="x", verified_by_owner=True)
        second = reissue_license(self.signer, self.hist, license_id=first.record.license_id, request_text=new_device, reason="phone lost", verified_by_owner=True, now=NOW)
        self.assertEqual(second.record.revision, 2)
        self.assertEqual(second.record.license_id, first.record.license_id)
        self.assertEqual(second.record.customer_ref, "C-7")
        p1, p2 = parse_license_file(first.file_json)[0], parse_license_file(second.file_json)[0]
        self.assertEqual((p1["startAt"], p1["expiresAt"]), (p2["startAt"], p2["expiresAt"]))  # same validity
        self.assertEqual(verify_license(second.file_json, self.pub, new_device, int(NOW.timestamp()))[0], "VALID")
        self.assertEqual(verify_license(first.file_json, self.pub, new_device, int(NOW.timestamp()))[0], "WRONG_DEVICE")
        self.assertEqual([r.revision for r in self.hist.revisions(first.record.license_id)], [1, 2])
        self.assertEqual(self.hist.latest_revision(first.record.license_id).reissue_reason, "phone lost")


class HistoryTests(Base):
    def test_search(self):
        self.issue(customer_name="Alpha Electro", customer_ref="A1")
        self.issue(customer_name="Beta Meubles", customer_ref="B2")
        self.assertEqual(len(self.hist.search("")), 2)
        self.assertEqual([r.customer_name for r in self.hist.search("alpha")], ["Alpha Electro"])
        self.assertEqual([r.customer_ref for r in self.hist.search("B2")], ["B2"])
        self.assertEqual(len(self.hist.search(DEVICE)), 2)
        self.assertEqual(self.hist.search("nothing"), [])

    def test_history_holds_no_key_material(self):
        self.issue()
        dump = "\n".join(self.hist.db.iterdump())
        self.assertNotIn("PRIVATE KEY", dump)


class KeyTests(unittest.TestCase):
    def test_key_is_encrypted_and_unlockable(self):
        with tempfile.TemporaryDirectory() as t:
            s = generate_key(Path(t) / "k", PASS)
            raw = (Path(t) / "k" / "signing_key.pem").read_bytes()
            self.assertIn(b"ENCRYPTED PRIVATE KEY", raw)
            self.assertEqual(load_signer(Path(t) / "k" / "signing_key.pem", PASS).key_id, s.key_id)
            with self.assertRaises(KeyError_):
                load_signer(Path(t) / "k" / "signing_key.pem", "wrong passphrase")
            self.assertEqual(oct(os.stat(Path(t) / "k" / "signing_key.pem").st_mode & 0o777), "0o600") if os.name == "posix" else None
            self.assertNotIn("PRIVATE", repr(s))

    def test_refuses_overwrite_weak_passphrase_and_git_worktree(self):
        with tempfile.TemporaryDirectory() as t:
            generate_key(Path(t) / "k", PASS)
            with self.assertRaises(KeyError_): generate_key(Path(t) / "k", PASS)
            with self.assertRaises(KeyError_): generate_key(Path(t) / "k2", "short")
            (Path(t) / "repo" / ".git").mkdir(parents=True)
            self.assertTrue(inside_git_worktree(Path(t) / "repo" / "keys"))
            with self.assertRaises(KeyError_): generate_key(Path(t) / "repo" / "keys", PASS)

    def test_backup_roundtrip_and_refuses_plaintext(self):
        with tempfile.TemporaryDirectory() as t:
            s = generate_key(Path(t) / "k", PASS)
            backup_key(Path(t) / "k" / "signing_key.pem", Path(t) / "bk.pem")
            self.assertEqual(load_signer(Path(t) / "bk.pem", PASS).key_id, s.key_id)
            plain = Path(t) / "plain.pem"
            plain.write_bytes(s._key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
            with self.assertRaises(KeyError_): backup_key(plain, Path(t) / "x.pem")


class RepositoryHygieneTests(unittest.TestCase):
    def test_no_private_keys_outside_test_keys(self):
        repo = ROOT.parent
        offenders = []
        for p in repo.rglob("*"):
            if not p.is_file() or ".git" in p.parts or "build" in p.parts or ".gradle" in p.parts or p.stat().st_size > 2_000_000:
                continue
            if "test-keys" in p.parts and p.parent.name == "test-keys":
                continue
            if p.suffix in {".class", ".jar", ".apk", ".png", ".sqlite", ".pyc"} or "__pycache__" in p.parts:
                continue
            try:
                text = p.read_text(errors="ignore")
            except OSError:
                continue
            if "-----BEGIN PRIVATE KEY-----" in text or "-----BEGIN EC PRIVATE KEY-----" in text or "-----BEGIN ENCRYPTED PRIVATE KEY-----" in text:
                if p.name != "test_generator.py":
                    offenders.append(str(p))
        self.assertEqual(offenders, [])


class SampleFileTests(unittest.TestCase):
    """The committed samples must verify with the committed TEST public key (cross-checked by the Kotlin core tests too)."""
    pub = (ROOT / "test-keys" / "TEST_ONLY_public_key.pem").read_text()

    def test_samples(self):
        s = ROOT / "samples"
        def st(name, now=int(datetime(2026, 6, 1, tzinfo=timezone.utc).timestamp()), device=DEVICE):
            return verify_license((s / name).read_text(encoding="utf-8"), self.pub, device, now)[0]
        self.assertEqual(st("sample-lifetime.smilic"), "VALID")
        self.assertEqual(st("sample-duration-valid-until-2099.smilic"), "VALID")
        self.assertEqual(st("sample-duration-expired-2021.smilic"), "EXPIRED")
        self.assertEqual(st("sample-duration-not-yet-valid-2098.smilic"), "NOT_YET_VALID")
        self.assertEqual(st("sample-lifetime-other-device.smilic"), "WRONG_DEVICE")


class CliTests(unittest.TestCase):
    def run_cli(self, *argv, env=None):
        old = dict(os.environ)
        os.environ.update(env or {})
        out, err = io.StringIO(), io.StringIO()
        try:
            with redirect_stdout(out), redirect_stderr(err):
                code = cli.main(list(argv))
        finally:
            os.environ.clear(); os.environ.update(old)
        return code, out.getvalue(), err.getvalue()

    def test_end_to_end(self):
        with tempfile.TemporaryDirectory() as t:
            home = str(Path(t) / "h")
            env = {"SMI_LICGEN_PASSPHRASE": PASS}
            code, out, _ = self.run_cli("--home", home, "init-keys", env=env); self.assertEqual(code, 0, out)
            pub = Path(t) / "pub.pem"
            self.assertEqual(self.run_cli("--home", home, "export-public", "--out", str(pub), env=env)[0], 0)
            lic = Path(t) / "l.smilic"
            code, out, err = self.run_cli("--home", home, "issue", "--customer", "Cli Shop", "--type", "duration", "--start", "2026-01-01",
                                          "--expiry", "2099-01-01", "--request", DEVICE, "--out", str(lic), env=env)
            self.assertEqual(code, 0, err)
            self.assertIn("Cli Shop", out)
            self.assertEqual(self.run_cli("verify", str(lic), "--public-key", str(pub), "--device", DEVICE)[0], 0)
            self.assertEqual(self.run_cli("verify", str(lic), "--public-key", str(pub), "--device", "FFFF-FFFF-FFFF-FFFF-FFFF")[0], 1)
            self.assertIn("Cli Shop", self.run_cli("--home", home, "list", "cli")[1])
            self.assertNotEqual(self.run_cli("--home", home, "issue", "--customer", "x", "--type", "lifetime", "--request", "bad", env=env)[0], 0)
            lid = self.run_cli("--home", home, "list")[1].split()[0]
            self.assertNotEqual(self.run_cli("--home", home, "reissue", "--license-id", lid, "--reason", "lost", "--request", "AAAA-BBBB-CCCC-DDDD-EEEE", env=env)[0], 0)  # not verified
            code, *_ = self.run_cli("--home", home, "reissue", "--license-id", lid, "--reason", "lost", "--verified", "--request", "AAAA-BBBB-CCCC-DDDD-EEEE", "--out", str(Path(t) / "r.smilic"), env=env)
            self.assertEqual(code, 0)
            self.assertEqual(self.run_cli("verify", str(Path(t) / "r.smilic"), "--public-key", str(pub), "--device", "AAAA-BBBB-CCCC-DDDD-EEEE")[0], 0)


if __name__ == "__main__":
    unittest.main()
