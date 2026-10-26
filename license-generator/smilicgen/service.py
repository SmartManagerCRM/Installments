"""License issuing logic shared by the GUI and the CLI."""
from __future__ import annotations

import secrets
from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone
from zoneinfo import ZoneInfo
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


def expiry_from_days(start: date, days: int) -> date:
    """'Valid for N days' = the license works until the END of the day that is N days after the start date."""
    if days < 1 or days > 3660:
        raise IssueError("Number of days must be between 1 and 3660")
    return start + timedelta(days=days)


def issue_license(signer: Signer, history: History, *, customer_name: str, customer_ref: Optional[str] = None, kind: str,
                  start: Optional[date] = None, expiry: Optional[date] = None, days: Optional[int] = None, tz: str = "Africa/Tunis",
                  request_text: str, notes: Optional[str] = None, now: Optional[datetime] = None, features: Optional[list] = None) -> IssuedLicense:
    now = now or datetime.now(timezone.utc)
    if start is None:
        start = now.astimezone(ZoneInfo(tz)).date()
    if kind == "duration" and expiry is None and days is not None:
        expiry = expiry_from_days(start, days)
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
    zone = ZoneInfo(tz)
    prev_start = datetime.fromtimestamp(prev.start_at, zone).date()
    prev_exp = datetime.fromtimestamp(prev.expires_at, zone).date() if prev.expires_at is not None else None
    lic = _build(signer, license_id=license_id, revision=prev.revision + 1, customer_name=prev.customer_name,
                 customer_ref=prev.customer_ref, kind=prev.kind, start=start or prev_start,
                 expiry=(expiry or prev_exp) if prev.kind == "duration" else None, tz=tz, request_text=request_text,
                 notes=prev.notes, reissue_reason=reason.strip(), now=now, features=None)
    history.add(lic.record)
    return lic


def renew_license(signer: Signer, history: History, *, license_id: str, kind: str, start: Optional[date] = None,
                  expiry: Optional[date] = None, days: Optional[int] = None, reason: str = "renewal",
                  now: Optional[datetime] = None) -> IssuedLicense:
    """New code for the SAME phone (revision + 1): renew a subscription, change its length, or switch lifetime <-> subscription.
    No new request code is needed because the device id is taken from the previous revision."""
    prev = history.latest_revision(license_id)
    if prev is None:
        raise IssueError(f"Unknown license id {license_id}")
    now = now or datetime.now(timezone.utc)
    zone = ZoneInfo(prev.tz)
    start = start or now.astimezone(zone).date()
    if kind == "duration" and expiry is None:
        if days is None:
            raise IssueError("Enter the number of days (or an expiry date)")
        expiry = expiry_from_days(start, days)
    lic = _build(signer, license_id=license_id, revision=prev.revision + 1, customer_name=prev.customer_name,
                 customer_ref=prev.customer_ref, kind=kind, start=start, expiry=expiry if kind == "duration" else None,
                 tz=prev.tz, request_text=prev.device_id, notes=prev.notes, reissue_reason=(reason or "renewal").strip(),
                 now=now, features=None)
    history.add(lic.record)
    return lic


def license_status(rec: IssuedRecord, now_epoch: Optional[int] = None) -> str:
    """ACTIVE / EXPIRED / NOT YET VALID, evaluated at the owner's computer clock (the app uses the phone's clock)."""
    now_epoch = int(datetime.now(timezone.utc).timestamp()) if now_epoch is None else now_epoch
    if rec.start_at - 24 * 3600 > now_epoch:
        return "NOT YET VALID"
    if rec.expires_at is not None and now_epoch > rec.expires_at:
        return "EXPIRED"
    return "ACTIVE"


def valid_to_text(rec: IssuedRecord) -> str:
    if rec.expires_at is None:
        return "lifetime"
    return datetime.fromtimestamp(rec.expires_at, ZoneInfo(rec.tz)).strftime("%Y-%m-%d")


def export_csv(history: History, path) -> int:
    """Writes every issued license revision to a UTF-8 CSV (Excel-friendly) and returns the row count."""
    import csv
    rows = history.all_rows()
    with open(path, "w", newline="", encoding="utf-8-sig") as f:
        w = csv.writer(f)
        w.writerow(["license_id", "revision", "customer", "order_reference", "type", "device_id", "issued_at_utc", "valid_from", "valid_to", "key_id", "note", "reason"])
        for r in rows:
            def safe(v):  # neutralise spreadsheet formulas
                v = "" if v is None else str(v)
                return "'" + v if v[:1] in "=+-@" else v
            w.writerow([r.license_id, r.revision, safe(r.customer_name), safe(r.customer_ref), r.kind, r.device_id,
                        datetime.fromtimestamp(r.issued_at, timezone.utc).strftime("%Y-%m-%d %H:%M"),
                        datetime.fromtimestamp(r.start_at, ZoneInfo(r.tz)).strftime("%Y-%m-%d"), valid_to_text(r), r.key_id,
                        safe(r.notes), safe(r.reissue_reason)])
    return len(rows)
