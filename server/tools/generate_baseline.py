#!/usr/bin/env python3
"""Generate the MySQL baseline DDL for the Java service from the Python models.

The Python backend stays the single source of truth for table shape. This tool
compiles ``Base.metadata`` with the SQLAlchemy MySQL dialect and rewrites the
few PostgreSQL-only constructs:

* ``JSONB`` -> ``JSON``
* partial indexes are dropped here; MySQL equivalents (generated columns)
  live in ``V2__mysql_partial_unique.sql``
* ``TEXT`` server defaults are dropped (MySQL rejects literal defaults on TEXT)

Run from the repository root:

    .venv/Scripts/python.exe server/tools/generate_baseline.py
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "backend"))

from sqlalchemy import JSON, Text  # noqa: E402
from sqlalchemy.dialects import mysql  # noqa: E402
from sqlalchemy.dialects.postgresql import JSONB  # noqa: E402
from sqlalchemy.schema import CreateIndex, CreateTable  # noqa: E402

from app import modules  # noqa: E402,F401  registers every ORM model
from app.db import Base  # noqa: E402

OUT = ROOT / "server" / "src" / "main" / "resources" / "db" / "migration" / "V1__baseline.sql"

# Indexes declared with a PostgreSQL partial predicate. Their MySQL
# equivalents are generated columns added in V2.
PARTIAL_UNIQUE = {
    "uq_admin_users_supplier",
    "uq_admin_api_keys_user_name_active",
    "uq_subscriptions_one_active_per_user",
    "uq_dramas_title_self",
    "uq_dramas_title_supplier",
}
PARTIAL_PLAIN = {
    "ix_dramas_review_state",
    "ix_orders_source_drama",
    "ix_orders_refunded_at",
}


def adapt(table) -> None:
    for column in table.columns:
        if isinstance(column.type, JSONB):
            column.type = JSON()
        if isinstance(column.type, Text) and column.server_default is not None:
            column.server_default = None


def main() -> None:
    dialect = mysql.dialect()
    chunks: list[str] = [
        "-- MySQL 8 baseline generated from the SQLAlchemy models.",
        "-- Regenerate with: .venv/Scripts/python.exe server/tools/generate_baseline.py",
        "SET NAMES utf8mb4;",
        "",
    ]

    for table in Base.metadata.sorted_tables:
        adapt(table)
        ddl = str(CreateTable(table).compile(dialect=dialect)).strip()
        ddl = re.sub(r"\s*DEFAULT now\(\)", "", ddl)
        chunks.append(ddl.rstrip(";") + " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;")
        chunks.append("")

        for index in sorted(table.indexes, key=lambda i: i.name or ""):
            if index.name in PARTIAL_UNIQUE or index.name in PARTIAL_PLAIN:
                continue
            stmt = str(CreateIndex(index).compile(dialect=dialect)).strip()
            chunks.append(stmt.rstrip(";") + ";")

        if table.indexes:
            chunks.append("")

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text("\n".join(chunks) + "\n", encoding="utf-8")
    print(f"wrote {OUT} ({len(chunks)} statements)")


if __name__ == "__main__":
    main()
