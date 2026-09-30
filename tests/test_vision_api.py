"""Local API integration tests. Original image analyzer runs; external admin transport is isolated."""
import importlib.util
import io
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

AVAILABLE = all(importlib.util.find_spec(m) for m in ['fastapi', 'httpx', 'cv2', 'multipart'])

@unittest.skipUnless(AVAILABLE, 'Install backend/requirements.txt and httpx to run API integration tests')
class VisionApiTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        os.environ['UPLOAD_TMP_DIR'] = str(Path(cls.tmp.name)/'uploads')
        os.environ['ALERT_DB_PATH'] = str(Path(cls.tmp.name)/'alerts.sqlite3')
        sys.path.insert(0, str(Path(__file__).resolve().parents[1]/'module4/backend'))
        import main
        from fastapi.testclient import TestClient
        cls.main = main
        cls.client = TestClient(main.app)

    @classmethod
    def tearDownClass(cls):
        cls.client.close()
        cls.tmp.cleanup()

    def test_photo_to_report_uses_original_analyzer_and_forwards_report(self):
        from PIL import Image
        photo = io.BytesIO()
        Image.new('RGB', (128,128), (90,110,80)).save(photo, format='PNG')
        with patch.object(self.main, 'transmit_report', return_value={'accepted': True}) as transport:
            result = self.client.post('/analyze', files={'image': ('test.png',photo.getvalue(),'image/png')},
                                      data={'lat':'28.1','lng':'85.2','user_id':'local-test'})
        self.assertEqual(200, result.status_code, result.text)
        report = result.json()
        self.assertIn('severity_score', report['analysis'])
        self.assertEqual({'lat':28.1,'lng':85.2,'source':'app_gps'}, report['location'])
        self.assertTrue(report['admin_transmission']['transmitted'])
        transport.assert_called_once()
        self.assertFalse(list((Path(self.tmp.name)/'uploads').iterdir()))

    def test_invalid_coordinates_and_base64_are_rejected(self):
        result = self.client.post('/analyze-json', json={'image_base64':'not-base64!!','lat':91})
        self.assertEqual(422,result.status_code)
        result = self.client.post('/analyze-json', json={'image_base64':'not-base64!!'})
        self.assertEqual(400,result.status_code)

    def test_health_alerts_and_websocket_contracts(self):
        self.assertEqual('ok',self.client.get('/health').json()['status'])
        self.assertIsInstance(self.client.get('/alerts').json()['alerts'],list)
        with self.client.websocket_connect('/ws/alerts') as socket:
            self.assertEqual('INIT_ALERTS',socket.receive_json()['type'])

if __name__ == '__main__': unittest.main()
