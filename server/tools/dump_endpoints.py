#!/usr/bin/env python3
"""Dump every HTTP endpoint of the Python backend as a porting checklist."""

from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BACKEND = ROOT / "backend" / "app"
OUT = ROOT / "server" / "PORTING-ENDPOINTS.md"

ROUTER_RE = re.compile(r'APIRouter\(prefix="([^"]*)"')
ROUTE_RE = re.compile(r'@router\.(get|post|put|patch|delete)\(\s*"([^"]*)"')
SECOND_ROUTE_RE = re.compile(r'@router\.(get|post|put|patch|delete)\(\s*\n?\s*"([^"]*)"')

FILES = [
    "modules/public_flow.py",
    "modules/content/public_router.py",
    "modules/recommend/router.py",
    "modules/ops/router.py",
    "modules/ops/flow_router.py",
    "modules/ops/review_router.py",
    "modules/ops/settlement_router.py",
    "modules/ops/api_key_router.py",
    "modules/ops/partner_router.py",
]


def main() -> None:
    lines: list[str] = ["# Endpoint checklist (generated)\n", "\n"]
    total = 0
    for rel in FILES:
        path = BACKEND / rel
        text = path.read_text(encoding="utf-8")
        prefix_match = ROUTER_RE.search(text)
        prefix = prefix_match.group(1) if prefix_match else ""
        routes = [(m.group(1).upper(), m.group(2)) for m in ROUTE_RE.finditer(text)]
        routes += [(m.group(1).upper(), m.group(2)) for m in SECOND_ROUTE_RE.finditer(text)]
        # de-duplicate while keeping order
        seen = set()
        unique = []
        for method, route in routes:
            key = (method, route)
            if key not in seen:
                seen.add(key)
                unique.append((method, route))
        total += len(unique)
        lines.append(f"## {rel}  ({prefix or '/'}) - {len(unique)} endpoints\n")
        for method, route in unique:
            lines.append(f"- [ ] {method} {prefix}{route}\n")
        lines.append("\n")
    lines.insert(1, f"Total: {total} endpoints\n\n")
    OUT.write_text("".join(lines), encoding="utf-8")
    print(f"wrote {OUT} ({total} endpoints)")


if __name__ == "__main__":
    main()
