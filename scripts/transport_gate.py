"""Evaluate a sanitized transport benchmark for manual promotion review."""

from __future__ import annotations

import argparse
import json
import math
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / "config/network/transport-catalog.json"
SECURITY_CHECKS = (
    "certificate_validation",
    "authentication_success",
    "data_plane_verified",
    "dns_leak_free",
    "ip_leak_free",
    "plaintext_fallback_disabled",
)


def _number(value: Any) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


def evaluate(report: dict[str, Any]) -> dict[str, Any]:
    """Return a manual-review decision; this tool never enables a transport."""
    reasons: list[str] = []
    catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
    known_ids = {entry["id"] for entry in catalog["entries"]}

    if report.get("schema_version") != 1:
        reasons.append("schema_version must be 1")
    transport_id = report.get("transport_id")
    if transport_id not in known_ids:
        reasons.append("transport_id is not present in the Molido catalog")

    for field in ("test_run_id", "client_version", "server_version", "device_model", "captured_at_utc"):
        if not isinstance(report.get(field), str) or not report[field].strip():
            reasons.append(f"{field} is required")

    security = report.get("security")
    if not isinstance(security, dict):
        reasons.append("security object is required")
    else:
        for check in SECURITY_CHECKS:
            if security.get(check) is not True:
                reasons.append(f"security.{check} must be true")

    profiles = report.get("network_profiles")
    if not isinstance(profiles, list) or len(profiles) < 2:
        reasons.append("at least two independent network_profiles are required")
        profiles = []

    profile_ids = [
        item.get("profile_id")
        for item in profiles
        if isinstance(item, dict) and isinstance(item.get("profile_id"), str)
    ]
    if len(profile_ids) != len(set(profile_ids)):
        reasons.append("network profile IDs must be unique")

    required_numbers = (
        "sample_count",
        "handshake_success_rate",
        "reconnect_success_rate",
        "median_rtt_ms",
        "p95_rtt_ms",
        "median_download_mbps",
        "median_upload_mbps",
        "packet_loss_pct",
        "soak_minutes",
        "battery_drain_percent_per_hour",
    )
    for index, profile in enumerate(profiles):
        prefix = f"network_profiles[{index}]"
        if not isinstance(profile, dict):
            reasons.append(f"{prefix} must be an object")
            continue
        if not isinstance(profile.get("network_type"), str) or not profile["network_type"].strip():
            reasons.append(f"{prefix}.network_type is required")
        for field in required_numbers:
            if not _number(profile.get(field)):
                reasons.append(f"{prefix}.{field} must be a finite number")
        if not all(_number(profile.get(field)) for field in required_numbers):
            continue
        if profile["sample_count"] < 10:
            reasons.append(f"{prefix} needs at least 10 samples")
        if profile["handshake_success_rate"] < 0.95:
            reasons.append(f"{prefix} handshake success must be at least 95%")
        if profile["reconnect_success_rate"] < 0.95:
            reasons.append(f"{prefix} reconnect success must be at least 95%")
        if profile["median_rtt_ms"] <= 0 or profile["p95_rtt_ms"] < profile["median_rtt_ms"]:
            reasons.append(f"{prefix} RTT values are invalid")
        if profile["median_download_mbps"] <= 0 or profile["median_upload_mbps"] <= 0:
            reasons.append(f"{prefix} must show non-zero upload and download throughput")
        if not 0 <= profile["packet_loss_pct"] <= 5:
            reasons.append(f"{prefix} packet loss must be between 0 and 5 percent")
        if profile["soak_minutes"] < 30:
            reasons.append(f"{prefix} requires at least a 30-minute stability soak")
        if profile["battery_drain_percent_per_hour"] < 0:
            reasons.append(f"{prefix} battery drain cannot be negative")

    return {
        "transport_id": transport_id,
        "eligible_for_manual_review": not reasons,
        "validated": False,
        "runtime_activation": "never_automatic",
        "reasons": reasons,
        "profile_count": len(profiles),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--report", required=True)
    parser.add_argument("--output")
    args = parser.parse_args()
    report = json.loads(Path(args.report).read_text(encoding="utf-8"))
    result = evaluate(report)
    encoded = json.dumps(result, indent=2)
    if args.output:
        Path(args.output).write_text(encoded + "\n", encoding="utf-8")
    print(encoded)
    return 0 if result["eligible_for_manual_review"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
