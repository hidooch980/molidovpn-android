#!/usr/bin/env python3
"""Fail-closed validator and quality report for the published SHARD server pool."""
from __future__ import annotations
import argparse, hashlib, ipaddress, json, re, sys
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

SUPPORTED = {"vless", "trojan"}
DNS_LABEL = re.compile(r"^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$", re.I)
LOCAL_RELAY_PORT = 40443

def valid_host(value: str) -> bool:
    value = value.rstrip(".")
    if not value or len(value) > 253 or value.lower() in {"localhost", "localhost.localdomain"}:
        return False
    try:
        address = ipaddress.ip_address(value)
    except ValueError:
        if value.lower().endswith((".local", ".localhost", ".internal")):
            return False
        return all(DNS_LABEL.fullmatch(label) for label in value.split("."))
    return address.is_global

def parse_entry(line: str) -> tuple[str, tuple[str, ...]]:
    raw = line.split("#", 1)[0].strip()
    parsed = urlsplit(raw)
    scheme = parsed.scheme.lower()
    if scheme not in SUPPORTED:
        raise ValueError("unsupported protocol")
    if not parsed.username or not parsed.hostname:
        raise ValueError("missing credential or endpoint host")
    try:
        port = parsed.port
    except ValueError as exc:
        raise ValueError("invalid port") from exc
    if port is None or not 1 <= port <= 65535:
        raise ValueError("port outside 1..65535")
    endpoint = parsed.hostname
    try:
        endpoint_ip = ipaddress.ip_address(endpoint)
    except ValueError:
        endpoint_ip = None
    if endpoint_ip is not None:
        if not (endpoint_ip.is_global or (endpoint_ip.is_loopback and port == LOCAL_RELAY_PORT)):
            raise ValueError("non-public endpoint")
    elif not valid_host(endpoint):
        raise ValueError("invalid endpoint hostname")
    query = parse_qs(parsed.query, keep_blank_values=True)
    if query.get("type", ["tcp"])[0].lower() != "ws" or query.get("security", ["none"])[0].lower() != "tls":
        raise ValueError("SHARD requires WebSocket over TLS")
    host = query.get("host", [""])[0].strip()
    sni = query.get("sni", [host])[0].strip()
    if not valid_host(host) or not valid_host(sni):
        raise ValueError("missing or invalid WebSocket host/SNI")
    identity = (scheme, parsed.username, host.lower(), sni.lower(),
                query.get("path", [""])[0], query.get("serviceName", [""])[0])
    return scheme, identity

def analyze(text: str, min_configs: int = 3, min_nodes: int = 3) -> dict:
    lines = [line.strip() for line in text.splitlines() if line.strip() and not line.lstrip().startswith("#")]
    valid, rejected, exact_seen, duplicates = [], Counter(), set(), 0
    for line in lines:
        canonical = line.split("#", 1)[0].strip()
        if canonical in exact_seen:
            duplicates += 1
            continue
        exact_seen.add(canonical)
        try:
            valid.append(parse_entry(line))
        except (ValueError, TypeError) as exc:
            rejected[str(exc)] += 1
    nodes = {identity for _, identity in valid}
    protocols = Counter(scheme for scheme, _ in valid)
    total, valid_count = len(lines), len(valid)
    reject_ratio = (total - valid_count - duplicates) / total if total else 1.0
    passed = (valid_count >= min_configs and len(nodes) >= min_nodes
              and reject_ratio <= 0.25 and duplicates / total <= 0.50)
    return {
        "schema": 1, "checked_at": datetime.now(timezone.utc).isoformat(),
        "status": "pass" if passed else "reject", "input_lines": total,
        "valid_configs": valid_count, "unique_logical_nodes": len(nodes),
        "exact_duplicates": duplicates, "rejected_configs": total - valid_count - duplicates,
        "rejection_reasons": dict(sorted(rejected.items())),
        "protocols": dict(sorted(protocols.items())),
        "sha256": hashlib.sha256(text.encode("utf-8")).hexdigest(),
        "probe_claim": "syntax_and_catalog_quality_only",
        "thresholds": {"min_configs": min_configs, "min_unique_nodes": min_nodes,
                       "max_rejected_ratio": 0.25, "max_exact_duplicate_ratio": 0.50},
    }

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", default="remote/shard-nodes.txt")
    parser.add_argument("--report", default="")
    parser.add_argument("--min-configs", type=int, default=3)
    parser.add_argument("--min-nodes", type=int, default=3)
    args = parser.parse_args()
    try:
        content = Path(args.input).read_text(encoding="utf-8")
    except OSError as exc:
        print(f"cannot read catalog: {exc}", file=sys.stderr)
        return 2
    report = analyze(content, args.min_configs, args.min_nodes)
    rendered = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.report:
        Path(args.report).write_text(rendered, encoding="utf-8")
    print(rendered, end="")
    return 0 if report["status"] == "pass" else 1

if __name__ == "__main__":
    raise SystemExit(main())
