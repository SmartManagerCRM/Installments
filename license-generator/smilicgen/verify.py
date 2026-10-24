"""Independent verification (mirrors what the Android app does) - used by tests and `smilicgen verify`."""
from __future__ import annotations

import time
from typing import Optional

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

from .formats import PRODUCT_ID, SCHEMA_VERSION, normalize_device_id, parse_license_file
from .keys import key_id_of_public

TOLERANCE = 24 * 3600


def verify_license(text: str, public_pem: str, device_id: str, now: Optional[int] = None) -> tuple[str, Optional[dict]]:
    now = int(time.time()) if now is None else now
    try:
        payload, raw, sig, kid = parse_license_file(text)
    except Exception:  # noqa: BLE001
        return "MALFORMED", None
    pub = serialization.load_pem_public_key(public_pem.encode("ascii"))
    if key_id_of_public(pub) != kid:  # type: ignore[arg-type]
        return "UNKNOWN_KEY", None
    try:
        pub.verify(sig, raw, ec.ECDSA(hashes.SHA256()))  # type: ignore[union-attr]
    except InvalidSignature:
        return "INVALID_SIGNATURE", None
    if payload.get("v", 0) > SCHEMA_VERSION:
        return "UNSUPPORTED_VERSION", payload
    if payload.get("product") != PRODUCT_ID:
        return "WRONG_PRODUCT", payload
    if normalize_device_id(payload["deviceId"]) != normalize_device_id(device_id):
        return "WRONG_DEVICE", payload
    if payload["type"] == "duration":
        if now < payload["startAt"]:
            return "NOT_YET_VALID", payload
        if now > payload["expiresAt"]:
            return "EXPIRED", payload
    elif now < payload["startAt"] - TOLERANCE:
        return "NOT_YET_VALID", payload
    return "VALID", payload
