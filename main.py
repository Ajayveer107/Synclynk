"""
SyncLynk - Windows Companion App
=================================
No login. No database. No cloud. Everything lives in memory for the
life of this process, and syncing only happens with a phone that scans
the on-screen QR code while on the same Wi-Fi network.

Run:
    pip install -r requirements.txt
    python main.py
"""

import io
import tkinter as tk
from tkinter import ttk, filedialog

import qrcode
from PIL import Image, ImageTk

from server import SyncLynkServer
from clipboard_sync import ClipboardSync
import notification_receiver
from camera_viewer import CameraViewer
from file_transfer import FileTransfer, RECEIVE_DIR
from history_log import HistoryLog


class SyncLynkApp:
    def __init__(self, root: tk.Tk):
        self.root = root
        self.root.title("SyncLynk")
        self.root.geometry("420x620")
        self.root.resizable(False, False)

        self.history = HistoryLog()
        self.server = SyncLynkServer()
        self.server.status_callback = self._queue_status_update

        self.clipboard_sync = ClipboardSync(self.server, history=self.history)
        notification_receiver.register(self.server, history=self.history)
        self.camera_viewer = CameraViewer(self.server, history=self.history)
        self.file_transfer = FileTransfer(self.server, on_event=self.history.add)

        self._status_queue: list[str] = []
        self._history_queue: list[str] = []
        self.history.add_listener(self._history_queue.append)

        self._build_ui()
        self._show_qr()

        self.server.run_in_background_thread()
        self._poll_queues()

    # ---------------------------------------------------------------- UI

    def _build_ui(self):
        header = ttk.Label(self.root, text="SyncLynk", font=("Segoe UI", 20, "bold"))
        header.pack(pady=(14, 0))

        self.status_var = tk.StringVar(value="Starting server...")
        ttk.Label(self.root, textvariable=self.status_var, wraplength=380, justify="center").pack(pady=(2, 8))

        notebook = ttk.Notebook(self.root)
        notebook.pack(fill="both", expand=True, padx=10, pady=(0, 10))

        self.home_tab = ttk.Frame(notebook)
        self.devices_tab = ttk.Frame(notebook)
        self.files_tab = ttk.Frame(notebook)
        self.history_tab = ttk.Frame(notebook)
        self.settings_tab = ttk.Frame(notebook)

        notebook.add(self.home_tab, text="Home")
        notebook.add(self.devices_tab, text="Devices")
        notebook.add(self.files_tab, text="Files")
        notebook.add(self.history_tab, text="History")
        notebook.add(self.settings_tab, text="Settings")

        self._build_home_tab()
        self._build_devices_tab()
        self._build_files_tab()
        self._build_history_tab()
        self._build_settings_tab()

    def _build_home_tab(self):
        t = self.home_tab
        ttk.Label(
            t, text="Scan this QR from the Android app to connect.", justify="center"
        ).pack(pady=(12, 8))

        self.qr_label = ttk.Label(t)
        self.qr_label.pack()

        self.info_label = ttk.Label(t, justify="center", font=("Consolas", 10))
        self.info_label.pack(pady=(10, 0))

        ttk.Separator(t, orient="horizontal").pack(fill="x", pady=14, padx=10)

        cam_row = ttk.Frame(t)
        cam_row.pack(pady=6)
        ttk.Button(cam_row, text="Start Camera Mirror", command=self.camera_viewer.start).pack(side="left", padx=4)
        ttk.Button(cam_row, text="Stop Camera Mirror", command=self.camera_viewer.stop).pack(side="left", padx=4)

    def _build_devices_tab(self):
        t = self.devices_tab
        ttk.Label(t, text="Connected device", font=("Segoe UI", 11, "bold")).pack(pady=(14, 6))
        self.device_var = tk.StringVar(value="No device paired yet.")
        ttk.Label(t, textvariable=self.device_var, wraplength=360, justify="center").pack(pady=4)
        ttk.Button(t, text="Refresh", command=self._refresh_devices).pack(pady=10)
        ttk.Label(
            t,
            text="Only one phone can be paired per session.\n"
                 "Restart the app to generate a new pairing code.",
            justify="center",
            foreground="#666",
        ).pack(pady=(20, 0))

    def _build_files_tab(self):
        t = self.files_tab
        ttk.Label(t, text="Send a file to your phone", font=("Segoe UI", 11, "bold")).pack(pady=(14, 6))
        ttk.Button(t, text="Choose File to Send...", command=self._pick_and_send_file).pack(pady=6)

        ttk.Separator(t, orient="horizontal").pack(fill="x", pady=14, padx=10)

        ttk.Label(t, text="Files received from phone", font=("Segoe UI", 11, "bold")).pack(pady=(0, 6))
        ttk.Label(t, text=str(RECEIVE_DIR), foreground="#666").pack()
        ttk.Button(t, text="Open Received Folder", command=self._open_received_folder).pack(pady=10)

    def _build_history_tab(self):
        t = self.history_tab
        ttk.Label(t, text="Recent activity", font=("Segoe UI", 11, "bold")).pack(pady=(12, 6))

        frame = ttk.Frame(t)
        frame.pack(fill="both", expand=True, padx=10, pady=(0, 10))
        scrollbar = ttk.Scrollbar(frame)
        scrollbar.pack(side="right", fill="y")
        self.history_list = tk.Listbox(frame, yscrollcommand=scrollbar.set, font=("Consolas", 9))
        self.history_list.pack(side="left", fill="both", expand=True)
        scrollbar.config(command=self.history_list.yview)

    def _build_settings_tab(self):
        t = self.settings_tab
        ttk.Label(t, text="Feature toggles", font=("Segoe UI", 11, "bold")).pack(pady=(14, 10))

        self.clipboard_enabled_var = tk.BooleanVar(value=True)
        self.notifications_enabled_var = tk.BooleanVar(value=True)

        ttk.Checkbutton(
            t, text="Enable clipboard sync", variable=self.clipboard_enabled_var,
            command=self._apply_settings,
        ).pack(anchor="w", padx=30, pady=4)

        ttk.Checkbutton(
            t, text="Enable notification mirroring", variable=self.notifications_enabled_var,
            command=self._apply_settings,
        ).pack(anchor="w", padx=30, pady=4)

        ttk.Separator(t, orient="horizontal").pack(fill="x", pady=14, padx=10)
        ttk.Label(
            t,
            text="SyncLynk keeps nothing on disk except files you\n"
                 "explicitly send or receive. Restarting the app clears\n"
                 "the pairing code, connection state, and history.",
            justify="center",
            foreground="#666",
        ).pack(pady=(0, 10))

    # ------------------------------------------------------------ actions

    def _apply_settings(self):
        self.clipboard_sync.enabled = self.clipboard_enabled_var.get()
        notification_receiver.set_enabled(self.notifications_enabled_var.get())
        self.history.add(
            f"Settings changed: clipboard={'on' if self.clipboard_sync.enabled else 'off'}, "
            f"notifications={'on' if self.notifications_enabled_var.get() else 'off'}"
        )

    def _pick_and_send_file(self):
        path = filedialog.askopenfilename(title="Choose a file to send to your phone")
        if path:
            self.file_transfer.send_file(path)

    def _open_received_folder(self):
        import os
        import subprocess

        RECEIVE_DIR.mkdir(exist_ok=True)
        try:
            os.startfile(RECEIVE_DIR)  # Windows-only, matches this app's target platform
        except AttributeError:
            subprocess.Popen(["xdg-open", str(RECEIVE_DIR)])

    def _refresh_devices(self):
        count = len(self.server.paired_clients)
        if count == 0:
            self.device_var.set("No device paired yet.")
        else:
            self.device_var.set(f"{count} device(s) paired and syncing.")

    def _show_qr(self):
        uri = self.server.connect_uri
        qr_img = qrcode.make(uri, box_size=6, border=2)
        buf = io.BytesIO()
        qr_img.save(buf, format="PNG")
        buf.seek(0)
        pil_img = Image.open(buf)
        self._qr_photo = ImageTk.PhotoImage(pil_img)  # keep a reference!
        self.qr_label.configure(image=self._qr_photo)

        self.info_label.configure(
            text=f"IP:   {self.server.ip}\nPort: {self.server.port}\nCode: {self.server.token}"
        )

    # ------------------------------------------------------- status pump

    def _queue_status_update(self, text: str):
        # Called from the server's background thread; hand off to the
        # Tkinter thread instead of touching widgets directly.
        self._status_queue.append(text)

    def _poll_queues(self):
        while self._status_queue:
            text = self._status_queue.pop(0)
            self.status_var.set(text)
            self._refresh_devices()
        while self._history_queue:
            line = self._history_queue.pop(0)
            self.history_list.insert(tk.END, line)
            self.history_list.see(tk.END)
        self.root.after(200, self._poll_queues)


def main():
    root = tk.Tk()
    style = ttk.Style()
    try:
        style.theme_use("vista")
    except tk.TclError:
        pass
    SyncLynkApp(root)
    root.mainloop()


if __name__ == "__main__":
    main()
