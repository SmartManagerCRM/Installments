"""Simple Tkinter GUI. All logic lives in service.py / keys.py / history.py (unit-tested without a display).

Tabs: 1. Keys   2. Issue license   3. Customers & licenses   4. Verify license
"""
from __future__ import annotations

import os
import re
import subprocess
import sys
import tkinter as tk
from datetime import date, datetime, timezone
from pathlib import Path
from tkinter import filedialog, messagebox, simpledialog, ttk

from . import __version__
from .formats import human_summary, parse_license_file, to_text_form
from .history import History
from .keys import KeyError_, Signer, backup_key, default_home, generate_key, is_encrypted, key_file, load_signer, public_pem
from .service import (IssueError, IssuedLicense, export_csv, issue_license, license_status, renew_license, reissue_license,
                      valid_to_text)
from .verify import verify_license

BOLD = ("TkDefaultFont", 10, "bold")


def test_key_path() -> Path | None:
    """The published TEST key shipped next to the generator (only trusted by DEBUG builds of the app)."""
    for base in (Path(__file__).resolve().parents[1], Path(sys.argv[0]).resolve().parent, Path.cwd()):
        p = base / "test-keys" / "TEST_ONLY_signing_key.pem"
        if p.exists():
            return p
    return None


def slug(text: str) -> str:
    return re.sub(r"[^A-Za-z0-9_-]+", "_", text).strip("_")[:40] or "license"


class App(tk.Tk):
    def __init__(self, home: Path | None, key_path: Path | None):
        super().__init__()
        self.title(f"SmartManager Installments - License Manager {__version__}")
        self.geometry("1000x860")
        self.minsize(900, 640)
        self.home = home or default_home()
        self.home.mkdir(parents=True, exist_ok=True)
        self.history = History(self.home / "license_history.sqlite")
        self.v_key = tk.StringVar(value=str(key_path or key_file(self.home)))
        self.v_pass = tk.StringVar()
        self.last: IssuedLicense | None = None
        self._records: list = []

        nb = ttk.Notebook(self)
        nb.pack(fill="both", expand=True, padx=6, pady=6)
        self.tabs = {name: ttk.Frame(nb, padding=10) for name in ("keys", "issue", "register", "verify")}
        nb.add(self.tabs["keys"], text="1. Keys")
        nb.add(self.tabs["issue"], text="2. Issue license")
        nb.add(self.tabs["register"], text="3. Customers & licenses")
        nb.add(self.tabs["verify"], text="4. Verify license")
        self.nb = nb
        self._build_keys(); self._build_issue(); self._build_register(); self._build_verify()

        out = ttk.LabelFrame(self, text="Output", padding=4)
        out.pack(fill="both", expand=False, padx=6, pady=(0, 6))
        self.t_out = tk.Text(out, height=13, wrap="word", background="#fbfbfb")
        self.t_out.pack(fill="both", expand=True)
        self.show("Ready. Tab 2: paste the phone's Device ID or activation request, choose the validity and press 'Issue license'.")
        if not self.key_exists():
            nb.select(self.tabs["keys"])

    # ------------------------------------------------------------------ helpers
    def show(self, text: str) -> None:
        self.t_out.configure(state="normal"); self.t_out.delete("1.0", "end"); self.t_out.insert("1.0", text); self.t_out.configure(state="disabled")

    def key_exists(self) -> bool:
        return Path(self.v_key.get()).exists()

    def signer(self) -> Signer | None:
        path = Path(self.v_key.get().strip())
        if not path.exists():
            messagebox.showerror("Signing key", "The key file does not exist. Create it in tab 1 (Keys) or choose it with Browse.")
            return None
        try:
            return load_signer(path, self.v_pass.get() or None) if is_encrypted(path) else load_signer(path, None)
        except KeyError_ as exc:
            messagebox.showerror("Signing key", str(exc))
            return None

    def row(self, parent, r, label, widget_factory, hint: str = "") -> tk.Widget:
        ttk.Label(parent, text=label, font=BOLD).grid(row=r, column=0, sticky="nw", padx=(0, 12), pady=6)
        w = widget_factory(parent)
        w.grid(row=r, column=1, sticky="ew", pady=6)
        if hint:
            ttk.Label(parent, text=hint, foreground="#666").grid(row=r + 1, column=1, sticky="w")
        return w

    def key_rows(self, parent, start_row: int) -> int:
        """Private key file + passphrase rows (shared by tabs 2 and 3)."""
        f = ttk.Frame(parent)
        ttk.Entry(f, textvariable=self.v_key).pack(side="left", fill="x", expand=True)
        ttk.Button(f, text="Browse...", command=self._browse_key).pack(side="left", padx=4)
        if test_key_path():
            ttk.Button(f, text="Use TEST key", command=lambda: self.v_key.set(str(test_key_path()))).pack(side="left")
        ttk.Label(parent, text="Private key file", font=BOLD).grid(row=start_row, column=0, sticky="nw", padx=(0, 12), pady=6)
        f.grid(row=start_row, column=1, sticky="ew", pady=6)
        ttk.Label(parent, text="Passphrase", font=BOLD).grid(row=start_row + 1, column=0, sticky="nw", padx=(0, 12), pady=6)
        ttk.Entry(parent, textvariable=self.v_pass, show="*").grid(row=start_row + 1, column=1, sticky="ew", pady=6)
        return start_row + 2

    def _browse_key(self) -> None:
        p = filedialog.askopenfilename(title="Private key file", filetypes=[("PEM key", "*.pem"), ("All files", "*.*")])
        if p:
            self.v_key.set(p)

    # ------------------------------------------------------------------ 1. keys
    def _build_keys(self) -> None:
        f = self.tabs["keys"]; f.columnconfigure(1, weight=1)
        ttk.Label(f, justify="left", wraplength=900, text=(
            "The private key signs every license you issue. It never leaves your computer and must NEVER go into the Android project, "
            "the APK or Git. Create it once, then make two offline backups.\\n"
            "For the DEBUG test app use the 'Use TEST key' button in tab 2 - no key creation needed.").replace("\\n", "\n")).grid(row=0, column=0, columnspan=2, sticky="w", pady=(0, 10))
        r = self.key_rows(f, 1)
        bar = ttk.Frame(f); bar.grid(row=r, column=1, sticky="w", pady=8)
        ttk.Button(bar, text="Create new signing key (passphrase above)", command=self._create_key).pack(side="left", padx=2)
        ttk.Button(bar, text="Export public key for the app...", command=self._export_pub).pack(side="left", padx=2)
        ttk.Button(bar, text="Back up private key...", command=self._backup).pack(side="left", padx=2)
        self.l_keyinfo = ttk.Label(f, foreground="#333", justify="left")
        self.l_keyinfo.grid(row=r + 1, column=0, columnspan=2, sticky="w", pady=10)
        self._refresh_keyinfo()

    def _refresh_keyinfo(self) -> None:
        self.l_keyinfo.configure(text=f"Key file: {self.v_key.get()}\nStatus: {'present' if self.key_exists() else 'NOT CREATED YET'}\n"
                                      f"History database: {self.home / 'license_history.sqlite'}")

    def _create_key(self) -> None:
        pw = self.v_pass.get()
        if len(pw) < 10:
            messagebox.showerror("Passphrase", "Type a passphrase of at least 10 characters in the Passphrase box first.")
            return
        target = Path(self.v_key.get())
        try:
            signer = generate_key(target.parent, pw)
        except KeyError_ as exc:
            messagebox.showerror("Create key", str(exc)); return
        self._refresh_keyinfo()
        self.show(f"Signing key created (key id {signer.key_id}).\nPrivate key: {target}\n\nNext: 'Back up private key...' (2 copies, offline) and "
                  f"'Export public key for the app...' (put it in app/src/main/res/raw/license_public_key.pem and rebuild the app).")

    def _export_pub(self) -> None:
        s = self.signer()
        if s:
            p = filedialog.asksaveasfilename(initialfile="license_public_key.pem", defaultextension=".pem")
            if p:
                Path(p).write_text(public_pem(s.public_key), encoding="ascii"); self.show(f"Public key {s.key_id} saved to {p}")

    def _backup(self) -> None:
        p = filedialog.asksaveasfilename(initialfile="smi-signing-key-BACKUP.pem", defaultextension=".pem")
        if p:
            try:
                backup_key(Path(self.v_key.get()), Path(p)); self.show(f"Encrypted private key copied to {p}. Store it offline, away from this computer.")
            except (KeyError_, OSError) as exc:
                messagebox.showerror("Backup", str(exc))

    # ------------------------------------------------------------------ 2. issue
    def _build_issue(self) -> None:
        f = self.tabs["issue"]; f.columnconfigure(1, weight=1)
        r = self.key_rows(f, 0)
        ttk.Label(f, text="Device ID or\nActivation request", font=BOLD, justify="left").grid(row=r, column=0, sticky="nw", padx=(0, 12), pady=6)
        self.t_req = tk.Text(f, height=4, wrap="char")
        self.t_req.grid(row=r, column=1, sticky="ew", pady=6)
        ttk.Label(f, text="Paste what the customer sends from the app's activation screen (the long SMI-ACT-1:... text, or just XXXX-XXXX-XXXX-XXXX-XXXX).",
                  foreground="#666").grid(row=r + 1, column=1, sticky="w")
        self.v_name, self.v_ref = tk.StringVar(), tk.StringVar()
        self.row(f, r + 2, "Customer / note", lambda p: ttk.Entry(p, textvariable=self.v_name), "Required. Private - only shown in your register, never sent anywhere.")
        self.row(f, r + 4, "Order reference", lambda p: ttk.Entry(p, textvariable=self.v_ref), "Optional (invoice or order number - no personal data).")
        self.v_kind, self.v_days = tk.StringVar(value="lifetime"), tk.StringVar(value="30")
        v = ttk.Frame(f)
        ttk.Radiobutton(v, text="Lifetime (never expires)", variable=self.v_kind, value="lifetime", command=self._kind_changed).pack(side="left")
        ttk.Radiobutton(v, text="Subscription: valid for", variable=self.v_kind, value="duration", command=self._kind_changed).pack(side="left", padx=(16, 4))
        self.e_days = ttk.Entry(v, textvariable=self.v_days, width=6); self.e_days.pack(side="left")
        ttk.Label(v, text="days (renew it when the customer pays again)").pack(side="left", padx=4)
        ttk.Label(f, text="Validity", font=BOLD).grid(row=r + 6, column=0, sticky="nw", padx=(0, 12), pady=6)
        v.grid(row=r + 6, column=1, sticky="w", pady=6)
        self.v_save = tk.StringVar()
        sv = ttk.Frame(f)
        ttk.Entry(sv, textvariable=self.v_save).pack(side="left", fill="x", expand=True)
        ttk.Button(sv, text="Browse...", command=self._browse_save).pack(side="left", padx=4)
        ttk.Label(f, text="Save license as", font=BOLD).grid(row=r + 7, column=0, sticky="nw", padx=(0, 12), pady=6)
        sv.grid(row=r + 7, column=1, sticky="ew", pady=6)
        ttk.Label(f, text=f"Leave empty to save automatically in {self.home / 'licenses'}", foreground="#666").grid(row=r + 8, column=1, sticky="w")
        ttk.Button(f, text="Issue license", command=self._issue).grid(row=r + 9, column=1, sticky="ew", pady=(12, 4), ipady=6)
        bar = ttk.Frame(f); bar.grid(row=r + 10, column=1, sticky="w")
        ttk.Button(bar, text="Copy license code", command=lambda: self._copy(lambda l: l.text_form)).pack(side="left", padx=2)
        ttk.Button(bar, text="Copy summary", command=lambda: self._copy(lambda l: l.summary)).pack(side="left", padx=2)
        ttk.Button(bar, text="Open licenses folder", command=lambda: self._open_folder(self.home / "licenses")).pack(side="left", padx=2)
        self._kind_changed()

    def _kind_changed(self) -> None:
        self.e_days.configure(state="normal" if self.v_kind.get() == "duration" else "disabled")

    def _browse_save(self) -> None:
        p = filedialog.asksaveasfilename(defaultextension=".smilic", filetypes=[("SmartManager license", "*.smilic")])
        if p:
            self.v_save.set(p)

    def _copy(self, fn) -> None:
        if self.last:
            self.clipboard_clear(); self.clipboard_append(fn(self.last)); self.update(); messagebox.showinfo("Copied", "Copied to the clipboard.")
        else:
            messagebox.showinfo("Nothing yet", "Issue a license first.")

    def _open_folder(self, path: Path) -> None:
        path.mkdir(parents=True, exist_ok=True)
        try:
            if sys.platform.startswith("win"):
                os.startfile(str(path))  # type: ignore[attr-defined]
            elif sys.platform == "darwin":
                subprocess.Popen(["open", str(path)])
            else:
                subprocess.Popen(["xdg-open", str(path)])
        except OSError:
            self.show(f"Folder: {path}")

    def _save_license(self, lic: IssuedLicense, chosen: str = "") -> Path:
        if chosen.strip():
            path = Path(chosen.strip())
        else:
            folder = self.home / "licenses"; folder.mkdir(parents=True, exist_ok=True)
            path = folder / f"{slug(lic.record.customer_name)}-{lic.suggested_filename}"
        path.write_text(lic.file_json, encoding="utf-8")
        return path

    def _finish(self, lic: IssuedLicense, chosen: str = "") -> None:
        self.last = lic
        path = self._save_license(lic, chosen)
        self.show(f"LICENSE ISSUED  -  {lic.record.license_id} (revision {lic.record.revision})\n\n{lic.summary}\n\n"
                  f"Saved file: {path}\nSend this file to the customer, or copy the one-line code below:\n\n{lic.text_form}")
        self._refresh_register()

    def _issue(self) -> None:
        signer = self.signer()
        if not signer:
            return
        try:
            kind = self.v_kind.get()
            days = int(self.v_days.get()) if kind == "duration" else None
            lic = issue_license(signer, self.history, customer_name=self.v_name.get(), customer_ref=self.v_ref.get(), kind=kind, days=days,
                                request_text=self.t_req.get("1.0", "end"))
        except ValueError as exc:  # IssueError is a ValueError
            messagebox.showerror("Cannot issue license", str(exc)); return
        self._finish(lic, self.v_save.get())

    # ------------------------------------------------------------------ 3. register
    def _build_register(self) -> None:
        f = self.tabs["register"]
        top = ttk.Frame(f); top.pack(fill="x")
        ttk.Label(top, text="Search:").pack(side="left")
        self.v_search = tk.StringVar()
        e = ttk.Entry(top, textvariable=self.v_search, width=34); e.pack(side="left", padx=6)
        e.bind("<KeyRelease>", lambda _e: self._refresh_register())
        ttk.Button(top, text="Refresh", command=self._refresh_register).pack(side="left")
        cols = ("ref", "note", "license", "status", "valid", "rev", "device")
        heads = ("Order ref", "Customer / note", "License ID", "Status", "Valid to", "Rev", "Device ID")
        self.tree = ttk.Treeview(f, columns=cols, show="headings", height=12)
        for c, h, w in zip(cols, heads, (110, 190, 190, 100, 100, 40, 190)):
            self.tree.heading(c, text=h); self.tree.column(c, width=w, anchor="w")
        self.tree.tag_configure("ACTIVE", background="#e3f6ec"); self.tree.tag_configure("EXPIRED", background="#fce8e9"); self.tree.tag_configure("NOT YET VALID", background="#fff1de")
        self.tree.pack(fill="both", expand=True, pady=8)
        self.l_count = ttk.Label(f, font=BOLD); self.l_count.pack(anchor="w")
        bar = ttk.Frame(f); bar.pack(fill="x", pady=6)
        for text, cmd in (("Renew / change validity...", self._renew), ("New phone (replacement)...", self._reissue), ("History", self._history),
                          ("Save license file...", self._save_selected), ("Copy license code", self._copy_selected), ("Export CSV...", self._export_csv)):
            ttk.Button(bar, text=text, command=cmd).pack(side="left", padx=3)
        ttk.Label(f, justify="left", wraplength=940, foreground="#444", text=(
            "How control works offline: a subscription ends by itself on its end date unless you send a renewal code - that is how you stop a customer who does not "
            "pay. Renew creates a new code for the SAME phone (no new request code needed). 'New phone' needs the new phone's request code and an ID check. "
            "A license can't be switched off remotely once the app has no internet connection.")).pack(anchor="w", pady=6)
        self._refresh_register()

    def _refresh_register(self) -> None:
        self._records = self.history.latest_all(self.v_search.get())
        self.tree.delete(*self.tree.get_children())
        counts: dict[str, int] = {}
        for i, r in enumerate(self._records):
            st = license_status(r)
            counts[st] = counts.get(st, 0) + 1
            self.tree.insert("", "end", iid=str(i), tags=(st,), values=(r.customer_ref or "", r.customer_name, r.license_id, st, valid_to_text(r), r.revision, r.device_id))
        self.l_count.configure(text=f"{len(self._records)} license(s): " + "  ".join(f"{k} {v}" for k, v in sorted(counts.items())))

    def _selected(self):
        sel = self.tree.selection()
        if not sel:
            messagebox.showinfo("Select a license", "Select a license in the list first.")
            return None
        return self._records[int(sel[0])]

    def _renew(self) -> None:
        r = self._selected()
        if not r:
            return
        d = tk.Toplevel(self); d.title(f"Renew {r.license_id}"); d.transient(self); d.grab_set()
        ttk.Label(d, text=f"{r.customer_name}\nCurrent: {r.kind}, valid to {valid_to_text(r)}\nDevice: {r.device_id}", justify="left").grid(row=0, column=0, columnspan=3, padx=12, pady=10, sticky="w")
        kind = tk.StringVar(value="duration" if r.kind == "duration" else "lifetime"); days = tk.StringVar(value="30")
        ttk.Radiobutton(d, text="Lifetime", variable=kind, value="lifetime").grid(row=1, column=0, padx=12, sticky="w")
        ttk.Radiobutton(d, text="Subscription: valid for", variable=kind, value="duration").grid(row=2, column=0, padx=12, sticky="w")
        ttk.Entry(d, textvariable=days, width=6).grid(row=2, column=1); ttk.Label(d, text="days from today").grid(row=2, column=2, padx=6, sticky="w")

        def go() -> None:
            signer = self.signer()
            if not signer:
                return
            try:
                lic = renew_license(signer, self.history, license_id=r.license_id, kind=kind.get(), days=int(days.get()) if kind.get() == "duration" else None)
            except ValueError as exc:
                messagebox.showerror("Cannot renew", str(exc), parent=d); return
            d.destroy(); self._finish(lic)

        ttk.Button(d, text="Create renewal code", command=go).grid(row=3, column=0, columnspan=3, pady=12, ipadx=10)

    def _reissue(self) -> None:
        r = self._selected()
        if not r:
            return
        d = tk.Toplevel(self); d.title(f"New phone for {r.license_id}"); d.transient(self); d.grab_set(); d.geometry("620x360")
        ttk.Label(d, text=f"{r.customer_name}\nOld device: {r.device_id}", justify="left").pack(anchor="w", padx=12, pady=8)
        ttk.Label(d, text="New Device ID or activation request *").pack(anchor="w", padx=12)
        req = tk.Text(d, height=4, wrap="char"); req.pack(fill="x", padx=12)
        ttk.Label(d, text="Reason * (e.g. 'phone lost, verified by video call')").pack(anchor="w", padx=12, pady=(8, 0))
        reason = tk.StringVar(); ttk.Entry(d, textvariable=reason).pack(fill="x", padx=12)
        ok = tk.BooleanVar(); ttk.Checkbutton(d, text="I verified the customer's identity and that the old phone is no longer used", variable=ok).pack(anchor="w", padx=12, pady=8)

        def go() -> None:
            signer = self.signer()
            if not signer:
                return
            try:
                lic = reissue_license(signer, self.history, license_id=r.license_id, request_text=req.get("1.0", "end"), reason=reason.get(), verified_by_owner=ok.get())
            except ValueError as exc:
                messagebox.showerror("Cannot issue", str(exc), parent=d); return
            d.destroy(); self._finish(lic)

        ttk.Button(d, text="Issue replacement license", command=go).pack(pady=8, ipadx=10)

    def _history(self) -> None:
        r = self._selected()
        if not r:
            return
        lines = [f"History of {r.license_id} - {r.customer_name}"]
        for h in self.history.revisions(r.license_id):
            lines.append(f"  r{h.revision}  issued {datetime.fromtimestamp(h.issued_at, timezone.utc):%Y-%m-%d %H:%M} UTC  {h.kind:8} valid to {valid_to_text(h)}  device {h.device_id}"
                         + (f"  [{h.reissue_reason}]" if h.reissue_reason else ""))
        self.show("\n".join(lines))

    def _save_selected(self) -> None:
        r = self._selected()
        if r:
            p = filedialog.asksaveasfilename(initialfile=f"{slug(r.customer_name)}-{r.license_id}-r{r.revision}.smilic", defaultextension=".smilic")
            if p:
                Path(p).write_text(r.license_text, encoding="utf-8"); self.show(f"Saved {p}")

    def _copy_selected(self) -> None:
        r = self._selected()
        if r:
            self.clipboard_clear(); self.clipboard_append(to_text_form(r.license_text)); self.update(); self.show("One-line license code copied to the clipboard.")

    def _export_csv(self) -> None:
        p = filedialog.asksaveasfilename(initialfile="issued-licenses.csv", defaultextension=".csv", filetypes=[("CSV", "*.csv")])
        if p:
            self.show(f"{export_csv(self.history, p)} rows exported to {p}")

    # ------------------------------------------------------------------ 4. verify
    def _build_verify(self) -> None:
        f = self.tabs["verify"]; f.columnconfigure(1, weight=1)
        ttk.Label(f, text="Check a license file or code against a public key and a device id (the same check the app performs).", wraplength=900).grid(row=0, column=0, columnspan=2, sticky="w", pady=(0, 8))
        self.v_vfile, self.v_vpub, self.v_vdev = tk.StringVar(), tk.StringVar(), tk.StringVar()
        for i, (label, var, browse) in enumerate((("License file", self.v_vfile, "*.smilic"), ("Public key (.pem)", self.v_vpub, "*.pem"))):
            ttk.Label(f, text=label, font=BOLD).grid(row=1 + i, column=0, sticky="w", padx=(0, 12), pady=6)
            fr = ttk.Frame(f); fr.grid(row=1 + i, column=1, sticky="ew")
            ttk.Entry(fr, textvariable=var).pack(side="left", fill="x", expand=True)
            ttk.Button(fr, text="Browse...", command=lambda v=var, b=browse: v.set(filedialog.askopenfilename(filetypes=[("files", b), ("All", "*.*")]) or v.get())).pack(side="left", padx=4)
        ttk.Label(f, text="Device ID", font=BOLD).grid(row=3, column=0, sticky="w", padx=(0, 12), pady=6)
        ttk.Entry(f, textvariable=self.v_vdev).grid(row=3, column=1, sticky="ew")
        ttk.Button(f, text="Verify", command=self._verify).grid(row=4, column=1, sticky="ew", pady=10, ipady=4)
        if test_key_path():
            self.v_vpub.set(str(test_key_path().with_name("TEST_ONLY_public_key.pem")))

    def _verify(self) -> None:
        try:
            text = Path(self.v_vfile.get()).read_text(encoding="utf-8")
            status, payload = verify_license(text, Path(self.v_vpub.get()).read_text(), self.v_vdev.get())
        except (OSError, ValueError) as exc:
            messagebox.showerror("Verify", str(exc)); return
        self.show(f"Result: {status}\n\n" + (human_summary(payload) if payload else ""))


def run_gui(home: Path | None = None, key_path: Path | None = None) -> None:
    App(home, key_path).mainloop()
