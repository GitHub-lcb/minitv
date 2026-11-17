#!/usr/bin/env python3
"""Generate Java MyBatis row classes for every SQLAlchemy table.

One class per table under us.minitv.<module>.row, Lombok @Data, snake_case
columns mapped to camelCase by MyBatis. JSON/JSONB columns become String (the
service layer parses them with Jackson), timestamps become LocalDateTime
(UTC), dates LocalDate.
"""

from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "backend"))

from sqlalchemy import BigInteger, Boolean, Date, DateTime, Float, Integer, JSON, Numeric, SmallInteger, String, Text  # noqa: E402
from sqlalchemy.dialects.postgresql import JSONB  # noqa: E402

from app import modules  # noqa: E402,F401
from app.db import Base  # noqa: E402

OUT = ROOT / "server" / "src" / "main" / "java" / "us" / "minitv"

MODULE = {
    "users": "account", "third_party_identities": "account", "verification_codes": "account",
    "dramas": "content", "episodes": "content", "tags": "content", "drama_tags": "content",
    "home_groups": "content", "drama_home_groups": "content", "media_uploads": "content", "cdn_purges": "content",
    "plans": "billing", "plan_versions": "billing", "subscriptions": "billing", "orders": "billing",
    "billing_settings": "billing",
    "wallet_accounts": "wallet", "coin_ledger": "wallet", "entitlements": "wallet",
    "playback_events": "playback", "playback_progress": "playback",
    "comments": "social", "reactions": "social", "inbox_messages": "social", "push_records": "social",
    "daily_metrics": "analytics",
    "idempotency_records": "common", "audit_logs": "common",
}

OPS_TABLES = {
    "admin_users", "admin_api_keys", "api_key_events", "suppliers", "supplier_share_history",
    "supplier_settlements", "channel_tabs", "banners", "hot_searches", "copy_entries",
    "marketing_stats", "testimonials", "store_links", "legal_pages", "reward_configs",
    "checkins", "ad_claims", "task_claims", "moderation_settings",
}


def camel(name: str) -> str:
    parts = name.split("_")
    return parts[0] + "".join(p.capitalize() for p in parts[1:])


def java_type(column) -> str:
    t = column.type
    if isinstance(t, (JSONB, JSON)):
        return "String"
    if isinstance(t, Boolean):
        return "Boolean"
    if isinstance(t, (BigInteger,)):
        return "Long"
    if isinstance(t, (SmallInteger, Integer)):
        return "Integer"
    if isinstance(t, (Numeric, Float)):
        return "Double"
    if isinstance(t, Date):
        return "LocalDate"
    if isinstance(t, DateTime):
        return "LocalDateTime"
    return "String"


def class_name(table: str) -> str:
    name = table[:-1] if table.endswith("s") and not table.endswith("ss") else table
    return "".join(part.capitalize() for part in name.split("_")) + "Row"


def main() -> None:
    count = 0
    for table in Base.metadata.sorted_tables:
        module = "ops" if table.name in OPS_TABLES else MODULE.get(table.name)
        if module is None:
            raise SystemExit(f"unmapped table: {table.name}")
        package = f"us.minitv.{module}.row"
        target = OUT / module / "row" / f"{class_name(table.name)}.java"
        target.parent.mkdir(parents=True, exist_ok=True)

        fields = []
        needs_date = False
        needs_datetime = False
        for column in table.columns:
            jt = java_type(column)
            needs_date |= jt == "LocalDate"
            needs_datetime |= jt == "LocalDateTime"
            nullable = column.nullable
            if jt in ("Long", "Integer", "Double", "Boolean") and not nullable:
                java_type_name = jt
            else:
                java_type_name = jt if nullable else jt
            fields.append(f"    private {java_type_name} {camel(column.name)};")

        imports = ["import lombok.Data;", ""]
        if needs_date:
            imports.append("import java.time.LocalDate;")
        if needs_datetime:
            imports.append("import java.time.LocalDateTime;")
        header = "\n".join([
            f"package {package};",
            "",
            *imports,
            "",
            f"/** Row for table {table.name}. Generated from the Python models. */",
            "@Data",
            f"public class {class_name(table.name)} {{",
        ])
        target.write_text(header + "\n" + "\n".join(fields) + "\n}\n", encoding="utf-8")
        count += 1
    print(f"generated {count} row classes")


if __name__ == "__main__":
    main()
