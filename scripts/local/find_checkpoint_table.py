#!/usr/bin/env python3
"""Find correct database and checkpoint table."""
import json
import os
import sys
import pymysql
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

# Get MySQL credentials from .env
env_path = ROOT / ".env"
env_vars = {}
if env_path.exists():
    for line in env_path.read_text(encoding="utf-8-sig").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            key = key.strip()
            value = value.strip().strip("\"'")
            if key:
                env_vars[key] = value

password = env_vars.get("MYSQL_ROOT_PASSWORD", os.getenv("MYSQL_ROOT_PASSWORD", ""))
if not password:
    print("MYSQL_ROOT_PASSWORD not found", file=sys.stderr)
    sys.exit(1)

conn = pymysql.connect(
    host="127.0.0.1",
    port=3307,
    user="root",
    password=password,
    charset="utf8mb4"
)

try:
    with conn.cursor() as cursor:
        cursor.execute("SHOW DATABASES")
        databases = [row[0] for row in cursor.fetchall()]
        
        app_dbs = [db for db in databases if db not in ("information_schema", "mysql", "performance_schema", "sys")]
        print(f"Application databases: {app_dbs}", file=sys.stderr)
        
        for db in app_dbs:
            cursor.execute(f"USE `{db}`")
            cursor.execute("SHOW TABLES LIKE '%checkpoint%'")
            tables = [row[0] for row in cursor.fetchall()]
            
            if tables:
                print(f"\nDatabase: {db}", file=sys.stderr)
                print(f"Checkpoint tables: {tables}", file=sys.stderr)
                
                for table in tables:
                    cursor.execute(f"SELECT COUNT(*) FROM `{table}` WHERE run_id LIKE %s", ("%fe814d79%",))
                    count = cursor.fetchone()[0]
                    if count > 0:
                        print(f"  {table}: {count} rows matching fe814d79", file=sys.stderr)
                        
                        cursor.execute(f"DESCRIBE `{table}`")
                        columns = [row[0] for row in cursor.fetchall()]
                        print(f"  Columns: {', '.join(columns[:10])}", file=sys.stderr)
                        
                        print(json.dumps({"database": db, "table": table, "matchingRows": count}))
                        sys.exit(0)
        
        print("No checkpoint found", file=sys.stderr)
        sys.exit(1)
        
finally:
    conn.close()
