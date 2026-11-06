import unittest

from transport_gate import evaluate


def valid_report():
    profile = {
        "network_type": "wifi",
        "sample_count": 12,
        "handshake_success_rate": 0.99,
        "reconnect_success_rate": 0.98,
        "median_rtt_ms": 80,
        "p95_rtt_ms": 140,
        "median_download_mbps": 35.0,
        "median_upload_mbps": 12.0,
        "packet_loss_pct": 0.5,
        "soak_minutes": 35,
        "battery_drain_percent_per_hour": 4.0,
    }
    return {
        "schema_version": 1,
        "transport_id": "quic",
        "test_run_id": "lab-2026-001",
        "client_version": "4.0.5",
        "server_version": "gateway-test-1",
        "server_region": "test-region",
        "device_model": "test-device",
        "android_api": 35,
        "abi": "arm64-v8a",
        "captured_at_utc": "2026-11-06T00:00:00Z",
        "security": {
            "certificate_validation": True,
            "authentication_success": True,
            "data_plane_verified": True,
            "dns_leak_free": True,
            "ip_leak_free": True,
            "plaintext_fallback_disabled": True,
        },
        "network_profiles": [
            {"profile_id": "wifi-a", **profile},
            {"profile_id": "cell-b", **{**profile, "network_type": "cellular"}},
        ],
    }


class TransportGateTests(unittest.TestCase):
    def test_complete_report_is_eligible_for_manual_review_only(self):
        result = evaluate(valid_report())
        self.assertTrue(result["eligible_for_manual_review"])
        self.assertFalse(result["validated"])
        self.assertEqual(result["runtime_activation"], "never_automatic")

    def test_security_failure_blocks_review(self):
        report = valid_report()
        report["security"]["dns_leak_free"] = False
        result = evaluate(report)
        self.assertFalse(result["eligible_for_manual_review"])
        self.assertTrue(any("dns_leak_free" in reason for reason in result["reasons"]))

    def test_single_network_profile_is_insufficient(self):
        report = valid_report()
        report["network_profiles"] = report["network_profiles"][:1]
        self.assertFalse(evaluate(report)["eligible_for_manual_review"])

    def test_short_soak_is_insufficient(self):
        report = valid_report()
        report["network_profiles"][0]["soak_minutes"] = 10
        self.assertFalse(evaluate(report)["eligible_for_manual_review"])

    def test_packet_loss_regression_blocks_review(self):
        report = valid_report()
        report["network_profiles"][1]["packet_loss_pct"] = 12
        self.assertFalse(evaluate(report)["eligible_for_manual_review"])

    def test_unlisted_transport_is_rejected(self):
        report = valid_report()
        report["transport_id"] = "made-up-protocol"
        self.assertFalse(evaluate(report)["eligible_for_manual_review"])

    def test_success_rate_above_one_is_rejected(self):
        report = valid_report()
        report["network_profiles"][0]["handshake_success_rate"] = 1.5
        self.assertFalse(evaluate(report)["eligible_for_manual_review"])

    def test_missing_profile_identity_is_rejected(self):
        report = valid_report()
        report["network_profiles"][0].pop("profile_id")
        self.assertFalse(evaluate(report)["eligible_for_manual_review"])

    def test_missing_device_api_or_abi_is_rejected(self):
        report = valid_report()
        report.pop("android_api")
        report.pop("abi")
        self.assertFalse(evaluate(report)["eligible_for_manual_review"])


if __name__ == "__main__":
    unittest.main()
