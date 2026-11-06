# Smart Server Refresh Agent

The repository already has an hourly source collector, app-side refresh/cache fallback,
and per-device health ranking. This agent adds a server-side publication guard so a
bad or malformed refresh cannot silently replace the fleet's current SHARD catalog.

## Automatic operation

- The source collector remains responsible for gathering provider lists and ranking
  community-scored candidates.
- This guard runs after the hourly collector (at minute 47), on catalog changes, and
  on demand.
- It validates VLESS/Trojan WebSocket+TLS syntax, public endpoint/SNI fields, ports,
  duplicate entries, protocol distribution, and minimum usable catalog size.
- It groups IPv4, IPv6 and approved local-relay variants by logical node identity.
- If a new catalog fails on the main branch, it searches Git history for the newest
  valid catalog and restores it automatically. If no valid history exists, it fails closed.
- Android clients continue to refresh with ETag and keep their local last-known-good
  cache when every mirror fails. Device-side ShardHealth remains the source of
  observed latency, throughput and connection-streak ranking.

## Trust boundaries

This guard reports syntax_and_catalog_quality_only. It does not claim that a URL
parses means its VPN handshake works, and it does not promote a server based on TCP
reachability alone. The existing upstream collector's real proxied requests and
on-device connection observations remain separate signals.

No credentials, subscription URLs, device IDs, precise locations, or user traffic
are collected by this guard. It reads only the public catalog committed to this repo.

## Validation

Run locally:

    python -m unittest discover -s scripts -p 'test_*.py' -v
    python scripts/smart_server_refresh.py --input remote/shard-nodes.txt
