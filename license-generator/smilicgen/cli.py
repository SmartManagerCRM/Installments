"""Command-line interface (the GUI is the default entry point: `python -m smilicgen`)."""
from __future__ import annotations

import argparse
import getpass
import os
import sys
from datetime import date, datetime
from pathlib import Path

from . import __version__
from .formats import human_summary, parse_license_file
from .history import History
from .keys import (KeyError_, backup_key, default_home, generate_key, is_encrypted, key_file, load_signer, public_pem)
from .service import IssueError, issue_license, reissue_license
from .verify import verify_license


def _passphrase(path: Path, confirm: bool = False) -> str | None:
    if not is_encrypted(path):
        return None
    env = os.environ.get("SMI_LICGEN_PASSPHRASE")
    if env:
        return env
    return getpass.getpass("Signing-key passphrase: ")


def _signer(args):
    path = Path(args.key) if args.key else key_file(Path(args.home) if args.home else None)
    if not path.exists():
        raise KeyError_(f"No signing key at {path}. Run `init-keys` first.")
    return load_signer(path, _passphrase(path))


def _history(args) -> History:
    home = Path(args.home) if args.home else default_home()
    return History(home / "license_history.sqlite")


def _date(s: str) -> date:
    return datetime.strptime(s, "%Y-%m-%d").date()


def _read_request(args) -> str:
    if args.request_file:
        return Path(args.request_file).read_text(encoding="utf-8")
    return args.request


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="smilicgen", description="SmartManager Installments license generator (offline).")
    p.add_argument("--version", action="version", version=__version__)
    p.add_argument("--home", help="data directory (default: ~/.smartmanager-licensegen or %%APPDATA%%\\SmartManagerLicenseGen)")
    p.add_argument("--key", help="explicit signing-key file (default: <home>/signing_key.pem)")
    sub = p.add_subparsers(dest="cmd")

    sub.add_parser("gui", help="open the graphical interface (default)")
    sub.add_parser("init-keys", help="create the signing key pair (once)")
    e = sub.add_parser("export-public", help="write the public key PEM to embed in the Android app")
    e.add_argument("--out", required=True)
    b = sub.add_parser("backup-key", help="copy the passphrase-protected private key to a backup location")
    b.add_argument("--out", required=True)

    i = sub.add_parser("issue", help="issue a new license")
    i.add_argument("--customer", required=True)
    i.add_argument("--customer-ref")
    i.add_argument("--type", choices=["lifetime", "duration"], required=True)
    i.add_argument("--start", type=_date, default=date.today())
    i.add_argument("--expiry", type=_date, help="last valid day (inclusive) for --type duration")
    i.add_argument("--tz", default="Africa/Tunis")
    i.add_argument("--notes")
    g = i.add_mutually_exclusive_group(required=True)
    g.add_argument("--request", help="activation request text (SMI-ACT-1:...) or device id")
    g.add_argument("--request-file")
    i.add_argument("--out", help="output file (default: ./<license-id>-r1.smilic)")

    r = sub.add_parser("reissue", help="issue a replacement license for a new device")
    r.add_argument("--license-id", required=True)
    r.add_argument("--reason", required=True)
    r.add_argument("--verified", action="store_true", help="I verified the customer's identity and request")
    r.add_argument("--start", type=_date)
    r.add_argument("--expiry", type=_date)
    g2 = r.add_mutually_exclusive_group(required=True)
    g2.add_argument("--request")
    g2.add_argument("--request-file")
    r.add_argument("--out")

    l = sub.add_parser("list", help="search the issuance history")
    l.add_argument("query", nargs="?", default="")
    s = sub.add_parser("show", help="show a license summary from a file")
    s.add_argument("file")
    v = sub.add_parser("verify", help="verify a license file against a public key")
    v.add_argument("file")
    v.add_argument("--public-key", required=True)
    v.add_argument("--device", required=True)
    return p


def _fmt_ts(ts):
    return "-" if ts is None else datetime.fromtimestamp(ts).strftime("%Y-%m-%d")


def main(argv=None) -> int:
    args = build_parser().parse_args(argv)
    try:
        if args.cmd in (None, "gui"):
            from .gui import run_gui
            run_gui(Path(args.home) if args.home else None, Path(args.key) if args.key else None)
            return 0
        if args.cmd == "init-keys":
            home = Path(args.home) if args.home else default_home()
            pw = os.environ.get("SMI_LICGEN_PASSPHRASE") or getpass.getpass("Choose a passphrase (>= 10 chars): ")
            if not os.environ.get("SMI_LICGEN_PASSPHRASE") and getpass.getpass("Repeat passphrase: ") != pw:
                print("Passphrases differ", file=sys.stderr)
                return 2
            signer = generate_key(home, pw)
            print(f"Key created. key id: {signer.key_id}\n  private (KEEP SECRET, BACK UP): {home / 'signing_key.pem'}\n  public : {home / 'public_key.pem'}")
            return 0
        if args.cmd == "export-public":
            signer = _signer(args)
            Path(args.out).write_text(public_pem(signer.public_key), encoding="ascii")
            print(f"Public key {signer.key_id} written to {args.out}")
            return 0
        if args.cmd == "backup-key":
            backup_key(Path(args.key) if args.key else key_file(Path(args.home) if args.home else None), Path(args.out))
            print(f"Encrypted private key copied to {args.out}. Store it OFFLINE, separate from the original.")
            return 0
        if args.cmd == "issue":
            signer, hist = _signer(args), _history(args)
            lic = issue_license(signer, hist, customer_name=args.customer, customer_ref=args.customer_ref, kind=args.type,
                                start=args.start, expiry=args.expiry, tz=args.tz, request_text=_read_request(args), notes=args.notes)
            out = Path(args.out or lic.suggested_filename)
            out.write_text(lic.file_json, encoding="utf-8")
            print(lic.summary + f"\n\nLicense file written to {out}")
            return 0
        if args.cmd == "reissue":
            signer, hist = _signer(args), _history(args)
            lic = reissue_license(signer, hist, license_id=args.license_id, request_text=_read_request(args), reason=args.reason,
                                  verified_by_owner=args.verified, start=args.start, expiry=args.expiry)
            out = Path(args.out or lic.suggested_filename)
            out.write_text(lic.file_json, encoding="utf-8")
            print(lic.summary + f"\n\nReplacement license written to {out}")
            return 0
        if args.cmd == "list":
            hist = _history(args)
            for r in hist.search(args.query):
                print(f"{r.license_id} r{r.revision}  {r.kind:8} {_fmt_ts(r.start_at)}..{_fmt_ts(r.expires_at):10}  {r.customer_name}  [{r.device_id}]")
            return 0
        if args.cmd == "show":
            payload, *_ = parse_license_file(Path(args.file).read_text(encoding="utf-8"))
            print(human_summary(payload))
            return 0
        if args.cmd == "verify":
            status, _ = verify_license(Path(args.file).read_text(encoding="utf-8"), Path(args.public_key).read_text(), args.device)
            print(status)
            return 0 if status == "VALID" else 1
    except (KeyError_, IssueError, ValueError, OSError) as exc:
        print(f"Error: {exc}", file=sys.stderr)
        return 1
    return 0
