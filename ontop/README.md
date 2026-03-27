# Ontop Knowledge Graph Setup

Ontop virtualizes the TPC-H Iceberg data as a knowledge graph via SPARQL — no data duplication.

## One-time setup

Download the Trino JDBC driver before starting:

```bash
cd iceberg-semantic/ontop
chmod +x download-jdbc.sh
./download-jdbc.sh
```

Then start the full stack:

```bash
cd iceberg-semantic
docker compose up -d ontop
```

Ontop UI (SPARQL portal) is available at: http://localhost:8089

## Files

| File | Purpose |
|------|---------|
| `tpch.owl` | OWL ontology — defines classes (Customer, Order, Nation...) and relationships |
| `tpch.obda` | OBDA mappings — binds ontology classes to Trino/Iceberg SQL tables |
| `ontop.properties` | JDBC connection config pointing to Trino |
| `tpch.toml` | Portal config with predefined demo SPARQL queries |

## Ontology model

```
Customer --placesOrder--> Order --hasLineItem--> LineItem --hasPart--> Part
    |                                                 |
belongsToNation                                  suppliedBy
    |                                                 |
  Nation --partOfRegion--> Region               Supplier --belongsToNation--> Nation
```
