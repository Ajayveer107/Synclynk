"""
SyncLynk - Windows Companion Server
------------------------------------
A LAN-only WebSocket server with no accounts and no database.

Pairing model:
  * On startup we generate a random one-time PAIR_TOKEN (in memory only).
  * We show it encoded in a QR code (synclynk://<ip>:<port>/<token>).
  * The Android app scans the QR, connects to ws://<ip>:<port>,
    and sends {"type":"pair","token": "..."} as its first message.
  * If the token matches, the socket is marked "paired" and can
    exchange clipboard / notification / camera messages.
  * Nothing is persisted to disk. Restarting the app = new token.

This keeps things "no login, no database" while still preventing a
random device on the same Wi-Fi from silently attaching to your PC.
"""

import asyncio
import json
import secrets
import socket
import logging

import websockets

logging.basicConfig(level=logging.INFO, format="[%(asctime)s] %(message)s")
log = logging.getLogger("synclynk")

HOST = "0.0.0.0"
PORT = 8765


def get_local_ip() -> str:
    """Best-effort LAN IP discovery (no packets actually sent)."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
    except Exception:
        ip = "127.0.0.1"
    finally:
        s.close()
    return ip


class SyncLynkServer:
    """
    Owns the websocket server, the pairing token, and a registry of
    connected (and paired) clients. GUI / feature modules register
    callbacks via `on_message(type, handler)` and push outbound data
    via `broadcast(msg_dict)`.
    """

    def __init__(self):
        self.token = secrets.token_hex(4)  # short, human/QR friendly
        self.ip = get_local_ip()
        self.port = PORT
        self.clients: set[websockets.WebSocketServerProtocol] = set()
        self.paired_clients: set[websockets.WebSocketServerProtocol] = set()
        self._handlers: dict[str, list] = {}
        self._loop: asyncio.AbstractEventLoop | None = None
        self.status_callback = None  # fn(str) -> None, for GUI status updates

    @property
    def connect_uri(self) -> str:
        return f"synclynk://{self.ip}:{self.port}/{self.token}"

    def on_message(self, msg_type: str, handler):
        """Register a callback: handler(server, websocket, payload_dict)."""
        self._handlers.setdefault(msg_type, []).append(handler)

    def _set_status(self, text: str):
        log.info(text)
        if self.status_callback:
            self.status_callback(text)

    async def _handle_client(self, ws: "websockets.WebSocketServerProtocol"):
        self.clients.add(ws)
        peer = ws.remote_address
        self._set_status(f"Device connecting from {peer}...")
        try:
            # First message MUST be the pairing handshake.
            raw = await asyncio.wait_for(ws.recv(), timeout=15)
            msg = json.loads(raw)
            if msg.get("type") != "pair" or msg.get("token") != self.token:
                await ws.send(json.dumps({"type": "pair_result", "ok": False}))
                self._set_status("Pairing rejected (bad/missing token).")
                await ws.close()
                return

            self.paired_clients.add(ws)
            await ws.send(json.dumps({"type": "pair_result", "ok": True}))
            self._set_status(f"Paired with {peer}. SyncLynk is live.")

            async for raw in ws:
                try:
                    payload = json.loads(raw)
                except json.JSONDecodeError:
                    continue
                msg_type = payload.get("type")
                for handler in self._handlers.get(msg_type, []):
                    try:
                        handler(self, ws, payload)
                    except Exception as e:  # never let one bad handler kill the loop
                        log.exception("Handler error for %s: %s", msg_type, e)

        except asyncio.TimeoutError:
            self._set_status("Pairing timed out, no handshake received.")
        except websockets.exceptions.ConnectionClosed:
            pass
        finally:
            self.clients.discard(ws)
            self.paired_clients.discard(ws)
            self._set_status("Device disconnected.")

    def broadcast(self, msg: dict):
        """Send a JSON message to every paired client. Fire-and-forget."""
        if not self.paired_clients or self._loop is None:
            return
        data = json.dumps(msg)
        for ws in list(self.paired_clients):
            asyncio.run_coroutine_threadsafe(self._safe_send(ws, data), self._loop)

    @staticmethod
    async def _safe_send(ws, data):
        try:
            await ws.send(data)
        except Exception:
            pass

    async def _run(self):
        self._loop = asyncio.get_running_loop()
        async with websockets.serve(self._handle_client, HOST, self.port, max_size=8 * 1024 * 1024):
            self._set_status(f"Server running on {self.ip}:{self.port} — waiting for pairing.")
            await asyncio.Future()  # run forever

    def run_in_background_thread(self):
        """Starts the asyncio server on a dedicated thread so it can live
        alongside a Tkinter GUI mainloop on the main thread."""
        import threading

        def runner():
            asyncio.run(self._run())

        t = threading.Thread(target=runner, daemon=True)
        t.start()
        return t
