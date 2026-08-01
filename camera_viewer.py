"""
Camera mirroring: Android's camera -> live window on PC.

The phone streams JPEG frames base64-encoded inside JSON messages:
    {"type": "camera_frame", "data": "<base64 jpeg bytes>"}

We decode each frame and show it in an OpenCV window on its own thread
so it never blocks the asyncio server or the Tkinter GUI. A "poison
pill" (None) placed on the queue tells the render thread to exit.
"""

import base64
import queue
import threading

import cv2
import numpy as np

WINDOW_NAME = "SyncLynk - Phone Camera"


class CameraViewer:
    def __init__(self, server, history=None):
        self.server = server
        self.history = history
        self._frame_q: "queue.Queue" = queue.Queue(maxsize=2)
        self._running = False
        server.on_message("camera_frame", self._on_frame)

    def _on_frame(self, server, ws, payload):
        if not self._running:
            return
        b64 = payload.get("data")
        if not b64:
            return
        try:
            jpg_bytes = base64.b64decode(b64)
        except Exception:
            return
        # Drop the oldest frame if the render thread is falling behind,
        # so the stream stays "live" instead of buffering delay.
        if self._frame_q.full():
            try:
                self._frame_q.get_nowait()
            except queue.Empty:
                pass
        self._frame_q.put(jpg_bytes)

    def _render_loop(self):
        cv2.namedWindow(WINDOW_NAME, cv2.WINDOW_NORMAL)
        while self._running:
            try:
                jpg_bytes = self._frame_q.get(timeout=0.5)
            except queue.Empty:
                if cv2.getWindowProperty(WINDOW_NAME, cv2.WND_PROP_VISIBLE) < 1:
                    break
                continue
            arr = np.frombuffer(jpg_bytes, dtype=np.uint8)
            frame = cv2.imdecode(arr, cv2.IMREAD_COLOR)
            if frame is None:
                continue
            cv2.imshow(WINDOW_NAME, frame)
            if cv2.waitKey(1) & 0xFF == ord("q"):
                break
            if cv2.getWindowProperty(WINDOW_NAME, cv2.WND_PROP_VISIBLE) < 1:
                break
        cv2.destroyWindow(WINDOW_NAME)
        self._running = False
        self.server.broadcast({"type": "camera_stop"})
        if self.history:
            self.history.add("Camera mirror stopped.")

    def start(self):
        if self._running:
            return
        self._running = True
        self.server.broadcast({"type": "camera_start"})
        if self.history:
            self.history.add("Camera mirror started.")
        threading.Thread(target=self._render_loop, daemon=True).start()

    def stop(self):
        self._running = False
