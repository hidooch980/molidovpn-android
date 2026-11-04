import base64
import hashlib
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat
from fastapi import HTTPException
from pydantic import ValidationError

import app as api
from app import Observation


def make_observation(**overrides):
    values = {
        "protocol": "ikev2",
        "endpoint_host": "vpn.example.com",
        "port": 500,
        "result": "connected",
        "rtt_ms": 42,
        "loss_percent": 0.0,
        "observed_at": int(time.time()),
    }
    values.update(overrides)
    return Observation(**values)


class ObservationTests(unittest.TestCase):
    def test_public_hostname_normalized(self):
        self.assertEqual(make_observation(endpoint_host="VPN.Example.com.").endpoint_host, "vpn.example.com")

    def test_public_ip_allowed_and_private_ip_rejected(self):
        self.assertEqual(make_observation(endpoint_host="8.8.8.8").endpoint_host, "8.8.8.8")
        with self.assertRaises(ValidationError):
            make_observation(endpoint_host="127.0.0.1")

    def test_unknown_fields_rejected(self):
        with self.assertRaises(ValidationError):
            make_observation(password="secret")

    def test_protocol_scopes_endpoint(self):
        a = make_observation()
        b = make_observation(protocol="wireguard")
        def endpoint_id(o):
            return hashlib.sha256(f"{o.protocol}|{o.endpoint_host}|{o.port}".encode()).hexdigest()
        self.assertNotEqual(endpoint_id(a), endpoint_id(b))


class DiscoveryApiTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.db = Path(self.tmp.name) / "test.sqlite3"
        self.key = api.Ed25519PrivateKey.generate()
        self.key_bytes = self.key.private_bytes(
            Encoding.Raw,
            api.serialization.PrivateFormat.Raw,
            api.serialization.NoEncryption(),
        )
        self.patches = [
            patch.object(api, "DB", self.db),
            patch.object(api, "PEPPER", "test-pepper-value-that-is-long-enough-123456"),
            patch.object(api, "KEY", base64.b64encode(self.key_bytes).decode()),
            patch.object(api, "MIN_REPORTERS", 3),
        ]
        for item in self.patches:
            item.start()

    def tearDown(self):
        for item in reversed(self.patches):
            item.stop()
        self.tmp.cleanup()

    def test_directory_hidden_until_three_distinct_reporters(self):
        observation = make_observation()
        for token in ("reporter-token-0000000000000001", "reporter-token-0000000000000002"):
            api.report(observation, token)
        response = api.Response()
        self.assertEqual(api.get_directory(response)["payload"]["entries"], [])
        api.report(observation, "reporter-token-0000000000000003")
        directory = api.get_directory(api.Response())
        self.assertEqual(len(directory["payload"]["entries"]), 1)
        self.assertEqual(directory["payload"]["entries"][0]["confidence"], "community_reported")

    def test_duplicate_observation_rejected(self):
        observation = make_observation()
        api.report(observation, "reporter-token-0000000000000001")
        with self.assertRaises(HTTPException) as caught:
            api.report(observation, "reporter-token-0000000000000001")
        self.assertEqual(caught.exception.status_code, 409)

    def test_stale_observation_rejected(self):
        observation = make_observation(observed_at=int(time.time()) - 901)
        with self.assertRaises(HTTPException) as caught:
            api.report(observation, "reporter-token-0000000000000001")
        self.assertEqual(caught.exception.status_code, 422)

    def test_report_quota_enforced(self):
        token = "reporter-token-0000000000000001"
        for index in range(30):
            api.report(make_observation(observed_at=int(time.time()) + index), token)
        with self.assertRaises(HTTPException) as caught:
            api.report(make_observation(observed_at=int(time.time()) + 31), token)
        self.assertEqual(caught.exception.status_code, 429)

    def test_directory_signature_verifies_with_configured_key(self):
        for index in range(3):
            api.report(make_observation(), f"reporter-token-000000000000000{index + 1}")
        directory = api.get_directory(api.Response())
        payload = directory["payload"]
        canonical = api.json.dumps(payload, sort_keys=True, separators=(",", ":")).encode()
        public_key = self.key.public_key()
        public_key.verify(base64.b64decode(directory["signature"]), canonical)
        self.assertEqual(base64.b64decode(directory["public_key"]), public_key.public_bytes(Encoding.Raw, PublicFormat.Raw))

    def test_missing_signing_key_fails_closed(self):
        with patch.object(api, "KEY", ""):
            with self.assertRaises(HTTPException) as caught:
                api.get_directory(api.Response())
        self.assertEqual(caught.exception.status_code, 503)


if __name__ == "__main__":
    unittest.main()
