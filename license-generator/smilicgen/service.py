"""License issuing logic shared by the GUI and the CLI."""
from __future__ import annotations

import secrets
from dataclasses import dataclass
from datetime import date, datetime, timezone
from typing import Optional

from .formats import (DEFAULT_FEATURES, LicenseSpec, build_license_file, end_of_day_epoch, human_summary,
                      parse_activation_request, start_of_day_epoch, to_text_form, parse_license_file)
from .history import History, IssuedRecord
from .keys import Signer

_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"  # no look-alike characters


class IssueError(ValueError):
    pass


@dataclass
class IssuedLicense:
    file_json: str
    text_form: str
    summary: str
    record: IssuedRecord

    @property
    def suggested_filename(self) -> str:
        return f"{self.record.license_id}-r{self.record.revision}.smilic"


def new_license_id(now: Optional[datetime] = None) -> str:
    now = now or datetime.now(timezone.utc)
    return f"SMI-{now:%Y%m}-" + "".join(secrets.choice(_ALPHABET) for _ in range(6))


def _build(signer: Signer, *, license_id: str, revision: int, customer_name: str, customer_ref: Optional[str], kind: str,
           start: date, expiry: Optional[date], tz: str, request_text: str, notes: Optional[str],
           reissue_reason: Optional[str], now: datetime, features: Optional[list]) -> IssuedLicense:
    customer_name = customer_name.strip()
    if not customer_name:
        raise IssueError("Customer name is required")
    if kind not in ("lifetime", "duration"):
        raise IssueError("License type must be 'lifetime' or 'duration'")
    try:
        device_id = parse_activation_request(request_text)
    except ValueError as exc:
        raise IssueError(str(exc)) from exc
    try:
        start_at = start_of_day_epoch(start, tz)
        expires_at = None
        if kind == "duration":
            if expiry is None:
                raise IssueError("A custom-duration license needs an expiry date")
            if expiry < start:
                raise IssueError("Expiry date must not be before the start date")
            expires_at = end_of_day_epoch(expiry, tz)
        elif expiry is not None:
            raise IssueError("A lifetime license must not have an expiry date")
    except KeyError as exc:  # unknown time zone
        raise IssueError(f"Unknown time zone: {tz}") from exc
    spec = LicenseSpec(license_id=license_id, revision=revision, customer_name=customer_name,
                       customer_ref=(customer_ref or "").strip() or None, kind=kind, issued_at=int(now.timestamp()),
                       start_at=start_at, expires_at=expires_at, device_id=device_id,
                       features=features or list(DEFAULT_FEATURES), tz=tz)
    file_json = build_license_file(spec, signer.sign, signer.key_id)
    payload, _, _, _ = parse_license_file(file_json)
    rec = IssuedRecord(license_id=license_id, revision=revision, customer_name=spec.customer_name, customer_ref=spec.customer_ref,
                       kind=kind, device_id=device_id, issued_at=spec.issued_at, start_at=start_at, expires_at=expires_at,
                       key_id=signer.key_id, tz=tz, notes=(notes or "").strip() or None, reissue_reason=reissue_reason,
                       license_text=file_json)
    return IssuedLicense(file_json, to_text_form(file_json), human_summary(payload), rec)


def issue_license(signer: Signer, history: History, *, customer_name: str, customer_ref: Optional[str] = None, kind: str,
                  start: date, expiry: Optional[date] = None, tz: str = "Africa/Tunis", request_text: str,
                  notes: Optional[str] = None, now: Optional[datetime] = None, features: Optional[list] = None) -> IssuedLicense:
    now = now or datetime.now(timezone.utc)
    lic = _build(signer, license_id=new_license_id(now), revision=1, customer_name=customer_name, customer_ref=customer_ref,
                 kind=kind, start=start, expiry=expiry, tz=tz, request_text=request_text, notes=notes, reissue_reason=None,
                 now=now, features=features)
    history.add(lic.record)
    return lic


def reissue_license(signer: Signer, history: History, *, license_id: str, request_text: str, reason: str,
                    verified_by_owner: bool, start: Optional[date] = None, expiry: Optional[date] = None,
                    now: Optional[datetime] = None) -> IssuedLicense:
    """Issues a replacement (same license id, revision + 1) bound to the NEW device after the owner verified the customer.
    Terms (type, customer, validity) are copied from the latest revision unless `start`/`expiry` are given explicitly."""
    if not verified_by_owner:
        raise IssueError("Confirm that you verified the customer's identity and replacement request")
    if not reason.strip():
        raise IssueError("A reason for the replacement is required (e.g. 'phone lost')")
    prev = history.latest_revision(license_id)
    if prev is None:
        raise IssueError(f"Unknown license id {license_id}")
    now = now or datetime.now(timezone.utc)
    tz = prev.tz
    from zoneinfo import ZoneInfo
    zone = ZoneInfo(tz)
    prev_start = datetime.fromtimestamp(prev.start_at, zone).date()
    prev_exp = datetime.fromtimestamp(prev.expires_at, zone).date() if prev.expires_at is not None else None
    lic = _build(signer, license_id=license_id, revision=prev.revision + 1, customer_name=prev.customer_name,
                 customer_ref=prev.customer_ref, kind=prev.kind, start=start or prev_start,
                 expiry=(expiry or prev_exp) if prev.kind == "duration" else None, tz=tz, request_text=request_text,
                 notes=prev.notes, reissue_reason=reason.strip(), now=now, features=None)
    history.add(lic.record)
    return lic
