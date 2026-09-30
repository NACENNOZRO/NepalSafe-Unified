import concurrent.futures
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'module4/backend'))
from alert_store import AlertStore


class AlertStoreTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.path = Path(self.tmp.name) / 'history.sqlite3'
        self.store = AlertStore(self.path, limit=50)

    def tearDown(self):
        self.tmp.cleanup()

    def test_survives_restart_and_preserves_location(self):
        event = {'id': 'a', 'location': {'lat': 28.1, 'lng': 85.1}, 'severity': 'moderate'}
        self.store.add(event)
        self.assertEqual([event], AlertStore(self.path).recent())

    def test_duplicate_event_does_not_duplicate_alert(self):
        self.store.add({'id': 'a', 'severity': 'moderate'})
        self.store.add({'id': 'a', 'severity': 'high'})
        self.assertEqual([{'id': 'a', 'severity': 'high'}], self.store.recent())

    def test_history_retains_latest_events_in_chronological_order(self):
        for i in range(70):
            self.store.add({'id': str(i)})
        self.assertEqual([str(i) for i in range(20, 70)], [x['id'] for x in self.store.recent()])

    def test_concurrent_writers_do_not_lose_events(self):
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as workers:
            list(workers.map(lambda i: self.store.add({'id': str(i)}), range(40)))
        self.assertEqual({str(i) for i in range(40)}, {x['id'] for x in self.store.recent()})

    def test_invalid_record_does_not_corrupt_history(self):
        self.store.add({'id': 'good'})
        with self.assertRaises(ValueError):
            self.store.add({'id': 'bad', 'severity_score': float('nan')})
        self.assertEqual([{'id': 'good'}], self.store.recent())

if __name__ == '__main__':
    unittest.main()
