"""Durable recent alert history. Uses per-operation connections for worker safety."""
import json
import sqlite3
from pathlib import Path


class AlertStore:
    def __init__(self, path, limit=200):
        self.path = str(path)
        self.limit = limit
        if limit < 1:
            raise ValueError('limit must be positive')
        Path(self.path).parent.mkdir(parents=True, exist_ok=True)
        with self._connect() as db:
            db.execute('CREATE TABLE IF NOT EXISTS alerts (seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE, payload TEXT NOT NULL)')

    def _connect(self):
        return sqlite3.connect(self.path, timeout=10)

    def add(self, alert):
        payload = json.dumps(alert, allow_nan=False)
        alert_id = str(alert['id'])
        if not alert_id:
            raise ValueError('alert id is required')
        with self._connect() as db:
            db.execute('INSERT INTO alerts (id,payload) VALUES (?,?) ON CONFLICT(id) DO UPDATE SET payload=excluded.payload', (alert_id, payload))
            db.execute('DELETE FROM alerts WHERE seq NOT IN (SELECT seq FROM alerts ORDER BY seq DESC LIMIT ?)', (self.limit,))

    def recent(self, limit=50):
        with self._connect() as db:
            rows = db.execute('SELECT payload FROM alerts ORDER BY seq DESC LIMIT ?', (max(0, limit),)).fetchall()
        return [json.loads(row[0]) for row in reversed(rows)]
