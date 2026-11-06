"""Validate Molido engine/transport compatibility combinations."""

import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MATRIX = json.loads((ROOT / "config/network/protocol-transport-matrix.json").read_text())


class ProtocolTransportMatrixTests(unittest.TestCase):
    def test_unique_engine_transport_pairs(self):
        pairs = [(x["engine"], x["transport"]) for x in MATRIX["valid_combinations"]]
        self.assertEqual(len(pairs), len(set(pairs)))

    def test_all_initial_ten_engines_have_a_candidate_path(self):
        expected = {
            "vless", "vmess", "trojan", "shadowsocks", "hysteria2",
            "tuic", "anytls", "wireguard", "ikev2", "sstp",
        }
        actual = {x["engine"] for x in MATRIX["valid_combinations"]}
        self.assertTrue(expected.issubset(actual))

    def test_every_combination_has_a_runtime_and_status(self):
        for item in MATRIX["valid_combinations"]:
            self.assertTrue(item["runtime"])
            self.assertTrue(item["status"])

    def test_forbidden_tor_and_mkcp_combinations_are_explicit(self):
        excluded = {(x["engine"], x["transport"]) for x in MATRIX["excluded_combinations"]}
        self.assertIn(("tor", "udp"), excluded)
        self.assertIn(("*", "mkcp"), excluded)

    def test_unimplemented_mtproto_is_not_marked_supported(self):
        item = next(x for x in MATRIX["valid_combinations"] if x["engine"] == "mtproto_proxy")
        self.assertEqual(item["status"], "not_implemented")
        self.assertEqual(item["runtime"], "not_implemented")

    def test_matrix_never_enables_runtime_selection(self):
        self.assertFalse(MATRIX["policy"]["default_enabled"])
        self.assertTrue(MATRIX["policy"]["require_end_to_end_evidence"])


if __name__ == "__main__":
    unittest.main()
