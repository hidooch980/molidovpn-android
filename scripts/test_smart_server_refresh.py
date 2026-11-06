import unittest
from smart_server_refresh import analyze, parse_entry

class SmartServerRefreshTests(unittest.TestCase):
    def setUp(self):
        self.good = [
            "vless://11111111-1111-4111-8111-111111111111@188.114.98.0:443?security=tls&type=ws&host=a.example.com&sni=a.example.com#v4",
            "vless://11111111-1111-4111-8111-111111111111@[2a06:98c1:3120::7]:443?security=tls&type=ws&host=a.example.com&sni=a.example.com#v6",
            "vless://22222222-2222-4222-8222-222222222222@188.114.98.0:443?security=tls&type=ws&host=b.example.com&sni=b.example.com#node2",
            "trojan://secret@188.114.98.0:443?security=tls&type=ws&host=c.example.com&sni=c.example.com#node3",
        ]

    def test_accepts_public_and_approved_local_relay(self):
        self.assertEqual(parse_entry(self.good[0])[0], "vless")
        local = "vless://id@127.0.0.1:40443?security=tls&type=ws&host=a.example.com&sni=a.example.com"
        self.assertEqual(parse_entry(local)[0], "vless")

    def test_rejects_unsupported_protocol_and_non_ws_tls(self):
        with self.assertRaises(ValueError):
            parse_entry("ss://secret@example.com:443")
        with self.assertRaisesRegex(ValueError, "WebSocket over TLS"):
            parse_entry("vless://id@example.com:443?type=tcp&security=none")

    def test_rejects_private_and_unexpected_loopback_endpoints(self):
        for endpoint in ("10.0.0.2:443", "192.168.1.4:443", "127.0.0.1:443"):
            with self.subTest(endpoint=endpoint), self.assertRaises(ValueError):
                parse_entry(f"vless://id@{endpoint}?security=tls&type=ws&host=a.example.com&sni=a.example.com")

    def test_rejects_bad_sni_and_invalid_port(self):
        with self.assertRaises(ValueError):
            parse_entry("vless://id@188.114.98.0:443?security=tls&type=ws&host=localhost&sni=localhost")
        with self.assertRaises(ValueError):
            parse_entry("vless://id@example.com:99999?security=tls&type=ws&host=a.example.com")

    def test_collapses_ip_variants_of_one_logical_node(self):
        report = analyze("\n".join(self.good))
        self.assertEqual(report["status"], "pass")
        self.assertEqual(report["valid_configs"], 4)
        self.assertEqual(report["unique_logical_nodes"], 3)

    def test_duplicate_and_invalid_only_catalog_is_rejected(self):
        report = analyze("\n".join([self.good[0], self.good[0], "not-a-profile"]))
        self.assertEqual(report["status"], "reject")
        self.assertEqual(report["valid_configs"], 1)
        self.assertEqual(report["exact_duplicates"], 1)

    def test_rejects_catalog_when_most_entries_are_invalid(self):
        report = analyze("\n".join(self.good + ["bad"] * 4))
        self.assertEqual(report["status"], "reject")

if __name__ == "__main__":
    unittest.main()
