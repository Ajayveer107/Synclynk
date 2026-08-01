"""
Universal clipboard: PC -> Android and Android -> PC.

We poll the local clipboard on a background thread (pyperclip has no
native "on change" event on Windows) and diff against the last known
value. When we set the clipboard ourselves (because the phone sent us
something), we remember that value so we don't immediately re-broadcast
it back and cause a feedback loop.
"""

import threading
import time
import pyperclip


class ClipboardSync:
    def __init__(self, server, history=None, poll_interval: float = 0.7):
        self.server = server
        self.history = history
        self.poll_interval = poll_interval
        self._last_value = self._safe_paste()
        self._suppress_once = None
        self._stop = False
        self.enabled = True

        server.on_message("clipboard", self._on_remote_clipboard)

    @staticmethod
    def _safe_paste() -> str:
        try:
            return pyperclip.paste()
        except Exception:
            return ""

    def _on_remote_clipboard(self, server, ws, payload):
        """Android sent us its clipboard contents -> apply locally."""
        if not self.enabled:
            return
        text = payload.get("data", "")
        if text == self._last_value:
            return
        self._suppress_once = text
        try:
            pyperclip.copy(text)
        except Exception:
            pass
        self._last_value = text
        if self.history:
            preview = text if len(text) <= 40 else text[:40] + "…"
            self.history.add(f"Clipboard from phone: \"{preview}\"")

    def _loop(self):
        while not self._stop:
            time.sleep(self.poll_interval)
            if not self.enabled:
                continue
            current = self._safe_paste()
            if current == self._last_value:
                continue
            self._last_value = current
            if self._suppress_once == current:
                self._suppress_once = None
                continue  # this change came from the phone, don't echo it back
            self.server.broadcast({"type": "clipboard", "data": current})
            if self.history:
                preview = current if len(current) <= 40 else current[:40] + "…"
                self.history.add(f"Clipboard sent to phone: \"{preview}\"")

    def start(self):
        threading.Thread(target=self._loop, daemon=True).start()

    def stop(self):
        self._stop = True
