"""Classify CI failures and emit bounded, redacted diagnostic excerpts."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Any

TRANSIENT = {
    "network_timeout": (
        r"timed? out|timeout|connection reset|temporary failure in name resolution"
    ),
    "upstream_unavailable": r"\b(502|503|504)\b|service unavailable|bad gateway",
    "rate_limited": r"\b429\b|rate.?limit|too many requests",
    "transient_tls": (
        r"tls handshake timeout|unexpected eof|remote end closed connection"
    ),
}
DETERMINISTIC = {
    "compile_or_type_error": (
        r"compilation error|unresolved reference|type mismatch|cannot find symbol"
    ),
    "test_failure": (
        r"tests? failed|assertionerror|assertion failed|there were failing tests"
    ),
    "security_or_integrity": (
        r"checksum mismatch|signature mismatch|certificate.*mismatch|verification failed"
    ),
    "configuration_error": (
        r"yaml.*(error|invalid)|gradle.*could not|manifest merger failed"
    ),
}
ERROR_LINE = re.compile(
    r"error|failed|exception|timed? out|unavailable|429|502|503|504",
    re.IGNORECASE,
)
SECRET_PATTERNS = (
    (
        re.compile(r"(authorization:\s*bearer\s+)[^\s]+", re.IGNORECASE),
        r"\1[REDACTED]",
    ),
    (
        re.compile(
            r"((?:token|password|secret|api[_-]?key)\s*[=:]\s*)[^\s&]+",
            re.IGNORECASE,
        ),
        r"\1[REDACTED]",
    ),
    (re.compile(r"gh[pousr]_[A-Za-z0-9_]{20,}"), "[REDACTED_GITHUB_TOKEN]"),
    (re.compile(r"github_pat_[A-Za-z0-9_]{20,}"), "[REDACTED_GITHUB_TOKEN]"),
)


def redact(line: str) -> str:
    """Remove common credentials before a log excerpt can leave the runner."""
    value = line[:500]
    for pattern, replacement in SECRET_PATTERNS:
        value = pattern.sub(replacement, value)
    return value


def classify(log: str) -> dict[str, Any]:
    """Classify one CI log; deterministic failures always suppress retry."""
    transient = [
        key for key, pattern in TRANSIENT.items() if re.search(pattern, log, re.I)
    ]
    deterministic = [
        key for key, pattern in DETERMINISTIC.items() if re.search(pattern, log, re.I)
    ]
    retry = bool(transient) and not deterministic
    excerpt = [redact(line) for line in log.splitlines() if ERROR_LINE.search(line)]
    if retry:
        category = "transient"
    elif deterministic:
        category = "deterministic"
    else:
        category = "unclassified"
    return {
        "category": category,
        "retry_recommended": retry,
        "transient_signals": transient,
        "deterministic_signals": deterministic,
        "excerpt": excerpt[-30:],
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--log", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    result = classify(Path(args.log).read_text(encoding="utf-8", errors="replace"))
    Path(args.output).write_text(
        json.dumps(result, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps(result))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
