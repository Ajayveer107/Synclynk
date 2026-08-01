"""
A tiny in-memory activity log shared by every feature module, so the
History tab has one place to pull from. Nothing is written to disk --
closing the app clears it, same "no database" philosophy as the rest
of SyncLynk.
"""

import datetime
import threading


class HistoryLog:
    def __init__(self, max_entries: int = 200):
        self._entries: list[str] = []
        self._lock = threading.Lock()
        self._listeners = []
        self.max_entries = max_entries

    def add(self, text: str):
        stamp = datetime.datetime.now().strftime("%H:%M:%S")
        line = f"[{stamp}] {text}"
        with self._lock:
            self._entries.append(line)
            if len(self._entries) > self.max_entries:
                self._entries.pop(0)
        for listener in self._listeners:
            listener(line)

    def all(self) -> list[str]:
        with self._lock:
            return list(self._entries)

    def add_listener(self, fn):
        """fn(line: str) is called every time a new entry is added."""
        self._listeners.append(fn)
