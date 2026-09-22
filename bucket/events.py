"""Fan-out of item events to connected clients over SSE.

Each subscriber gets a bounded queue. If a client stalls badly enough to fill
it, that client is dropped rather than growing the queue without limit — a
phone that went to sleep must not be able to exhaust PC memory. It reconnects
and refetches the list, so nothing is actually lost.
"""

from __future__ import annotations

import json
import queue
import threading

QUEUE_DEPTH = 64
HEARTBEAT_SECONDS = 20.0


class Broker:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._subscribers: set[queue.Queue] = set()

    def subscribe(self) -> queue.Queue:
        q: queue.Queue = queue.Queue(maxsize=QUEUE_DEPTH)
        with self._lock:
            self._subscribers.add(q)
        return q

    def unsubscribe(self, q: queue.Queue) -> None:
        with self._lock:
            self._subscribers.discard(q)

    def publish(self, event: str, payload: dict) -> None:
        message = f"event: {event}\ndata: {json.dumps(payload)}\n\n"
        with self._lock:
            targets = list(self._subscribers)
        for q in targets:
            try:
                q.put_nowait(message)
            except queue.Full:
                self.unsubscribe(q)

    def stream(self, q: queue.Queue):
        """Yield SSE frames. The heartbeat is a comment line, which clients
        ignore but proxies and sleeping radios treat as traffic, keeping the
        connection from being reaped silently."""
        yield "retry: 2000\n\n"
        try:
            while True:
                try:
                    yield q.get(timeout=HEARTBEAT_SECONDS)
                except queue.Empty:
                    yield ": ping\n\n"
        finally:
            self.unsubscribe(q)
