"""
Notification mirroring: Android -> PC.

The Android app runs a NotificationListenerService that forwards every
notification it sees (app name, title, text) to us as:
    {"type": "notification", "app": "...", "title": "...", "text": "..."}

We show it as a native Windows toast using `plyer` (cross-platform, no
extra native deps beyond what's already in requirements.txt).
"""

from plyer import notification as plyer_notification

_state = {"enabled": True, "history": None}


def register(server, history=None):
    _state["history"] = history
    server.on_message("notification", _on_notification)


def set_enabled(value: bool):
    _state["enabled"] = value


def _on_notification(server, ws, payload):
    if not _state["enabled"]:
        return

    app = payload.get("app", "App")
    title = payload.get("title", "")
    text = payload.get("text", "")

    try:
        plyer_notification.notify(
            title=f"{app}: {title}" if title else app,
            message=text or " ",
            app_name="SyncLynk",
            timeout=6,
        )
    except Exception as e:
        # Notifications are best-effort; never crash the server over this.
        print(f"[notification_receiver] failed to show toast: {e}")

    if _state["history"]:
        _state["history"].add(f"Notification from {app}: {title}")
