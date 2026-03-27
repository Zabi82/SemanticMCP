#!/bin/bash
set -e

echo "Waiting for Trino to be ready..."
python /dbt/wait-for-trino.py

echo "Running dbt models..."
dbt run
echo "dbt run complete."

echo "Starting metricflow API server..."
exec python metricflow_server.py
