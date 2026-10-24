"""Tkinter GUI. All business logic lives in service.py / keys.py / history.py (unit-tested without a display)."""
from __future__ import annotations

import tkinter as tk
from datetime import date, datetime, timedelta
from pathlib import Path
from tkinter import filedialog, messagebox, simpledialog, ttk

from . import __version__
from .formats import parse_activation_request
from .history import History
from .keys import KeyError_, Signer, backup_key, default_home, generate_key, is_encrypted, key_file, load_signer, public_pem
from .service import IssueError, IssuedLicense, issue_license, reissue_license


def add_months(d: date, months: int) -> date:
    y, m = divmod(d.month - 1 + months, 12)
    y += d.year
    m += 1
    import calendar
    return date(y, m, min(d.day, calendar.monthrange(y, m)[1]))


class App(tk.Tk):
    def __init__(self, home: Path | None, key_path: Path | None):
        super().__init__()
        self.title(f"SmartManager Installments - License Generator {__version__}")
        self.geometry("920x720")
        self.home = home or default_home()
        self.key_path = key_path or key_file(self.home)
        self.history = History(self.home / "license_history.sqlite")
        self._signer: Signer | None = None
        self.last: IssuedLicense | None = None

        nb = ttk.Notebook(self)
        nb.pack(fill="both", expand=True, padx=8, pady=8)
        self.tab_new, self.tab_hist, self.tab_keys = ttk.Frame(nb), ttk.Frame(nb), ttk.Frame(nb)
        nb.add(self.tab_new, text="New license")
        nb.add(self.tab_hist, text="History / Reissue")
        nb.add(self.tab_keys, text="Signing key")
        self._build_new()
        self._build_history()
        self._build_keys()

    # ---- signer -------------------------------------------------------------------------------
    def signer(self) -> Signer | None:
        if self._signer:
            return self._signer
        if not self.key_path.exists():
            messagebox.showerror("No signing key", "Create the signing key first (tab 'Signing key').")
            return None
        pw = None
        if is_encrypted(self.key_path):
            pw = simpledialog.askstring("Signing key", "Passphrase:", show="*", parent=self)
            if pw is None:
                return None
        try:
            self._signer = load_signer(self.key_path, pw)
        except KeyError_ as exc:
            messagebox.showerror("Signing key", str(exc))
            return None
        return self._signer

    # ---- new license --------------------------------------------------------------------------
    def _build_new(self) -> None:
        f = self.tab_new
        f.columnconfigure(1, weight=1)
        self.v_name, self.v_ref = tk.StringVar(), tk.StringVar()
        self.v_type = tk.StringVar(value="lifetime")
        self.v_start = tk.StringVar(value=date.today().isoformat())
        self.v_expiry = tk.StringVar(value=add_months(date.today(), 12).isoformat())
        self.v_tz = tk.StringVar(value="Africa/Tunis")
        r = 0
        for label, var in (("Customer / business name *", self.v_name), ("Customer reference", self.v_ref)):
            ttk.Label(f, text=label).grid(row=r, column=0, sticky="w", padx=6, pady=4)
            ttk.Entry(f, textvariable=var).grid(row=r, column=1, columnspan=3, sticky="ew", padx=6)
            r += 1
        ttk.Label(f, text="License type *").grid(row=r, column=0, sticky="w", padx=6, pady=4)
        box = ttk.Frame(f); box.grid(row=r, column=1, columnspan=3, sticky="w")
        ttk.Radiobutton(box, text="Lifetime", variable=self.v_type, value="lifetime", command=self._type_changed).pack(side="left")
        ttk.Radiobutton(box, text="Custom duration", variable=self.v_type, value="duration", command=self._type_changed).pack(side="left", padx=12)
        r += 1
        ttk.Label(f, text="Start date (YYYY-MM-DD)").grid(row=r, column=0, sticky="w", padx=6, pady=4)
        ttk.Entry(f, textvariable=self.v_start, width=14).grid(row=r, column=1, sticky="w", padx=6)
        ttk.Label(f, text="Time zone (IANA)").grid(row=r, column=2, sticky="e")
        ttk.Entry(f, textvariable=self.v_tz, width=22).grid(row=r, column=3, sticky="w", padx=6)
        r += 1
        ttk.Label(f, text="Last valid day (inclusive)").grid(row=r, column=0, sticky="w", padx=6, pady=4)
        self.e_expiry = ttk.Entry(f, textvariable=self.v_expiry, width=14)
        self.e_expiry.grid(row=r, column=1, sticky="w", padx=6)
        self.quick = ttk.Frame(f); self.quick.grid(row=r, column=2, columnspan=2, sticky="w")
        for m, t in ((1, "+1 mo"), (3, "+3 mo"), (6, "+6 mo"), (12, "+1 yr"), (24, "+2 yr")):
            ttk.Button(self.quick, text=t, width=6, command=lambda m=m: self._quick(m)).pack(side="left", padx=2)
        r += 1
        ttk.Label(f, text="Activation request (paste) *").grid(row=r, column=0, sticky="nw", padx=6, pady=4)
        self.t_req = tk.Text(f, height=5, wrap="char")
        self.t_req.grid(row=r, column=1, columnspan=3, sticky="ew", padx=6)
        r += 1
        ttk.Button(f, text="Load request from file...", command=self._load_request).grid(row=r, column=1, sticky="w", padx=6)
        r += 1
        ttk.Label(f, text="Internal notes").grid(row=r, column=0, sticky="nw", padx=6, pady=4)
        self.t_notes = tk.Text(f, height=3)
        self.t_notes.grid(row=r, column=1, columnspan=3, sticky="ew", padx=6)
        r += 1
        ttk.Label(f, text="Device policy: one active device per license (bound to the request above).",
                  foreground="#555").grid(row=r, column=0, columnspan=4, sticky="w", padx=6, pady=4)
        r += 1
        ttk.Button(f, text="Generate and save license...", command=self._issue).grid(row=r, column=1, sticky="w", padx=6, pady=8)
        r += 1
        self.t_out = tk.Text(f, height=11, state="disabled", background="#f4f6fa")
        self.t_out.grid(row=r, column=0, columnspan=4, sticky="nsew", padx=6)
        f.rowconfigure(r, weight=1)
        r += 1
        bar = ttk.Frame(f); bar.grid(row=r, column=0, columnspan=4, sticky="w", padx=6, pady=6)
        ttk.Button(bar, text="Copy summary", command=lambda: self._copy(lambda l: l.summary)).pack(side="left", padx=2)
        ttk.Button(bar, text="Copy one-line license text", command=lambda: self._copy(lambda l: l.text_form)).pack(side="left", padx=2)
        self._type_changed()

    def _type_changed(self) -> None:
        state = "normal" if self.v_type.get() == "duration" else "disabled"
        self.e_expiry.configure(state=state)
        for w in self.quick.winfo_children():
            w.configure(state=state)

    def _quick(self, months: int) -> None:
        try:
            start = datetime.strptime(self.v_start.get(), "%Y-%m-%d").date()
        except ValueError:
            start = date.today()
        self.v_expiry.set((add_months(start, months) - timedelta(days=1)).isoformat())

    def _load_request(self) -> None:
        p = filedialog.askopenfilename(title="Activation request", filetypes=[("Text", "*.txt *.smireq"), ("All", "*.*")])
        if p:
            self.t_req.delete("1.0", "end")
            self.t_req.insert("1.0", Path(p).read_text(encoding="utf-8").strip())

    def _show(self, text: str) -> None:
        self.t_out.configure(state="normal"); self.t_out.delete("1.0", "end"); self.t_out.insert("1.0", text); self.t_out.configure(state="disabled")

    def _copy(self, fn) -> None:
        if self.last:
            self.clipboard_clear(); self.clipboard_append(fn(self.last)); messagebox.showinfo("Copied", "Copied to clipboard.")

    def _issue(self) -> None:
        signer = self.signer()
        if not signer:
            return
        try:
            start = datetime.strptime(self.v_start.get().strip(), "%Y-%m-%d").date()
            expiry = datetime.strptime(self.v_expiry.get().strip(), "%Y-%m-%d").date() if self.v_type.get() == "duration" else None
            lic = issue_license(signer, self.history, customer_name=self.v_name.get(), customer_ref=self.v_ref.get(),
                                kind=self.v_type.get(), start=start, expiry=expiry, tz=self.v_tz.get().strip(),
                                request_text=self.t_req.get("1.0", "end"), notes=self.t_notes.get("1.0", "end"))
        except (IssueError, ValueError) as exc:
            messagebox.showerror("Cannot issue license", str(exc))
            return
        self.last = lic
        path = filedialog.asksaveasfilename(title="Save license file", initialfile=lic.suggested_filename, defaultextension=".smilic",
                                            filetypes=[("SmartManager license", "*.smilic")])
        if path:
            Path(path).write_text(lic.file_json, encoding="utf-8")
        self._show(f"License ID: {lic.record.license_id}\n\n{lic.summary}\n\n" + (f"Saved to {path}" if path else "NOT saved to a file yet - use 'Copy one-line license text' or reissue from History."))
        self._refresh_history()

    # ---- history ------------------------------------------------------------------------------
    def _build_history(self) -> None:
        f = self.tab_hist
        top = ttk.Frame(f); top.pack(fill="x", padx=6, pady=6)
        self.v_search = tk.StringVar()
        ttk.Label(top, text="Search (customer, license id, device, reference):").pack(side="left")
        e = ttk.Entry(top, textvariable=self.v_search, width=32); e.pack(side="left", padx=6)
        e.bind("<KeyRelease>", lambda _e: self._refresh_history())
        cols = ("license", "rev", "customer", "type", "valid", "device")
        self.tree = ttk.Treeview(f, columns=cols, show="headings", height=12)
        for c, w in zip(cols, (170, 40, 220, 80, 170, 180)):
            self.tree.heading(c, text=c.title()); self.tree.column(c, width=w)
        self.tree.pack(fill="both", expand=True, padx=6)
        self.tree.bind("<<TreeviewSelect>>", self._select)
        self.t_det = tk.Text(f, height=10, state="disabled", background="#f4f6fa")
        self.t_det.pack(fill="x", padx=6, pady=6)
        bar = ttk.Frame(f); bar.pack(fill="x", padx=6, pady=4)
        ttk.Button(bar, text="Save selected license file...", command=self._save_selected).pack(side="left", padx=2)
        ttk.Button(bar, text="Reissue replacement for new device...", command=self._reissue).pack(side="left", padx=2)
        self._records = []
        self._refresh_history()

    def _refresh_history(self) -> None:
        self._records = self.history.search(self.v_search.get())
        self.tree.delete(*self.tree.get_children())
        for i, r in enumerate(self._records):
            exp = "lifetime" if r.expires_at is None else datetime.fromtimestamp(r.expires_at).strftime("%Y-%m-%d")
            self.tree.insert("", "end", iid=str(i), values=(r.license_id, r.revision, r.customer_name, r.kind, f"{datetime.fromtimestamp(r.start_at):%Y-%m-%d} -> {exp}", r.device_id))

    def _selected(self):
        sel = self.tree.selection()
        return self._records[int(sel[0])] if sel else None

    def _select(self, _e=None) -> None:
        r = self._selected()
        if not r:
            return
        txt = (f"{r.license_id} revision {r.revision}\nCustomer: {r.customer_name}  ref: {r.customer_ref or '-'}\nDevice: {r.device_id}\n"
               f"Issued: {datetime.fromtimestamp(r.issued_at):%Y-%m-%d %H:%M}  key id: {r.key_id}\nNotes: {r.notes or '-'}\n"
               f"Replacement reason: {r.reissue_reason or '-'}")
        self.t_det.configure(state="normal"); self.t_det.delete("1.0", "end"); self.t_det.insert("1.0", txt); self.t_det.configure(state="disabled")

    def _save_selected(self) -> None:
        r = self._selected()
        if not r:
            return
        path = filedialog.asksaveasfilename(initialfile=f"{r.license_id}-r{r.revision}.smilic", defaultextension=".smilic")
        if path:
            Path(path).write_text(r.license_text, encoding="utf-8")

    def _reissue(self) -> None:
        r = self._selected()
        if not r:
            messagebox.showinfo("Reissue", "Select a license first.")
            return
        dlg = tk.Toplevel(self); dlg.title(f"Replacement for {r.license_id}"); dlg.geometry("600x420"); dlg.transient(self)
        ttk.Label(dlg, text=f"Customer: {r.customer_name}\nPrevious device: {r.device_id}", justify="left").pack(anchor="w", padx=8, pady=6)
        ttk.Label(dlg, text="New activation request *").pack(anchor="w", padx=8)
        req = tk.Text(dlg, height=5, wrap="char"); req.pack(fill="x", padx=8)
        ttk.Label(dlg, text="Reason for replacement * (e.g. 'phone lost, verified by video call')").pack(anchor="w", padx=8, pady=(8, 0))
        reason = tk.StringVar(); ttk.Entry(dlg, textvariable=reason).pack(fill="x", padx=8)
        ok = tk.BooleanVar()
        ttk.Checkbutton(dlg, text="I verified the customer's identity and that the old device is no longer used", variable=ok).pack(anchor="w", padx=8, pady=8)
        ttk.Label(dlg, text="Note: an offline license cannot be revoked remotely. The old license keeps working on the old device.",
                  foreground="#a33", wraplength=560).pack(anchor="w", padx=8)

        def go() -> None:
            signer = self.signer()
            if not signer:
                return
            try:
                lic = reissue_license(signer, self.history, license_id=r.license_id, request_text=req.get("1.0", "end"),
                                      reason=reason.get(), verified_by_owner=ok.get())
            except IssueError as exc:
                messagebox.showerror("Cannot reissue", str(exc), parent=dlg)
                return
            self.last = lic
            path = filedialog.asksaveasfilename(parent=dlg, initialfile=lic.suggested_filename, defaultextension=".smilic")
            if path:
                Path(path).write_text(lic.file_json, encoding="utf-8")
            self._show(lic.summary); self._refresh_history(); dlg.destroy()

        ttk.Button(dlg, text="Issue replacement", command=go).pack(pady=10)

    # ---- keys ---------------------------------------------------------------------------------
    def _build_keys(self) -> None:
        f = self.tab_keys
        self.l_key = ttk.Label(f, justify="left"); self.l_key.pack(anchor="w", padx=10, pady=10)
        bar = ttk.Frame(f); bar.pack(anchor="w", padx=10)
        ttk.Button(bar, text="Create signing key...", command=self._create_key).pack(side="left", padx=2)
        ttk.Button(bar, text="Export public key (for the Android app)...", command=self._export_pub).pack(side="left", padx=2)
        ttk.Button(bar, text="Back up private key...", command=self._backup).pack(side="left", padx=2)
        ttk.Label(f, justify="left", foreground="#444", wraplength=800, text=(
            "The private key never leaves this computer, is stored encrypted with your passphrase, and must never be placed in the Android "
            "project, the APK or Git. Keep at least two offline copies of the backup in different places. Losing the key means you cannot issue "
            "licenses that existing apps accept; losing the passphrase is equivalent to losing the key.")).pack(anchor="w", padx=10, pady=14)
        self._refresh_key()

    def _refresh_key(self) -> None:
        self.l_key.configure(text=(f"Key file: {self.key_path}\n" + ("Status: present" if self.key_path.exists() else "Status: NOT CREATED YET")
                                   + f"\nHistory database: {self.home / 'license_history.sqlite'}"))

    def _create_key(self) -> None:
        if self.key_path.exists():
            messagebox.showinfo("Key exists", "A signing key already exists. It will not be overwritten.")
            return
        pw = simpledialog.askstring("New key", "Choose a passphrase (min. 10 characters):", show="*", parent=self)
        if not pw or pw != simpledialog.askstring("New key", "Repeat passphrase:", show="*", parent=self):
            messagebox.showerror("Passphrase", "Passphrases are empty or differ.")
            return
        try:
            self._signer = generate_key(self.key_path.parent, pw)
        except KeyError_ as exc:
            messagebox.showerror("Key", str(exc)); return
        self._refresh_key()
        messagebox.showinfo("Key created", f"Key id {self._signer.key_id}. Now BACK UP the private key and export the public key.")

    def _export_pub(self) -> None:
        s = self.signer()
        if s:
            p = filedialog.asksaveasfilename(initialfile="license_public_key.pem", defaultextension=".pem")
            if p:
                Path(p).write_text(public_pem(s.public_key), encoding="ascii")

    def _backup(self) -> None:
        p = filedialog.asksaveasfilename(initialfile="smi-signing-key-BACKUP.pem", defaultextension=".pem")
        if p:
            try:
                backup_key(self.key_path, Path(p)); messagebox.showinfo("Backup", "Encrypted private key copied. Store it offline.")
            except (KeyError_, OSError) as exc:
                messagebox.showerror("Backup", str(exc))


def run_gui(home: Path | None = None, key_path: Path | None = None) -> None:
    App(home, key_path).mainloop()
