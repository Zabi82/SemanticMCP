# Intelligent Data Platform Demo

Demo for the talk: **"Intelligent Data Platform: Powered by Semantic Layer, MCP and Agents"**

## Overview

This demo showcases how a semantic layer dramatically improves data agent effectiveness by comparing two scenarios:

- **Scenario One**: Agent with data layer access only (writes SQL manually)
- **Scenario Two**: Agent with data layer + semantic layer (metric store + knowledge graph)

### Why a Semantic Layer Matters for AI Agents

When an AI agent connects directly to a data platform, it faces several fundamental problems:

**Hallucination of business logic** — LLMs don't know your business rules. Ask "what's our order completion rate?" and the agent has to guess which status codes mean "completed". It might get it right, or it might silently produce a wrong answer. With a semantic layer, the business rule is encoded once (`F` = Filled = completed) and the agent uses it — no guessing.

**Metric drift** — without a single source of truth, different agents (or different runs of the same agent) calculate the same metric differently. Revenue before or after discount? Which order statuses count as pending? The semantic layer enforces consistency.

**Token waste and back-and-forth** — without pre-defined metrics, the agent has to explore tables, check schemas, figure out JOIN paths, write SQL, handle errors, and retry. This burns tokens and takes time. With a semantic layer, the agent calls `list_metrics()`, picks the right metric, and gets the answer in 2-3 tool calls instead of 8-10.

**Complex JOIN paths** — a 4-table JOIN (`lineitem → supplier → nation → region`) requires the agent to know the foreign key relationships. The semantic layer encodes these as dimension paths — the agent just asks for `supplier_id__region_id__region_name` and the JOIN is handled automatically.

**No semantic understanding** — raw tables have no concept of "high-value customer" or "on-time delivery". The knowledge graph layer adds a formal ontology — business concepts with defined relationships — so the agent can reason over meaning, not just data.

The result: agents that are faster, cheaper to run, more consistent, and less likely to produce plausible-but-wrong answers.

## Repository Structure

```
SemanticMCP/                          ← repo root
├── docker-compose.yml                ← start the full platform
├── trino/                            ← Trino config + TPC-H init SQL
├── dbt-metricflow/                   ← dbt + MetricFlow metric store + REST API
├── ontop/                            ← Ontop knowledge graph (OWL ontology + OBDA mappings)
├── DataLakeHouseMCP/                 ← MCP server: Trino/Iceberg data layer tools
├── SemanticLayerMCP/                 ← MCP server: metric store + knowledge graph tools
├── .claude/                          ← agent instructions for all AI clients
│   ├── agent-data-layer-only.md      ← Scenario One instructions
│   └── agent-data-and-semantic-layer.md ← Scenario Two instructions
├── DBT_METRICFLOW_EXPLAINED.md       ← deep dive: how dbt MetricFlow works
└── ONTOP_EXPLAINED.md                ← deep dive: how Ontop virtual KG works
```

### Semantic Layer Components

**dbt MetricFlow** (`dbt-metricflow/`) — defines 31 business metrics on TPC-H data using dbt's semantic layer. MetricFlow translates metric queries into optimised SQL at runtime, encoding business rules (e.g. which order statuses count as "completed") so agents never have to guess. A lightweight Flask API wraps the MetricFlow CLI for MCP access. See [DBT_METRICFLOW_EXPLAINED.md](DBT_METRICFLOW_EXPLAINED.md) for a full walkthrough.

**Ontop** (`ontop/`) — a virtual knowledge graph that exposes the Iceberg tables as an OWL ontology via a SPARQL endpoint. No data duplication — Ontop rewrites SPARQL queries to SQL at query time. This adds a formal domain model (Customer, Order, Supplier, Nation, Region and their relationships) on top of the raw tables, enabling semantic reasoning. See [ONTOP_EXPLAINED.md](ONTOP_EXPLAINED.md) for details.

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                    AI Agents (Kiro/Claude)               │
│              via MCP (Model Context Protocol)            │
└────────────────────┬────────────────────────────────────┘
                     │
        ┌────────────┴────────────┐
        │                         │
┌───────▼──────────┐   ┌──────────▼──────────┐
│  DataLakehouse   │   │   SemanticLayer MCP  │
│    MCP Server    │   │       Server         │
│  (Trino tools)   │   │  Metric Store (dbt)  │
└───────┬──────────┘   │  Knowledge Graph     │
        │              │  (Ontop/SPARQL)      │
        │              └──────────┬───────────┘
        │                         │
┌───────▼─────────────────────────▼───────────┐
│              Trino Query Engine              │
└───────────────────┬─────────────────────────┘
                    │
┌───────────────────▼─────────────────────────┐
│         Iceberg Tables (TPC-H data)          │
│         MinIO (S3-compatible storage)        │
└─────────────────────────────────────────────┘
```

## Quick Start

### 1. Start the platform

```bash
cd SemanticMCP
docker compose up -d
```

Wait ~60s for TPC-H data to load and dbt models to build. Check readiness:

```bash
docker logs dbt-metricflow --follow   # wait for "dbt run complete"
curl http://localhost:8087/health      # metric API
curl http://localhost:8089/sparql      # knowledge graph
```

### 2. Download Ontop JDBC driver (first time only)

```bash
chmod +x ontop/download-jdbc.sh && ./ontop/download-jdbc.sh
```

### 3. Build and start the MCP servers

```bash
# Data layer MCP (Trino tools)
cd DataLakeHouseMCP
mvn clean package -q
java -jar target/datalakehouse-mcp-java-0.1.0.jar

# Semantic layer MCP (metrics + knowledge graph)
cd SemanticLayerMCP
mvn clean package -q
java -jar target/semantic-layer-mcp-java-0.1.0.jar
```

### 4. Configure your AI client

Use the JSON config snippets in the [AI Client Configuration](#ai-client-configuration) section below. Update the jar paths to match your local clone, then configure your client:

- **Claude Desktop**: paste into `claude_desktop_config.json` and restart
- **Kiro**: paste into `~/.kiro/settings/mcp.json`
- **GitHub Copilot**: add under `github.copilot.chat.mcp.servers` in VS Code settings

### 5. Load agent instructions

The `.claude/agent-data-layer-only.md` and `.claude/agent-data-and-semantic-layer.md` files contain the system prompt for each scenario.

- **Claude Desktop**: paste as a system prompt at the start of each conversation
- **Kiro**: type `#agent-data-layer-only` or `#agent-data-and-semantic-layer` in chat to load via steering files
- **GitHub Copilot**: paste into `.github/copilot-instructions.md` or as a custom instruction

## Services

| Service | Port | Purpose |
|---------|------|---------|
| Trino | 8080 | SQL query engine |
| dbt-metricflow | 8087 | Metric store REST API |
| Ontop | 8089 | SPARQL knowledge graph |
| MinIO | 9001 | Object storage UI |
| Iceberg REST | 8181 | Iceberg catalog |

## Demo Scenarios

**Scenario One** — Data Layer Only: agent explores raw tables, guesses business logic, writes SQL manually.

**Scenario Two** — Semantic Layer: agent discovers metrics dynamically, uses pre-validated business logic, traverses the knowledge graph for entity relationships.

## Technology Stack

- Java 21, Spring Boot 3.4.5, Spring AI 1.0.1
- Python 3.11, dbt-core, dbt-metricflow, Flask
- Apache Iceberg, Trino, MinIO
- Ontop 5.5.0 (virtual knowledge graph / SPARQL)
- Docker Compose
- MCP (Model Context Protocol)

## Testing MCP Servers

Use MCP Inspector to test tools interactively before the demo:

```bash
# Test DataLakehouse MCP
npx @modelcontextprotocol/inspector java -jar DataLakeHouseMCP/target/datalakehouse-mcp-0.1.0.jar
```

Opens at `http://localhost:6274`. Try:
- `trino_catalogs()` → should return `semantic_demo`, `system`, `tpch`
- `trino_iceberg_tables("semantic_demo", "ice_db")` → 8 tables
- `execute_trino_query("SELECT COUNT(*) FROM customer", "semantic_demo", "ice_db")` → 1500

```bash
# Test SemanticLayer MCP
# Pass the OWL file path as an env var so kg_ontology_classes works correctly
ONTOP_OWL_FILE=/absolute/path/to/ontop/tpch.owl \
  npx @modelcontextprotocol/inspector java -jar SemanticLayerMCP/target/semantic-layer-mcp-0.1.0.jar
```

> Note: `ONTOP_OWL_FILE` must be an absolute path to `ontop/tpch.owl` in your local clone. If omitted, all knowledge graph tools work except `kg_ontology_classes`.

Try:
- `list_metrics()` → 31 metrics
- `query_metric("revenue", null, "revenue", "DESC", 1)` → formatted revenue total
- `kg_ontology_classes()` → 7 TPC-H entity classes

## Troubleshooting

**Tables missing after restart**
```bash
docker exec trino trino --execute "SHOW TABLES FROM semantic_demo.ice_db"
# If empty, the init script may still be running — check:
docker logs trino | tail -20
```

**dbt models not built**
```bash
docker logs dbt-metricflow | tail -30
# If errors, manually re-run:
docker exec dbt-metricflow dbt run
```

**Ontop fails to start** — it uses `restart: on-failure`, so it will retry automatically once Trino is fully ready. Check:
```bash
docker logs ontop | tail -20
```

**Full reset** (deletes all Iceberg data — TPC-H will be re-ingested on next start)
```bash
docker compose down -v
docker compose up -d
```

**Prerequisites**
- Docker Desktop with ≥8GB RAM
- Java 21 (`java -version`)
- Maven 3.9+ (`mvn -version`)

## AI Client Configuration

The MCP servers communicate over stdio. Any MCP-compatible AI client works — Claude Desktop, Kiro, GitHub Copilot, and others.

**Key rule:**
- **Scenario One** (data layer only) — start only `DataLakeHouseMCP`, configure only that server in your client
- **Scenario Two** (semantic layer) — start both `DataLakeHouseMCP` and `SemanticLayerMCP`, configure both in your client

This ensures the agent only sees the tools relevant to each scenario.

Two scenarios, two configurations — update the jar paths to match your local clone.

> **Important**: In the Scenario Two configs below, replace `/path/to/ontop/tpch.owl` with the absolute path to `ontop/tpch.owl` in your local clone (e.g. `/Users/yourname/SemanticMCP/ontop/tpch.owl`). This is required for the `kg_ontology_classes` tool. All other knowledge graph tools work without it.

### Scenario One — Data Layer Only

```json
{
  "mcpServers": {
    "lakehouse-data": {
      "command": "java",
      "args": ["-jar", "/path/to/DataLakeHouseMCP/target/datalakehouse-mcp-java-0.1.0.jar"]
    }
  }
}
```

### Scenario Two — Data Layer + Semantic Layer

```json
{
  "mcpServers": {
    "lakehouse-data": {
      "command": "java",
      "args": ["-jar", "/path/to/DataLakeHouseMCP/target/datalakehouse-mcp-java-0.1.0.jar"]
    },
    "semantic-layer": {
      "command": "java",
      "args": ["-jar", "/path/to/SemanticLayerMCP/target/semantic-layer-mcp-java-0.1.0.jar"],
      "env": {
        "ONTOP_OWL_FILE": "/path/to/ontop/tpch.owl"
      }
    }
  }
}
```

> The `ONTOP_OWL_FILE` env var tells the semantic layer server where to find the OWL ontology file. Set it to the absolute path of `ontop/tpch.owl` in your local clone. If omitted, all knowledge graph tools still work except `kg_ontology_classes`.

### Claude Desktop

Config location:
- macOS: `~/Library/Application Support/Claude/claude_desktop_config.json`
- Windows: `%APPDATA%\Claude\claude_desktop_config.json`

Paste the appropriate JSON above into the config file and restart Claude Desktop. Paste the contents of `.claude/agent-data-layer-only.md` or `.claude/agent-data-and-semantic-layer.md` as a system prompt at the start of each conversation.

For the demo, open two separate Claude Desktop windows — one per scenario.

### Claude Code

Agent instruction files are in the `.claude/` folder. Copy the appropriate one to `CLAUDE.md` at the repo root before starting a session:

```bash
# Scenario One
cp .claude/agent-data-layer-only.md CLAUDE.md

# Scenario Two
cp .claude/agent-data-and-semantic-layer.md CLAUDE.md
```

Claude Code automatically picks up `CLAUDE.md` as project-level instructions.

### Kiro

Place the JSON in `~/.kiro/settings/mcp.json` (user-level) or `.kiro/settings/mcp.json` (workspace-level).

In chat, type `#agent-data-layer-only` or `#agent-data-and-semantic-layer` to load the agent instructions via steering files (sourced from `.claude/`).

> Note: Kiro shows all tools from all configured MCP servers regardless of which steering file is active. For a clean comparison, use two separate Kiro workspace windows with different `mcp.json` configs.

### GitHub Copilot

MCP servers are configured in `.vscode/mcp.json` in your workspace (or user-level via `MCP: Open User Configuration` in the Command Palette). Note the key is `servers`, not `mcpServers`:

**Scenario One** — create `.vscode/mcp.json`:
```json
{
  "servers": {
    "lakehouse-data": {
      "command": "java",
      "args": ["-jar", "/path/to/DataLakeHouseMCP/target/datalakehouse-mcp-java-0.1.0.jar"]
    }
  }
}
```

**Scenario Two** — add the semantic-layer server:
```json
{
  "servers": {
    "lakehouse-data": {
      "command": "java",
      "args": ["-jar", "/path/to/DataLakeHouseMCP/target/datalakehouse-mcp-java-0.1.0.jar"]
    },
    "semantic-layer": {
      "command": "java",
      "args": ["-jar", "/path/to/SemanticLayerMCP/target/semantic-layer-mcp-java-0.1.0.jar"],
      "env": {
        "ONTOP_OWL_FILE": "/path/to/ontop/tpch.owl"
      }
    }
  }
}
```

You can also enable/disable individual servers or select specific tools via the Chat Customizations editor (`Chat: Open Chat Customizations` in the Command Palette) or by right-clicking a server in the MCP SERVERS section of the Extensions view.

Paste the contents of `.claude/agent-data-layer-only.md` or `.claude/agent-data-and-semantic-layer.md` into `.github/copilot-instructions.md` to set the agent instructions for the workspace.

## Try It Yourself

Once everything is running, try these questions with your AI agent to see the semantic layer in action. Use Scenario One (data layer only) first, then Scenario Two (with semantic layer) to compare.

---

### "What's our order completion rate?" ⭐ Best opener

**Scenario One** — explores `orders`, sees `orderstatus` with values `F`, `O`, `P`, and has to guess which means "completed". May include `P` (Pending) as completed — wrong. Answer varies between runs.

**Scenario Two** — `query_metric("order_completion_rate")` → **48.69%**. Business rule encoded: only `F` (Filled) = completed.

> Key insight: the semantic layer prevents metric drift — everyone gets the same answer, every time.

---

### "What's our supplier on-time delivery rate?"

**Scenario One** — has to guess the business definition of "on-time". May use receipt date, ship date, or some proxy.

**Scenario Two** — `query_metric("supplier_on_time_rate")` → **49.39%**. Business rule encoded: `shipdate <= commitdate`.

---

### "What percentage of our orders are high priority?"

**Scenario One** — sees `orderpriority` with 5 levels. May include `3-MEDIUM` (too broad) or only `1-URGENT` (too narrow).

**Scenario Two** — `query_metric("high_priority_order_rate")` → **40.57%**. Business rule: only `1-URGENT` and `2-HIGH` count.

---

### "What does our data model look like — what entities exist?"

**Scenario One** — lists raw table names. Describes a database schema.

**Scenario Two** — `kg_ontology_classes()` → 7 entity classes with relationships as a domain model. Customer places Orders, Orders have LineItems, LineItems are supplied by Suppliers, etc.

> Scenario One shows you tables. Scenario Two shows you a domain model.

---

### "Who are our high-value customers and how much revenue are they generating?" ⭐ Cross-layer killer

**Scenario One** — guesses what "high-value" means. Inconsistent definition, multi-table JOINs.

**Scenario Two**:
1. `kg_high_value_customers()` → top-tier creditworthiness customers with geographic context (semantically defined in the ontology)
2. `query_metric("revenue", ["order_id__customer_id__customer_name"], "revenue", "DESC", 100)` → validated revenue per customer
3. Agent cross-references: most high-balance customers are NOT top revenue generators → upsell/retention opportunity

> The knowledge graph defines "high-value" by creditworthiness. The metric store measures purchasing behaviour. Only by combining both can you find the gap — and that gap is actionable.

---

### "Which region generates the most revenue — and do we have enough suppliers there?"

**Scenario One** — two separate complex SQL queries, manual comparison.

**Scenario Two**:
1. `query_metric("revenue_by_region", ...)` → ASIA ~$560M, AFRICA ~$421M, EUROPE ~$411M, AMERICA ~$410M, MIDDLE EAST ~$243M
2. `kg_suppliers_by_region()` → supplier counts per region via graph traversal
3. Agent: "ASIA has highest revenue AND most suppliers — well covered. MIDDLE EAST has lowest supplier count but significant revenue — supply risk."

> Metric store answers "how much". Knowledge graph answers "how distributed". Agent orchestrates both.
