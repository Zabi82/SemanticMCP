# Copilot Workspace Instructions

This workspace contains a data lakehouse demo with three data systems accessible via MCP tools. When answering data questions, follow the guidelines in the relevant prompt file for the active agent mode.

## Systems Overview

- **MCP_Data_Server** — direct SQL access to an Apache Iceberg/Trino lakehouse (`semantic_demo.ice_db`)
- **MCP_Semantic_Server (Metric Store)** — pre-defined business metrics via dbt MetricFlow
- **MCP_Semantic_Server (Knowledge Graph)** — entity relationships and semantic concepts via Ontop/SPARQL

## General Rules

- Never modify data — only SELECT queries are permitted
- Never guess metric names or dimension paths — always discover them first via `list_metrics` and `list_dimensions`
- The `custkey` from knowledge graph tools is the join key for cross-system questions — pass it as `entityFilter` to `query_metric`
