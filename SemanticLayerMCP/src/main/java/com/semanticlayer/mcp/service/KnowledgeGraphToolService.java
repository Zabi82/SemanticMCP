package com.semanticlayer.mcp.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import jakarta.annotation.PostConstruct;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * MCP tools for querying the TPC-H Knowledge Graph via Ontop SPARQL endpoint.
 * Ontop virtualizes the Iceberg/Trino data as an OWL ontology — no data duplication.
 */
@Service
public class KnowledgeGraphToolService {

    private static final Logger logger = LoggerFactory.getLogger(KnowledgeGraphToolService.class);

    @Value("${ontop.sparql.endpoint:http://localhost:8089/sparql}")
    private String sparqlEndpoint;

    @Value("${ontop.owl.file:}")
    private String owlFilePath;

    private final RestTemplate restTemplate = new RestTemplate();

    // Parsed from OWL file at startup
    private List<Map<String, String>> ontologyClasses = new ArrayList<>();
    private List<Map<String, String>> objectProperties = new ArrayList<>();

    @PostConstruct
    public void loadOntology() {
        if (owlFilePath == null || owlFilePath.isBlank()) {
            logger.info("ontop.owl.file not configured, ontology schema will not be pre-loaded");
            return;
        }
        try {
            String content = Files.readString(Path.of(owlFilePath));
            ontologyClasses = parseClasses(content);
            objectProperties = parseObjectProperties(content);
            logger.info("Loaded {} classes and {} object properties from {}", ontologyClasses.size(), objectProperties.size(), owlFilePath);
        } catch (Exception e) {
            logger.warn("Could not load OWL file from {}: {}", owlFilePath, e.getMessage());
        }
    }

    // Parse Declaration(Class(:Foo)) and AnnotationAssertion(rdfs:label :Foo "...") from OWL functional syntax
    private List<Map<String, String>> parseClasses(String content) {
        List<Map<String, String>> classes = new ArrayList<>();
        Pattern declPattern = Pattern.compile("Declaration\\(Class\\(:(\\w+)\\)\\)");
        Pattern labelPattern = Pattern.compile("AnnotationAssertion\\(rdfs:label :(\\w+) \"([^\"]+)\"\\)");

        Map<String, String> labels = new LinkedHashMap<>();
        Matcher lm = labelPattern.matcher(content);
        while (lm.find()) labels.put(lm.group(1), lm.group(2));

        Matcher dm = declPattern.matcher(content);
        while (dm.find()) {
            String name = dm.group(1);
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("class", "http://example.org/tpch#" + name);
            entry.put("label", labels.getOrDefault(name, name));
            classes.add(entry);
        }
        return classes;
    }

    // Parse ObjectPropertyDomain/Range pairs to build relationship list
    private List<Map<String, String>> parseObjectProperties(String content) {
        List<Map<String, String>> props = new ArrayList<>();
        Pattern declPattern = Pattern.compile("Declaration\\(ObjectProperty\\(:(\\w+)\\)\\)");
        Pattern domainPattern = Pattern.compile("ObjectPropertyDomain\\(:(\\w+) :(\\w+)\\)");
        Pattern rangePattern = Pattern.compile("ObjectPropertyRange\\(:(\\w+) :(\\w+)\\)");

        Map<String, String> domains = new LinkedHashMap<>();
        Map<String, String> ranges = new LinkedHashMap<>();

        Matcher dom = domainPattern.matcher(content);
        while (dom.find()) domains.put(dom.group(1), dom.group(2));

        Matcher ran = rangePattern.matcher(content);
        while (ran.find()) ranges.put(ran.group(1), ran.group(2));

        Matcher dm = declPattern.matcher(content);
        while (dm.find()) {
            String prop = dm.group(1);
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("property", prop);
            entry.put("from", domains.getOrDefault(prop, "?"));
            entry.put("to", ranges.getOrDefault(prop, "?"));
            props.add(entry);
        }
        return props;
    }

    @Tool(name = "kg_sparql_query",
          description = "Execute a SPARQL SELECT query against the TPC-H knowledge graph exposed by Ontop. " +
                        "The ontology models Customers, Orders, LineItems, Suppliers, Parts, Nations and Regions " +
                        "with relationships: placesOrder, hasLineItem, suppliedBy, hasPart, belongsToNation, partOfRegion. " +
                        "Use prefix: PREFIX : <http://example.org/tpch#>. " +
                        "Example: SELECT ?name WHERE { ?c a :Customer ; :customerName ?name ; :belongsToNation ?n . ?n :nationName 'GERMANY' }")
    public Object executeSparqlQuery(String sparqlQuery) {
        if (sparqlQuery == null || sparqlQuery.trim().isEmpty()) {
            return Map.of("error", "SPARQL query must be provided.");
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));

            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("query", sparqlQuery);

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);
            ResponseEntity<Map> response = restTemplate.exchange(sparqlEndpoint, HttpMethod.POST, request, Map.class);

            Map<String, Object> responseBody = response.getBody();
            if (responseBody == null) {
                return Map.of("error", "Empty response from SPARQL endpoint");
            }
            return parseSparqlResults(responseBody);
        } catch (Exception e) {
            logger.error("SPARQL query failed: {}", e.getMessage());
            return Map.of("error", e.getMessage(), "endpoint", sparqlEndpoint);
        }
    }

    @Tool(name = "kg_customers_by_region",
          description = "Traverse the knowledge graph to find customers grouped by geographic region and nation. " +
                        "Returns a summary count per region/nation by default. " +
                        "Optionally filter by regionName (e.g. 'ASIA', 'EUROPE') or nationName to get specific customers. " +
                        "Demonstrates graph traversal: Customer → Nation → Region without SQL joins.")
    public Object getCustomersByRegion(String regionName, String nationName) {
        if (regionName != null && !regionName.isBlank()) {
            // Filtered: return actual customers for a specific region
            String safe = regionName.replace("'", "\\'").toUpperCase();
            String query = String.format("""
                    PREFIX : <http://example.org/tpch#>
                    SELECT ?customerName ?marketSegment ?nationName WHERE {
                      ?c a :Customer ;
                         :customerName ?customerName ;
                         :marketSegment ?marketSegment ;
                         :belongsToNation ?n .
                      ?n :nationName ?nationName ;
                         :partOfRegion ?r .
                      ?r :regionName ?regionName .
                      FILTER(UCASE(?regionName) = '%s')
                    }
                    ORDER BY ?nationName
                    LIMIT 50
                    """, safe);
            return executeSparqlQuery(query);
        } else if (nationName != null && !nationName.isBlank()) {
            String safe = nationName.replace("'", "\\'").toUpperCase();
            String query = String.format("""
                    PREFIX : <http://example.org/tpch#>
                    SELECT ?customerName ?marketSegment WHERE {
                      ?c a :Customer ;
                         :customerName ?customerName ;
                         :marketSegment ?marketSegment ;
                         :belongsToNation ?n .
                      ?n :nationName ?nationName .
                      FILTER(UCASE(?nationName) = '%s')
                    }
                    LIMIT 50
                    """, safe);
            return executeSparqlQuery(query);
        } else {
            // Default: return customer count per region — not raw rows
            String query = """
                    PREFIX : <http://example.org/tpch#>
                    SELECT ?regionName ?nationName (COUNT(?c) AS ?customerCount) WHERE {
                      ?c a :Customer ;
                         :belongsToNation ?n .
                      ?n :nationName ?nationName ;
                         :partOfRegion ?r .
                      ?r :regionName ?regionName .
                    }
                    GROUP BY ?regionName ?nationName
                    ORDER BY ?regionName ?nationName
                    """;
            return executeSparqlQuery(query);
        }
    }

    @Tool(name = "kg_premium_customers",
          description = "Find premium customers (account balance > 8000) with their geographic context. " +
                        "Premium customers are defined as those in the top tier of account balance, indicating " +
                        "strong creditworthiness and purchasing capacity. " +
                        "Demonstrates business concept resolution via the knowledge graph — 'premium' is defined " +
                        "semantically, not as a raw SQL filter the agent had to guess.")
    public Object getHighValueCustomers() {
        String query = """
                PREFIX : <http://example.org/tpch#>
                SELECT ?customerName ?accountBalance ?marketSegment ?nationName ?regionName WHERE {
                  ?c a :Customer ;
                     :customerName ?customerName ;
                     :accountBalance ?accountBalance ;
                     :marketSegment ?marketSegment ;
                     :belongsToNation ?n .
                  ?n :nationName ?nationName ;
                     :partOfRegion ?r .
                  ?r :regionName ?regionName .
                  FILTER(?accountBalance > 8000)
                }
                ORDER BY DESC(?accountBalance)
                LIMIT 25
                """;
        return executeSparqlQuery(query);
    }

    @Tool(name = "kg_customer_order_graph",
          description = "Traverse the full Customer → Order → LineItem → Part/Supplier graph for a given customer name. " +
                        "Shows multi-hop graph traversal that would require 4-5 SQL joins otherwise.")
    public Object getCustomerOrderGraph(String customerName) {
        if (customerName == null || customerName.trim().isEmpty()) {
            return Map.of("error", "customerName must be provided.");
        }
        String safe = customerName.replace("'", "\\'");
        String query = String.format("""
                PREFIX : <http://example.org/tpch#>
                SELECT ?customerName ?orderStatus ?totalPrice ?orderDate ?partName ?brand ?supplierName WHERE {
                  ?c a :Customer ;
                     :customerName ?customerName ;
                     :placesOrder ?o .
                  ?o :orderStatus ?orderStatus ;
                     :totalPrice ?totalPrice ;
                     :orderDate ?orderDate ;
                     :hasLineItem ?li .
                  ?li :hasPart ?p ;
                      :suppliedBy ?s .
                  ?p :partName ?partName ;
                     :brand ?brand .
                  ?s :supplierName ?supplierName .
                  FILTER(CONTAINS(LCASE(?customerName), LCASE('%s')))
                }
                LIMIT 20
                """, safe);
        return executeSparqlQuery(query);
    }

    @Tool(name = "kg_ontology_classes",
          description = "List all classes (entity types) defined in the TPC-H ontology. " +
                        "Shows the ontology schema — the conceptual blueprint of the knowledge graph.")
    public Object getOntologyClasses() {
        // Ontop is a virtual KG query rewriter — it does not serve owl:Class metadata via SPARQL.
        // We parse the OWL file directly at startup to return the live ontology schema.
        if (ontologyClasses.isEmpty()) {
            return Map.of("error", "Ontology not loaded. Set ontop.owl.file in application.properties.");
        }
        return Map.of(
            "ontology", "http://example.org/tpch",
            "classes", ontologyClasses,
            "objectProperties", objectProperties
        );
    }

    @Tool(name = "kg_suppliers_by_region",
          description = "Find all suppliers and their geographic region via knowledge graph traversal: Supplier → Nation → Region. " +
                        "Returns supplier count per region by default. Optionally filter by regionName or nationName for details.")
    public Object getSuppliersByRegion(String regionName, String nationName) {
        if (regionName != null && !regionName.isBlank()) {
            String safe = regionName.replace("'", "\\'").toUpperCase();
            String query = String.format("""
                    PREFIX : <http://example.org/tpch#>
                    SELECT ?supplierName ?accountBalance ?nationName WHERE {
                      ?s a :Supplier ;
                         :supplierName ?supplierName ;
                         :accountBalance ?accountBalance ;
                         :belongsToNation ?n .
                      ?n :nationName ?nationName ;
                         :partOfRegion ?r .
                      ?r :regionName ?regionName .
                      FILTER(UCASE(?regionName) = '%s')
                    }
                    ORDER BY ?nationName
                    LIMIT 50
                    """, safe);
            return executeSparqlQuery(query);
        } else if (nationName != null && !nationName.isBlank()) {
            String safe = nationName.replace("'", "\\'").toUpperCase();
            String query = String.format("""
                    PREFIX : <http://example.org/tpch#>
                    SELECT ?supplierName ?accountBalance WHERE {
                      ?s a :Supplier ;
                         :supplierName ?supplierName ;
                         :accountBalance ?accountBalance ;
                         :belongsToNation ?n .
                      ?n :nationName ?nationName .
                      FILTER(UCASE(?nationName) = '%s')
                    }
                    LIMIT 50
                    """, safe);
            return executeSparqlQuery(query);
        } else {
            // Default: count per region — not raw rows
            String query = """
                    PREFIX : <http://example.org/tpch#>
                    SELECT ?regionName (COUNT(?s) AS ?supplierCount) WHERE {
                      ?s a :Supplier ;
                         :belongsToNation ?n .
                      ?n :partOfRegion ?r .
                      ?r :regionName ?regionName .
                    }
                    GROUP BY ?regionName
                    ORDER BY DESC(?supplierCount)
                    """;
            return executeSparqlQuery(query);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseSparqlResults(Map<String, Object> raw) {
        try {
            Map<String, Object> head = (Map<String, Object>) raw.get("head");
            Map<String, Object> results = (Map<String, Object>) raw.get("results");
            if (head == null || results == null) {
                return Map.of("raw", raw);
            }
            List<String> vars = (List<String>) head.get("vars");
            List<Map<String, Object>> bindings = (List<Map<String, Object>>) results.get("bindings");

            List<Map<String, Object>> rows = new ArrayList<>();
            if (bindings != null) {
                for (Map<String, Object> binding : bindings) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (String var : vars) {
                        Map<String, Object> val = (Map<String, Object>) binding.get(var);
                        row.put(var, val != null ? val.get("value") : null);
                    }
                    rows.add(row);
                }
            }
            logger.info("SPARQL query returned {} rows", rows.size());
            return Map.of("columns", vars, "rows", rows, "count", rows.size());
        } catch (Exception e) {
            logger.warn("Could not parse SPARQL results: {}", e.getMessage());
            return Map.of("raw", raw);
        }
    }
}
