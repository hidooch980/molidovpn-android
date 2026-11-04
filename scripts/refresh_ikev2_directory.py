#!/usr/bin/env python3
"""Refresh the public, non-secret IKEv2 endpoint mirror from VPN Jantit's public pages."""
from __future__ import annotations

import json
import re
import sys
from datetime import datetime, timezone
from concurrent.futures import ThreadPoolExecutor, as_completed
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import urljoin, urlparse, parse_qs
from urllib.request import Request, urlopen

BASE = "https://www.vpnjantit.com/"
INDEX = urljoin(BASE, "free-ikev2")
HOST = "www.vpnjantit.com"
OUT = Path("data/ikev2/free-servers.json")
HOST_RE = re.compile(r"(?i)\b([a-z0-9-]+\.vpnjantit\.com)\b")
PAGE_RE = re.compile(r"^/free-ikev2-[a-z0-9-]+$")


class Links(HTMLParser):
    def __init__(self):
        super().__init__()
        self.href = None
        self.text = []
        self.links = []

    def handle_starttag(self, tag, attrs):
        if tag == "a":
            self.href = dict(attrs).get("href")
            self.text = []

    def handle_data(self, data):
        if self.href is not None:
            self.text.append(data)

    def handle_endtag(self, tag):
        if tag == "a" and self.href is not None:
            self.links.append((self.href, " ".join(self.text).strip()))
            self.href = None
            self.text = []


def get(url: str) -> str:
    req = Request(url, headers={"User-Agent": "MolidoVPN-IKEv2-directory/1.0"})
    with urlopen(req, timeout=15) as response:
        final = response.geturl()
        if urlparse(final).scheme != "https" or urlparse(final).hostname != HOST:
            raise RuntimeError(f"Refusing non-provider redirect: {final}")
        data = response.read(2 * 1024 * 1024 + 1)
        if len(data) > 2 * 1024 * 1024:
            raise RuntimeError("Provider page exceeds size limit")
        return data.decode("utf-8", errors="replace")


def main() -> None:
    index_html = get(INDEX)
    parser = Links()
    parser.feed(index_html)
    pages = []
    for href, label in parser.links:
        absolute = urljoin(INDEX, href)
        parsed = urlparse(absolute)
        if parsed.scheme != "https" or parsed.hostname != HOST or not PAGE_RE.fullmatch(parsed.path.rstrip("/")):
            continue
        country = label.replace("IKEV2 MSCHPv2 ", "").strip()
        if country:
            pages.append((country, absolute))
    pages = list(dict.fromkeys(pages))
    if not pages:
        raise RuntimeError("No recognized IKEv2 country pages; refusing to overwrite last-known-good mirror")

    def scrape_country(country: str, url: str) -> list[dict]:
        html = get(url)
        page_links = Links()
        page_links.feed(html)
        account_links = {}
        for href, _ in page_links.links:
            absolute = urljoin(url, href)
            parsed = urlparse(absolute)
            if parsed.scheme != "https" or parsed.hostname != HOST or "create-free-account" not in parsed.path:
                continue
            code = parse_qs(parsed.query).get("server", [""])[0].lower()
            if code:
                account_links[code] = absolute
        plain = re.sub(r"<[^>]+>", " ", html)
        plain = re.sub(r"\s+", " ", plain)
        available = bool(re.search(r"\bAvailable\b", plain, re.I)) and not bool(
            re.search(r"\b(Not Available|Unavailable)\b", plain, re.I)
        )
        return [
            {
                "country": country,
                "host": host.lower(),
                "identity": "vpnjantit",
                "accountUrl": account_links[host.lower().split(".", 1)[0]],
                "available": available,
            }
            for host in set(HOST_RE.findall(plain))
            if host.lower().split(".", 1)[0] in account_links
        ]

    servers = {}
    with ThreadPoolExecutor(max_workers=5) as pool:
        futures = {pool.submit(scrape_country, country, url): country for country, url in pages[:80]}
        for future in as_completed(futures):
            country = futures[future]
            try:
                for item in future.result():
                    servers[item["host"]] = item
            except Exception as exc:
                print(f"WARNING: {country}: {exc}", file=sys.stderr)

    if not servers:
        raise RuntimeError("No valid IKEv2 endpoints found; refusing to overwrite last-known-good mirror")

    OUT.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "schema": 1,
        "source": INDEX,
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "servers": sorted(servers.values(), key=lambda item: (item["country"].lower(), item["host"])),
    }
    OUT.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {len(servers)} endpoints to {OUT}")


if __name__ == "__main__":
    main()
