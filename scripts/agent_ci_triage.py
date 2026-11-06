#!/usr/bin/env python3
"""Classify CI failure logs without executing or modifying repository code."""
from __future__ import annotations
import argparse, json, re
from pathlib import Path
TRANSIENT = {
 "network_timeout": r"timed? out|timeout|connection reset|temporary failure in name resolution",
 "upstream_unavailable": r"\b(502|503|504)\b|service unavailable|bad gateway",
 "rate_limited": r"\b429\b|rate.?limit|too many requests",
 "transient_tls": r"tls handshake timeout|unexpected eof|remote end closed connection",
}
DETERMINISTIC = {
 "compile_or_type_error": r"compilation error|unresolved reference|type mismatch|cannot find symbol",
 "test_failure": r"tests? failed|assertionerror|assertion failed|there were failing tests",
 "security_or_integrity": r"checksum mismatch|signature mismatch|certificate.*mismatch|verification failed",
 "configuration_error": r"yaml.*(error|invalid)|gradle.*could not|manifest merger failed",
}
def classify(log: str) -> dict:
    transient = [k for k, p in TRANSIENT.items() if re.search(p, log, re.I)]
    deterministic = [k for k, p in DETERMINISTIC.items() if re.search(p, log, re.I)]
    retry = bool(transient) and not deterministic
    excerpt = []
    for line in log.splitlines():
        if re.search(r"(?i)error|failed|exception|timed? out|unavailable|429|502|503|504", line):
            line = re.sub(r"(?i)(authorization:\s*bearer\s+)[^\s]+", r"\1[REDACTED]", line[:500])
            line = re.sub(r"(?i)((?:token|password|secret|api[_-]?key)=)[^\s&]+", r"\1[REDACTED]", line)
            excerpt.append(line)
    return {"category": "transient" if retry else ("deterministic" if deterministic else "unclassified"), "retry_recommended": retry, "transient_signals": transient, "deterministic_signals": deterministic, "excerpt": excerpt[-30:]}
def main() -> int:
    p = argparse.ArgumentParser(); p.add_argument("--log", required=True); p.add_argument("--output", required=True); a = p.parse_args()
    result = classify(Path(a.log).read_text(encoding="utf-8", errors="replace"))
    Path(a.output).write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result)); return 0
if __name__ == "__main__": raise SystemExit(main())
