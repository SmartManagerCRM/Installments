"""Local issuance history (SQLite). Contains customer names and device ids but NO key material."""
from __future__ import annotations

import sqlite3
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

SCHEMA = """
CREATE TABLE IF NOT EXISTS issued (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  license_id TEXT NOT NULL,
  revision INTEGER NOT NULL,
  customer_name TEXT NOT NULL,
  customer_ref TEXT,
  kind TEXT NOT NULL,
  device_id TEXT NOT NULL,
  issued_at INTEGER NOT NULL,
  start_at INTEGER NOT NULL,
  expires_at INTEGER,
  key_id TEXT NOT NULL,
  tz TEXT NOT NULL,
  notes TEXT,
  reissue_reason TEXT,
  license_text TEXT NOT NULL,
  UNIQUE (license_id, revision)
);
CREATE INDEX IF NOT EXISTS idx_issued_customer ON issued(customer_name);
CREATE INDEX IF NOT EXISTS idx_issued_license ON issued(license_id);
"""


@dataclass
class IssuedRecord:
    license_id: str
    revision: int
    customer_name: str
    customer_ref: Optional[str]
    kind: str
    device_id: str
    issued_at: int
    start_at: int
    expires_at: Optional[int]
    key_id: str
    tz: str
    notes: Optional[str]
    reissue_reason: Optional[str]
    license_text: str


class History:
    def __init__(self, path: Path | str):
        self.path = str(path)
        if self.path != ":memory:":
            Path(self.path).parent.mkdir(parents=True, exist_ok=True)
        self.db = sqlite3.connect(self.path)
        self.db.row_factory = sqlite3.Row
        self.db.executescript(SCHEMA)

    def add(self, r: IssuedRecord) -> None:
        with self.db:
            self.db.execute(
                "INSERT INTO issued (license_id,revision,customer_name,customer_ref,kind,device_id,issued_at,start_at,expires_at,"
                "key_id,tz,notes,reissue_reason,license_text) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                (r.license_id, r.revision, r.customer_name, r.customer_ref, r.kind, r.device_id, r.issued_at, r.start_at,
                 r.expires_at, r.key_id, r.tz, r.notes, r.reissue_reason, r.license_text))

    @staticmethod
    def _row(row: sqlite3.Row) -> IssuedRecord:
        d = {k: row[k] for k in row.keys() if k != "id"}
        return IssuedRecord(**d)

    def latest_revision(self, license_id: str) -> Optional[IssuedRecord]:
        row = self.db.execute("SELECT * FROM issued WHERE license_id=? ORDER BY revision DESC LIMIT 1", (license_id,)).fetchone()
        return self._row(row) if row else None

    def revisions(self, license_id: str) -> list[IssuedRecord]:
        return [self._row(r) for r in self.db.execute("SELECT * FROM issued WHERE license_id=? ORDER BY revision", (license_id,))]

    def search(self, text: str = "", limit: int = 500) -> list[IssuedRecord]:
        like = f"%{text.strip()}%"
        rows = self.db.execute(
            "SELECT * FROM issued WHERE license_id LIKE ? OR customer_name LIKE ? OR IFNULL(customer_ref,'') LIKE ? "
            "OR device_id LIKE ? ORDER BY issued_at DESC, revision DESC LIMIT ?", (like, like, like, like, limit))
        return [self._row(r) for r in rows]

    def close(self) -> None:
        self.db.close()
