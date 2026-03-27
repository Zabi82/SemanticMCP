# Ontop Deep Dive

This document explains how Ontop works using your actual ontology and mappings from the TPC-H demo.

## Core Concepts

### 1. What Ontop Does

Ontop is a **Virtual Knowledge Graph** (VKG) system. It sits on top of your existing relational data (Iceberg via Trino) and exposes it as a knowledge graph via SPARQL — without moving or duplicating any data.

```
SPARQL Query
     ↓
   Ontop
     ↓  (rewrites to SQL at query time)
   Trino
     ↓
 Iceberg Tables
```

The key word is *virtual*. There is no separate graph database. When a SPARQL query arrives, Ontop rewrites it into SQL, executes it against Trino, and returns the result as RDF triples.

### 2. The Three Files

Ontop needs three files to work:

| File | Purpose |
|------|---------|
| `tpch.owl` | **Ontology** — defines the conceptual schema (classes, properties) |
| `tpch.obda` | **Mappings** — binds ontology concepts to SQL tables |
| `ontop.properties` | **Connection** — JDBC config pointing to Trino |

---

## The Ontology (tpch.owl)

The ontology is written in **OWL Functional Syntax**. It defines the *blueprint* of your domain — what types of things exist and how they relate.

### Classes (Entity Types)

```
Declaration(Class(:Customer))
Declaration(Class(:Order))
Declaration(Class(:LineItem))
Declaration(Class(:Supplier))
Declaration(Class(:Part))
Declaration(Class(:Nation))
Declaration(Class(:Region))
```

These are the conceptual entity types. They map loosely to your Iceberg tables, but they are *business concepts*, not table names.

### Object Properties (Relationships)

```
Declaration(ObjectProperty(:placesOrder))
Declaration(ObjectProperty(:hasLineItem))
Declaration(ObjectProperty(:suppliedBy))
Declaration(ObjectProperty(:hasPart))
Declaration(ObjectProperty(:belongsToNation))
Declaration(ObjectProperty(:partOfRegion))
```

These define the *types of relationships* that can exist between entities. Each has a domain (source class) and range (target class):

```
ObjectPropertyDomain(:placesOrder :Customer)
ObjectPropertyRange(:placesOrder :Order)
```

This says: `placesOrder` goes *from* Customer *to* Order.

### The Full Relationship Graph

```
Customer ──placesOrder──► Order ──hasLineItem──► LineItem ──hasPart──► Part
    │                                                 │
belongsToNation                                  suppliedBy
    │                                                 │
  Nation ──partOfRegion──► Region              Supplier ──belongsToNation──► Nation
```

### Data Properties (Attributes)

```
Declaration(DataProperty(:customerName))
Declaration(DataProperty(:marketSegment))
Declaration(DataProperty(:accountBalance))
Declaration(DataProperty(:orderStatus))
...
```

These are the scalar attributes on each entity — equivalent to columns.

### Why OWL and Not Just a Schema?

An OWL ontology is machine-readable and formally defined. It enables:
- Reasoning over relationships (not just data retrieval)
- Formal domain/range constraints
- Interoperability with other semantic web tools
- Self-documenting domain model

---

## The Mappings (tpch.obda)

The `.obda` file is where the ontology meets the data. Each mapping entry has:
- A **target** — an RDF triple pattern using ontology terms
- A **source** — a SQL query against your Iceberg tables

### Anatomy of a Mapping

```
mappingId   customer-class
target      :customer/{custkey} a :Customer .
source      SELECT custkey FROM semantic_demo.ice_db.customer
```

**Target** — `{custkey}` is a template variable. For each row returned by the source query, Ontop creates a URI like `http://example.org/tpch#customer/12345` and asserts it is of type `:Customer`.

**Source** — plain SQL against your Trino catalog.

### Mapping Types

#### Class Assertion (what type is this entity?)
```
target      :customer/{custkey} a :Customer .
source      SELECT custkey FROM semantic_demo.ice_db.customer
```
Creates: `<:customer/1> rdf:type :Customer`

#### Data Property (scalar attribute)
```
target      :customer/{custkey} :customerName {name} .
source      SELECT custkey, name FROM semantic_demo.ice_db.customer
```
Creates: `<:customer/1> :customerName "Customer#000000001"`

#### Object Property (relationship between entities)
```
target      :customer/{custkey} :belongsToNation :nation/{nationkey} .
source      SELECT custkey, nationkey FROM semantic_demo.ice_db.customer
```
Creates: `<:customer/1> :belongsToNation <:nation/15>`

This is the key mapping — it encodes the FK relationship `customer.nationkey → nation.nationkey` as a graph edge, without the agent needing to know about foreign keys.

### How Ontop Rewrites SPARQL to SQL

When you run this SPARQL:
```sparql
PREFIX : <http://example.org/tpch#>
SELECT ?customerName ?nationName ?regionName WHERE {
  ?c a :Customer ;
     :customerName ?customerName ;
     :belongsToNation ?n .
  ?n :nationName ?nationName ;
     :partOfRegion ?r .
  ?r :regionName ?regionName .
}
```

Ontop looks up the mappings for each triple pattern and rewrites to:
```sql
SELECT
  c.name        AS customerName,
  n.name        AS nationName,
  r.name        AS regionName
FROM semantic_demo.ice_db.customer c
JOIN semantic_demo.ice_db.nation n   ON c.nationkey  = n.nationkey
JOIN semantic_demo.ice_db.region r   ON n.regionkey  = r.regionkey
```

The agent wrote SPARQL over business concepts. Ontop generated the SQL with the correct joins. The agent never saw a foreign key.

---

## The Properties File (ontop.properties)

```properties
jdbc.url=jdbc:trino://trino:8080/semantic_demo/ice_db
jdbc.driver=io.trino.jdbc.TrinoDriver
jdbc.user=admin
jdbc.password=
ontop.ontologyFile=/opt/ontop/input/tpch.owl
ontop.mappingFile=/opt/ontop/input/tpch.obda
ontop.propertiesFile=/opt/ontop/input/ontop.properties
```

This tells Ontop where to find the ontology and mappings, and how to connect to Trino.

---

## Practical Examples from Your Setup

### Example 1: Simple Class Query
```sparql
PREFIX : <http://example.org/tpch#>
SELECT ?name WHERE {
  ?c a :Customer ;
     :customerName ?name .
}
LIMIT 10
```

**What happens:**
1. Ontop matches `?c a :Customer` → `customer-class` mapping
2. Ontop matches `:customerName ?name` → `customer-name` mapping
3. Rewrites to: `SELECT name FROM semantic_demo.ice_db.customer LIMIT 10`
4. Returns customer names

### Example 2: Two-Hop Traversal
```sparql
PREFIX : <http://example.org/tpch#>
SELECT ?customerName ?nationName WHERE {
  ?c :customerName ?customerName ;
     :belongsToNation ?n .
  ?n :nationName ?nationName .
}
```

**What happens:**
1. `:belongsToNation` mapping provides the join key (`nationkey`)
2. Ontop generates: `SELECT c.name, n.name FROM customer c JOIN nation n ON c.nationkey = n.nationkey`
3. Agent traversed a graph edge — Ontop handled the join

### Example 3: Three-Hop Geographic Traversal
```sparql
PREFIX : <http://example.org/tpch#>
SELECT ?regionName (COUNT(?c) AS ?customerCount) WHERE {
  ?c a :Customer ;
     :belongsToNation ?n .
  ?n :partOfRegion ?r .
  ?r :regionName ?regionName .
}
GROUP BY ?regionName
ORDER BY DESC(?customerCount)
```

**What happens:**
1. Ontop follows: customer → nation → region (two joins)
2. Rewrites to:
```sql
SELECT r.name AS regionName, COUNT(c.custkey) AS customerCount
FROM semantic_demo.ice_db.customer c
JOIN semantic_demo.ice_db.nation n  ON c.nationkey = n.nationkey
JOIN semantic_demo.ice_db.region r  ON n.regionkey = r.regionkey
GROUP BY r.name
ORDER BY customerCount DESC
```
3. Agent asked a business question. Ontop resolved the join path.

### Example 4: FILTER on Business Concept
```sparql
PREFIX : <http://example.org/tpch#>
SELECT ?customerName ?accountBalance WHERE {
  ?c a :Customer ;
     :customerName ?customerName ;
     :accountBalance ?accountBalance .
  FILTER(?accountBalance > 5000)
}
ORDER BY DESC(?accountBalance)
```

**What happens:**
1. Ontop maps `:accountBalance` to `acctbal` column
2. Pushes the FILTER down to SQL: `WHERE acctbal > 5000`
3. The business concept "high-value" (balance > 5000) is encoded in the MCP tool, not guessed by the agent

---

## Ontology vs Knowledge Graph — Clarified

| Concept | What it is | In this setup |
|---------|-----------|---------------|
| **Ontology** | The schema/blueprint — classes and relationship types | `tpch.owl` |
| **Knowledge Graph** | The ontology + actual data instances | Ontop virtualizing Iceberg data |
| **Virtual KG** | Knowledge graph where data stays in the source system | What Ontop provides |

The ontology says: "Customers can `belongsToNation` Nations."
The knowledge graph says: "Customer #1 `belongsToNation` Germany."
Ontop makes the knowledge graph virtual — the facts live in Iceberg, Ontop just exposes them as graph traversals.

---

## Comparison: Ontop vs dbt Metricflow

| Aspect | dbt Metricflow | Ontop |
|--------|---------------|-------|
| **Query language** | MetricFlow DSL / REST API | SPARQL |
| **Output** | Metric values (numbers) | Graph traversals (entities + relationships) |
| **Best for** | "How much?" questions | "What is related to what?" questions |
| **Business concept** | Metric definitions (revenue, completion rate) | Entity relationships (customer → nation → region) |
| **SQL generation** | Yes — from metric + dimension definitions | Yes — from SPARQL + OBDA mappings |
| **Data movement** | No — queries Trino directly | No — queries Trino directly |
| **Agent value** | Consistent metric calculations | Consistent relationship traversal |

They are complementary. Metricflow answers quantitative business questions. Ontop answers structural and relational business questions. Together they form a complete semantic layer.

---

## SPARQL Basics for Your Setup

### Prefix Declaration
Always include at the top:
```sparql
PREFIX : <http://example.org/tpch#>
```

### Class Filter
```sparql
?c a :Customer .        -- ?c must be a Customer
```

### Property Traversal
```sparql
?c :belongsToNation ?n .   -- follow the relationship
?n :nationName ?name .     -- get an attribute
```

### Chained Properties (shorthand)
```sparql
?c a :Customer ;
   :customerName ?name ;
   :belongsToNation ?n .
```
The `;` chains multiple predicates on the same subject.

### Aggregation
```sparql
SELECT ?region (COUNT(?c) AS ?count) WHERE { ... }
GROUP BY ?region
ORDER BY DESC(?count)
```

### Filter
```sparql
FILTER(?accountBalance > 5000)
FILTER(UCASE(?regionName) = 'ASIA')
FILTER(CONTAINS(LCASE(?name), 'smith'))
```

---

## Testing Your Setup

### Check Ontop is running
```bash
curl http://localhost:8089/sparql \
  -d "query=SELECT * WHERE { ?s ?p ?o } LIMIT 1" \
  -H "Accept: application/json"
```

### Test a simple customer query
```bash
curl -X POST http://localhost:8089/sparql \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -H "Accept: application/json" \
  --data-urlencode "query=PREFIX : <http://example.org/tpch#>
SELECT ?name WHERE { ?c a :Customer ; :customerName ?name } LIMIT 3"
```

### Open the SPARQL portal
Navigate to `http://localhost:8089` — Ontop provides a built-in UI with your predefined demo queries from `tpch.toml`.

### Check logs
```bash
docker logs ontop
```

---

## Common Issues

### Issue 1: Ontop fails to start — OWL parse error
```
OntologyException: Problem parsing file tpch.owl
```
**Cause:** OWL file syntax error.
**Solution:** Ensure `tpch.owl` uses OWL Functional Syntax (not OWL XML). The file should start with `Prefix(:=...)` not `<?xml ...>`.

### Issue 2: SPARQL returns empty results
**Cause 1:** Trino not reachable from Ontop container.
**Solution:** Check `ontop.properties` — `jdbc.url` must use the Docker service name `trino`, not `localhost`.

**Cause 2:** Mapping source SQL references wrong catalog/schema.
**Solution:** Verify OBDA source queries use `semantic_demo.ice_db.<table>`.

### Issue 3: Ontology classes query returns empty
**Cause:** Ontop is a query rewriter, not a triple store. It does not serve `owl:Class` metadata via SPARQL introspection.
**Solution:** Use the `kg_ontology_classes` MCP tool which reads the OWL file directly.

### Issue 4: JDBC driver not found
```
ClassNotFoundException: io.trino.jdbc.TrinoDriver
```
**Solution:** Run `./ontop/download-jdbc.sh` to download `trino-jdbc.jar` before starting the container.

---

## Summary

**OWL Ontology** defines your domain model (classes + relationships)
↓
**OBDA Mappings** bind ontology concepts to Iceberg SQL tables
↓
**Ontop** rewrites SPARQL to SQL at query time (no data duplication)
↓
**Trino** executes the generated SQL against Iceberg
↓
**MCP Tools** expose graph traversals to agents via SPARQL

The power of Ontop is that it:
- Exposes your existing Iceberg data as a knowledge graph with zero duplication
- Automatically handles JOIN resolution from graph traversal patterns
- Provides a formal, machine-readable domain model (the ontology)
- Lets agents reason over business concepts and relationships, not table schemas
- Complements the metric store: metrics answer "how much", the knowledge graph answers "what relates to what"
