# SemanticLayer MCP Server

Spring AI MCP server that exposes the semantic layer to AI agents via MCP (Model Context Protocol). Provides two categories of tools: **metric store** (dbt MetricFlow) and **knowledge graph** (Ontop/SPARQL).

## Tools

### Metric Store (dbt MetricFlow)

| Tool | Description |
|------|-------------|
| `list_metrics` | List all 31 available business metrics |
| `get_metric_definition` | Get definition and metadata for a metric |
| `list_dimensions` | List available dimensions for a metric |
| `query_metric` | Query a metric with optional dimensions and ordering |
| `explain_metric_query` | Get the SQL MetricFlow would generate without executing |

### Knowledge Graph (Ontop/SPARQL)

| Tool | Description |
|------|-------------|
| `kg_ontology_classes` | List entity types and relationships from the OWL ontology |
| `kg_customers_by_region` | Traverse Customer → Nation → Region graph |
| `kg_high_value_customers` | Find customers with balance > 5000 (semantically defined) |
| `kg_customer_order_graph` | 4-hop traversal: Customer → Order → LineItem → Part/Supplier |
| `kg_suppliers_by_region` | Supplier counts per region via graph traversal |
| `kg_sparql_query` | Execute a raw SPARQL SELECT query |

## Prerequisites

- Java 21
- Maven 3.9+
- Docker services running (`docker compose up -d` from repo root)
  - dbt-metricflow on port 8087
  - Ontop on port 8089

## Build

```bash
cd SemanticLayerMCP
mvn clean package -q
```

## Run

The server runs in stdio mode — it is launched by the MCP client (Kiro, Claude Desktop, Copilot), not manually. Configure it in your client's `mcp.json`:

```json
{
  "semantic-layer": {
    "command": "java",
    "args": ["-jar", "/absolute/path/to/SemanticLayerMCP/target/semantic-layer-mcp-0.1.0.jar"],
    "env": {
      "ONTOP_OWL_FILE": "/absolute/path/to/ontop/tpch.owl"
    }
  }
}
```

> `ONTOP_OWL_FILE` must be the absolute path to `ontop/tpch.owl` in your local clone. Required for `kg_ontology_classes`. All other tools work without it.

## Configuration

`src/main/resources/application.properties`:

```properties
metricflow.url=http://localhost:8087
ontop.sparql.endpoint=http://localhost:8089/sparql
ontop.owl.file=${ONTOP_OWL_FILE:}
```

## Testing with MCP Inspector

```bash
ONTOP_OWL_FILE=/absolute/path/to/ontop/tpch.owl \
  npx @modelcontextprotocol/inspector \
  java -jar target/semantic-layer-mcp-0.1.0.jar
```

Opens at `http://localhost:6274`. Sample calls:
- `list_metrics()` → 31 metrics
- `list_dimensions("revenue_by_region")` → available dimension paths
- `query_metric("revenue_by_region", ["supplier_id__region_id__region_name"], "revenue_by_region", "DESC", 5)` → top 5 regions by revenue
- `kg_ontology_classes()` → 7 TPC-H entity classes
- `kg_high_value_customers()` → customers with balance > 5000
- `kg_suppliers_by_region()` → supplier counts per region
