"""License / activation-request formats. Must stay byte-compatible with core/.../licensing/License.kt."""
from __future__ import annotations

import base64
import hashlib
import json
from dataclasses import dataclass
from datetime import date, datetime, time, timezone
from typing import Optional
from zoneinfo import ZoneInfo

PRODUCT_ID = "smartmanager-installments"
LICENSE_FORMAT = "SMI-LIC"
SCHEMA_VERSION = 1
ACTIVATION_PREFIX = "SMI-ACT-1:"
LICENSE_TEXT_PREFIX = "SMI-LIC-1."
DEFAULT_FEATURES = ["core"]


def b64u(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def b64u_decode(text: str) -> bytes:
    text = text.strip()
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


def canonical_json(obj: dict) -> bytes:
    """Sorted keys, no whitespace, UTF-8. The signature covers exactly these bytes."""
    return json.dumps(obj, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


class RequestError(ValueError):
    pass


def normalize_device_id(device_id: str) -> str:
    return "".join(ch for ch in device_id.upper() if ch.isalnum())


def format_device_id(device_id: str) -> str:
    n = normalize_device_id(device_id)
    return "-".join(n[i:i + 4] for i in range(0, len(n), 4))


def parse_activation_request(text: str) -> str:
    """Accepts a full `SMI-ACT-1:...` request, or a bare device id (XXXX-XXXX-XXXX-XXXX-XXXX). Returns the device id."""
    t = text.strip()
    if not t:
        raise RequestError("Empty activation request")
    if t.startswith(ACTIVATION_PREFIX):
        try:
            body = t[len(ACTIVATION_PREFIX):]
            b64, check = body.split(".")
            raw = b64u_decode(b64)
        except Exception as exc:  # noqa: BLE001
            raise RequestError("Malformed activation request") from exc
        if hashlib.sha256(raw).hexdigest()[:6] != check:
            raise RequestError("Activation request checksum mismatch (was it copied completely?)")
        data = json.loads(raw.decode("utf-8"))
        if data.get("product") != PRODUCT_ID:
            raise RequestError("Activation request is for a different product")
        device = data.get("deviceId", "")
    else:
        device = t
    n = normalize_device_id(device)
    if len(n) != 20 or any(c not in "0123456789ABCDEF" for c in n):
        raise RequestError("Device id must be 20 hexadecimal characters (XXXX-XXXX-XXXX-XXXX-XXXX)")
    return format_device_id(n)


def make_activation_request(device_id: str, app_version: str = "1.0.0", created_at: Optional[int] = None) -> str:
    """Used by tests/tools to simulate the app's request."""
    body = {"v": 1, "product": PRODUCT_ID, "deviceId": device_id, "app": app_version,
            "at": int(created_at if created_at is not None else datetime.now(timezone.utc).timestamp())}
    raw = json.dumps(body, separators=(",", ":"), sort_keys=True).encode("utf-8")
    return ACTIVATION_PREFIX + b64u(raw) + "." + hashlib.sha256(raw).hexdigest()[:6]


@dataclass
class LicenseSpec:
    license_id: str
    revision: int
    customer_name: str
    customer_ref: Optional[str]
    kind: str  # "lifetime" | "duration"
    issued_at: int
    start_at: int
    expires_at: Optional[int]
    device_id: str
    features: list
    tz: str

    def payload_dict(self) -> dict:
        d = {
            "v": SCHEMA_VERSION,
            "product": PRODUCT_ID,
            "licenseId": self.license_id,
            "revision": self.revision,
            "customerName": self.customer_name,
            "customerRef": self.customer_ref,
            "type": self.kind,
            "issuedAt": self.issued_at,
            "startAt": self.start_at,
            "deviceId": self.device_id,
            "features": list(self.features),
            "tz": self.tz,
        }
        if self.kind == "duration":
            d["expiresAt"] = self.expires_at
        return d


def start_of_day_epoch(day: date, tz: str) -> int:
    """00:00:00 local time of `day` in time zone `tz`, as UTC epoch seconds."""
    return int(datetime.combine(day, time(0, 0, 0), tzinfo=ZoneInfo(tz)).timestamp())


def end_of_day_epoch(day: date, tz: str) -> int:
    """23:59:59 local time of `day` in `tz` (the expiry day is INCLUSIVE)."""
    return int(datetime.combine(day, time(23, 59, 59), tzinfo=ZoneInfo(tz)).timestamp())


def build_license_file(spec: LicenseSpec, sign_fn, key_id: str) -> str:
    payload = canonical_json(spec.payload_dict())
    sig = sign_fn(payload)
    doc = {"fmt": LICENSE_FORMAT, "v": SCHEMA_VERSION, "alg": "ES256", "kid": key_id,
           "payload": b64u(payload), "sig": b64u(sig)}
    return json.dumps(doc, sort_keys=True, separators=(",", ":"))


def to_text_form(license_json: str) -> str:
    return LICENSE_TEXT_PREFIX + b64u(license_json.encode("utf-8"))


def parse_license_file(text: str) -> tuple[dict, bytes, bytes, str]:
    t = text.strip().lstrip("﻿").strip()
    if t.startswith(LICENSE_TEXT_PREFIX):
        t = b64u_decode(t[len(LICENSE_TEXT_PREFIX):]).decode("utf-8")
    doc = json.loads(t)
    payload = b64u_decode(doc["payload"])
    return json.loads(payload.decode("utf-8")), payload, b64u_decode(doc["sig"]), doc["kid"]


def human_summary(p: dict, tz_hint: str | None = None) -> str:
    tz = p.get("tz") or tz_hint or "UTC"
    zone = ZoneInfo(tz)

    def fmt(ts: int | None) -> str:
        return "-" if ts is None else datetime.fromtimestamp(ts, zone).strftime("%Y-%m-%d %H:%M:%S %Z")

    lines = [
        "SmartManager Installments - License summary",
        f"License ID : {p['licenseId']} (revision {p.get('revision', 1)})",
        f"Customer   : {p['customerName']}" + (f"  [{p['customerRef']}]" if p.get("customerRef") else ""),
        f"Type       : {'Lifetime' if p['type'] == 'lifetime' else 'Custom duration'}",
        f"Device     : {p['deviceId']}",
        f"Issued     : {fmt(p['issuedAt'])}",
        f"Valid from : {fmt(p['startAt'])}",
        f"Valid until: {'never (lifetime)' if p['type'] == 'lifetime' else fmt(p.get('expiresAt'))}",
        f"Features   : {', '.join(p.get('features', []))}",
        f"Time zone  : {tz}",
    ]
    return "\n".join(lines)
