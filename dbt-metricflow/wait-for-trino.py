#!/usr/bin/env python3
"""
Wait for Trino to be fully ready:
1. HTTP endpoint up
2. Init SQL complete (all TPC-H tables exist in semantic_demo.ice_db)
"""
import urllib.request
import time
import sys

TRINO_URL = "http://trino:8080"
CATALOG = "semantic_demo"
SCHEMA = "ice_db"
# All tables created by init-tpch.sql — wait until all exist
REQUIRED_TABLES = ["customer", "orders", "lineitem", "supplier", "part", "nation", "region"]


def trino_query(sql):
    """Execute a query via Trino HTTP API, return True on success."""
    import json
    headers = {
        "X-Trino-User": "admin",
        "X-Trino-Catalog": CATALOG,
        "X-Trino-Schema": SCHEMA,
        "Content-Type": "application/json",
    }
    try:
        req = urllib.request.Request(
            f"{TRINO_URL}/v1/statement",
            data=sql.encode(),
            headers=headers,
            method="POST"
        )
        with urllib.request.urlopen(req, timeout=5) as resp:
            result = json.loads(resp.read())
            next_uri = result.get("nextUri")
            # Poll until query completes
            while next_uri:
                with urllib.request.urlopen(next_uri, timeout=5) as r:
                    result = json.loads(r.read())
                    next_uri = result.get("nextUri")
                    if result.get("error"):
                        return False
                    time.sleep(0.2)
            return "error" not in result
    except Exception:
        return False


print("Waiting for Trino HTTP endpoint...")
while True:
    try:
        urllib.request.urlopen(f"{TRINO_URL}/v1/info", timeout=3)
        print("Trino HTTP is up.")
        break
    except Exception:
        print("  Trino not ready yet, retrying in 5s...")
        time.sleep(5)

print("Waiting for TPC-H tables to be created by init script...")
while True:
    missing = []
    for table in REQUIRED_TABLES:
        ok = trino_query(f"SELECT 1 FROM {CATALOG}.{SCHEMA}.{table} LIMIT 1")
        if not ok:
            missing.append(table)
    if not missing:
        print("All TPC-H tables are ready.")
        sys.exit(0)
    print(f"  Still waiting for tables: {missing}, retrying in 5s...")
    time.sleep(5)
