import json
import unittest
from pathlib import Path

CATALOG = Path(__file__).resolve().parents[1] / "config/network/transport-catalog.json"


class TransportCatalogTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
        cls.entries = cls.catalog["entries"]

    def test_ids_are_unique(self):
        ids = [entry["id"] for entry in self.entries]
        self.assertEqual(len(ids), len(set(ids)))

    def test_every_protocol_starts_disabled_and_planned(self):
        self.assertFalse(self.catalog["policy"]["default_enabled"])
        for entry in self.entries:
            self.assertEqual(entry["status"], "planned")
            self.assertTrue(entry["requires_server_support"])
            self.assertGreater(len(entry["tests"]), 0)

    def test_requested_protocols_are_present(self):
        expected = {
            "mtproto_proxy", "tor", "tcp", "udp", "quic", "websocket",
            "grpc", "xhttp", "http2", "http3", "http_upgrade", "mkcp",
        }
        self.assertEqual({entry["id"] for entry in self.entries}, expected)

    def test_quic_and_http3_dependency_is_explicit(self):
        by_id = {entry["id"]: entry for entry in self.entries}
        self.assertIn("udp", by_id["quic"]["transport_dependencies"])
        self.assertIn("quic", by_id["http3"]["transport_dependencies"])

    def test_tor_is_not_declared_as_udp_transport(self):
        tor = next(entry for entry in self.entries if entry["id"] == "tor")
        self.assertEqual(tor["transport_dependencies"], ["tcp"])


if __name__ == "__main__":
    unittest.main()
