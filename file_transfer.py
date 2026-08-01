"""
File transfer, both directions, chunked over the existing WebSocket so
large files don't need to be held fully in memory.

Sending (PC -> phone): call `FileTransfer.send_file(path)` -- runs on a
background thread so it never blocks the Tkinter GUI.

Receiving (phone -> PC): handled automatically via the registered
"file_start" / "file_chunk" / "file_end" message handlers. Completed
files land in `~/SyncLynk Received/`.
"""

import base64
import os
import secrets
import threading
from pathlib import Path

CHUNK_SIZE = 48 * 1024  # raw bytes per chunk, before base64 inflation
RECEIVE_DIR = Path.home() / "SyncLynk Received"


class FileTransfer:
    def __init__(self, server, on_event=None):
        """
        on_event(text: str) -- optional callback for logging to the UI
        (e.g. the History tab), called for both sends and receives.
        """
        self.server = server
        self.on_event = on_event or (lambda text: None)
        self._incoming: dict[str, dict] = {}  # id -> {"path": Path, "handle": file, "name": str}

        RECEIVE_DIR.mkdir(exist_ok=True)

        server.on_message("file_start", self._on_file_start)
        server.on_message("file_chunk", self._on_file_chunk)
        server.on_message("file_end", self._on_file_end)

    # ------------------------------------------------------------ sending

    def send_file(self, path: str):
        threading.Thread(target=self._send_file_blocking, args=(path,), daemon=True).start()

    def _send_file_blocking(self, path: str):
        p = Path(path)
        if not p.is_file():
            self.on_event(f"Send failed: {p.name} not found.")
            return

        file_id = secrets.token_hex(4)
        size = p.stat().st_size
        self.server.broadcast({"type": "file_start", "id": file_id, "name": p.name, "size": size})
        self.on_event(f"Sending {p.name} ({size // 1024} KB)...")

        seq = 0
        with open(p, "rb") as f:
            while chunk := f.read(CHUNK_SIZE):
                b64 = base64.b64encode(chunk).decode("ascii")
                self.server.broadcast({"type": "file_chunk", "id": file_id, "seq": seq, "data": b64})
                seq += 1

        self.server.broadcast({"type": "file_end", "id": file_id})
        self.on_event(f"Sent {p.name}.")

    # ---------------------------------------------------------- receiving

    def _on_file_start(self, server, ws, payload):
        file_id = payload.get("id")
        name = payload.get("name", f"file_{file_id}")
        # Sanitize the name -- never trust a filename off the network verbatim.
        safe_name = os.path.basename(name).replace("..", "_") or f"file_{file_id}"
        tmp_path = RECEIVE_DIR / f".{file_id}.part"
        handle = open(tmp_path, "wb")
        self._incoming[file_id] = {"handle": handle, "tmp_path": tmp_path, "name": safe_name}
        self.on_event(f"Receiving {safe_name}...")

    def _on_file_chunk(self, server, ws, payload):
        file_id = payload.get("id")
        entry = self._incoming.get(file_id)
        if not entry:
            return
        try:
            entry["handle"].write(base64.b64decode(payload.get("data", "")))
        except Exception as e:
            self.on_event(f"Error writing chunk for {entry['name']}: {e}")

    def _on_file_end(self, server, ws, payload):
        file_id = payload.get("id")
        entry = self._incoming.pop(file_id, None)
        if not entry:
            return
        entry["handle"].close()
        final_path = RECEIVE_DIR / entry["name"]
        # Avoid clobbering an existing file with the same name.
        counter = 1
        while final_path.exists():
            stem, suffix = os.path.splitext(entry["name"])
            final_path = RECEIVE_DIR / f"{stem} ({counter}){suffix}"
            counter += 1
        entry["tmp_path"].rename(final_path)
        self.on_event(f"Received {final_path.name} -> {RECEIVE_DIR}")
