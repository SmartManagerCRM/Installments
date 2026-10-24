"""Signing-key management. The private key lives OUTSIDE any repository, encrypted with a passphrase."""
from __future__ import annotations

import hashlib
import os
import stat
import sys
from pathlib import Path

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

PRIVATE_NAME = "signing_key.pem"
PUBLIC_NAME = "public_key.pem"


class KeyError_(Exception):
    pass


def default_home() -> Path:
    env = os.environ.get("SMI_LICGEN_HOME")
    if env:
        return Path(env).expanduser()
    if sys.platform.startswith("win"):
        return Path(os.environ.get("APPDATA", str(Path.home()))) / "SmartManagerLicenseGen"
    return Path.home() / ".smartmanager-licensegen"


def inside_git_worktree(path: Path) -> bool:
    p = path.resolve()
    for parent in [p, *p.parents]:
        if (parent / ".git").exists():
            return True
    return False


def key_id_of_public(public_key: ec.EllipticCurvePublicKey) -> str:
    der = public_key.public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    return hashlib.sha256(der).hexdigest()[:8]


def public_pem(public_key: ec.EllipticCurvePublicKey) -> str:
    return public_key.public_bytes(serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo).decode("ascii")


class Signer:
    def __init__(self, private_key: ec.EllipticCurvePrivateKey):
        if not isinstance(private_key, ec.EllipticCurvePrivateKey) or not isinstance(private_key.curve, ec.SECP256R1):
            raise KeyError_("Signing key must be an ECDSA P-256 key")
        self._key = private_key
        self.public_key = private_key.public_key()
        self.key_id = key_id_of_public(self.public_key)

    def sign(self, data: bytes) -> bytes:
        return self._key.sign(data, ec.ECDSA(hashes.SHA256()))  # DER-encoded, same as Java SHA256withECDSA

    def __repr__(self) -> str:  # never leak key material into logs
        return f"Signer(key_id={self.key_id})"


def generate_key(home: Path, passphrase: str, *, allow_in_repo: bool = False) -> Signer:
    home = Path(home)
    if inside_git_worktree(home) and not allow_in_repo:
        raise KeyError_(f"Refusing to create a private key inside a Git working tree: {home}. Choose a directory outside the repository.")
    if len(passphrase) < 10:
        raise KeyError_("Use a passphrase of at least 10 characters")
    target = home / PRIVATE_NAME
    if target.exists():
        raise KeyError_(f"A signing key already exists at {target}. Refusing to overwrite it (rotate keys explicitly instead).")
    home.mkdir(parents=True, exist_ok=True)
    key = ec.generate_private_key(ec.SECP256R1())
    pem = key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                            serialization.BestAvailableEncryption(passphrase.encode("utf-8")))
    fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "wb") as f:
        f.write(pem)
    (home / PUBLIC_NAME).write_text(public_pem(key.public_key()), encoding="ascii")
    return Signer(key)


def load_signer(path: Path, passphrase: str | None) -> Signer:
    data = Path(path).read_bytes()
    try:
        key = serialization.load_pem_private_key(data, passphrase.encode("utf-8") if passphrase else None)
    except (ValueError, TypeError) as exc:
        raise KeyError_("Could not unlock the signing key (wrong passphrase or damaged file)") from exc
    return Signer(key)  # type: ignore[arg-type]


def key_file(home: Path | None = None) -> Path:
    return (home or default_home()) / PRIVATE_NAME


def is_encrypted(path: Path) -> bool:
    return b"ENCRYPTED" in Path(path).read_bytes()[:200]


def load_public_pem(path: Path) -> str:
    return Path(path).read_text(encoding="ascii")


def backup_key(src: Path, dest: Path) -> None:
    """Copies the (already passphrase-encrypted) private key. An unencrypted key is refused."""
    if not is_encrypted(src):
        raise KeyError_("Refusing to back up an unencrypted private key")
    dest = Path(dest)
    dest.write_bytes(Path(src).read_bytes())
    try:
        os.chmod(dest, stat.S_IRUSR | stat.S_IWUSR)
    except OSError:
        pass
