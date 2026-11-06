import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CATALOG_PATH = ROOT / "config/network/transport-catalog.json"


class TransportCatalogTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.catalog = json.loads(CATALOG_PATH.read_text(encoding="utf-8"))
        cls.entries = cls.catalog["entries"]
        cls.by_id = {entry["id"]: entry for entry in cls.entries}

    def test_requested_protocols_are_present_once(self):
        expected = {
            "mtproto_proxy",
            "tor",
            "tcp",
            "udp",
            "quic",
            "websocket",
            "grpc",
            "xhttp",
            "http2",
            "http3",
            "http_upgrade",
            "mkcp",
        }
        self.assertEqual(set(self.by_id), expected)
        self.assertEqual(len(self.entries), len(expected))

    def test_catalog_never_activates_protocols_by_presence(self):
        self.assertFalse(self.catalog["policy"]["default_enabled"])
        self.assertFalse(self.catalog["policy"]["runtime_selectable_by_this_catalog"])
        for entry in self.entries:
            self.assertFalse(entry["runtime_selectable"])

    def test_implemented_claims_have_real_source_evidence(self):
        for entry in self.entries:
            if not entry["implementation_status"].startswith("implemented"):
                continue
            self.assertTrue(entry["integration_evidence"], entry["id"])
            for evidence in entry["integration_evidence"]:
                source_path = ROOT / evidence["path"]
                self.assertTrue(source_path.is_file(), evidence["path"])
                source = source_path.read_text(encoding="utf-8", errors="replace")
                self.assertIn(evidence["marker"], source, entry["id"])

    def test_existing_test_references_resolve(self):
        for entry in self.entries:
            for test_path in entry["existing_tests"]:
                self.assertTrue((ROOT / test_path).is_file(), test_path)

    def test_only_unimplemented_options_are_mtproto_and_mkcp(self):
        missing = {
            entry["id"]
            for entry in self.entries
            if entry["implementation_status"]
            == "not_implemented_in_current_client_source_scan"
        }
        self.assertEqual(missing, {"mtproto_proxy", "mkcp"})

    def test_quic_and_http3_dependencies_are_explicit(self):
        self.assertIn("udp", self.by_id["quic"]["transport_dependencies"])
        self.assertIn("quic", self.by_id["http3"]["transport_dependencies"])

    def test_tor_is_not_declared_as_a_general_udp_transport(self):
        self.assertEqual(self.by_id["tor"]["transport_dependencies"], ["tcp"])

    def test_http2_is_not_claimed_as_fully_validated(self):
        self.assertEqual(
            self.by_id["http2"]["implementation_status"],
            "implemented_experimental_path",
        )
        self.assertIn(
            "known_gateway_compatibility_limitations",
            self.by_id["http2"]["interop_status"],
        )


if __name__ == "__main__":
    unittest.main()
