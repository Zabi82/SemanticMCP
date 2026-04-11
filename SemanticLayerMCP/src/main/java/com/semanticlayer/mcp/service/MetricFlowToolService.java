package com.semanticlayer.mcp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Service
public class MetricFlowToolService {
    private static final Logger logger = LoggerFactory.getLogger(MetricFlowToolService.class);
    
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    
    @Value("${metricflow.url}")
    private String metricflowUrl;
    
    public MetricFlowToolService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
        this.objectMapper = new ObjectMapper();
    }
    
    @Tool(name = "list_metrics", description = "List all available metrics from the dbt semantic layer.")
    public Object listMetrics() {
        try {
            String url = metricflowUrl + "/api/v1/metrics";
            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                logger.info("Successfully fetched metrics list");
                return response.getBody();
            }
            return Map.of("error", "Failed to fetch metrics");
        } catch (Exception e) {
            logger.error("Error fetching metrics: {}", e.getMessage());
            return Map.of("error", e.getMessage());
        }
    }
    
    @Tool(name = "get_metric_definition", description = "Get the definition and metadata for a specific metric.")
    public Object getMetricDefinition(String metricName) {
        if (metricName == null || metricName.trim().isEmpty()) {
            return Map.of("error", "Metric name must be provided");
        }
        
        try {
            String url = metricflowUrl + "/api/v1/metrics/" + metricName;
            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                logger.info("Successfully fetched definition for metric: {}", metricName);
                return response.getBody();
            }
            return Map.of("error", "Failed to fetch metric definition for: " + metricName);
        } catch (Exception e) {
            logger.error("Error fetching metric definition for {}: {}", metricName, e.getMessage());
            return Map.of("error", e.getMessage());
        }
    }
    
    @Tool(name = "list_dimensions", description = "List all available dimensions for a specific metric.")
    public Object listDimensions(String metricName) {
        if (metricName == null || metricName.trim().isEmpty()) {
            return Map.of("error", "Metric name must be provided");
        }
        
        try {
            String url = metricflowUrl + "/api/v1/metrics/" + metricName + "/dimensions";
            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                logger.info("Successfully fetched dimensions for metric: {}", metricName);
                return response.getBody();
            }
            return Map.of("error", "Failed to fetch dimensions for metric: " + metricName);
        } catch (Exception e) {
            logger.error("Error fetching dimensions for metric {}: {}", metricName, e.getMessage());
            return Map.of("error", e.getMessage());
        }
    }
    
    @Tool(name = "query_metric", description = "Query a metric with optional dimensions and time range. " +
          "Parameters: metricName (required), dimensions (optional array of dimension names to group by), " +
          "orderBy (optional - column to sort by), orderDirection (optional - 'DESC' or 'ASC', defaults to 'DESC'), " +
          "limit (optional integer), " +
          "startTime (optional - start date filter in YYYY-MM-DD format, e.g. '1995-01-01' for Q1 1995), " +
          "endTime (optional - end date filter in YYYY-MM-DD format, e.g. '1995-03-31' for Q1 1995), " +
          "entityFilter (optional - list of entity ID values as strings to scope results to a specific cohort, " +
          "e.g. custkey values from kg_premium_customers — use this instead of fetching all results and filtering in-memory), " +
          "entityColumn (optional - the dimension column to apply entityFilter on, " +
          "e.g. 'order_id__customer_key' when filtering by customer IDs from the knowledge graph).")
    public Object queryMetric(String metricName, String[] dimensions, String orderBy, String orderDirection,
                              Integer limit, String startTime, String endTime,
                              String[] entityFilter, String entityColumn) {
        if (metricName == null || metricName.trim().isEmpty()) {
            return Map.of("error", "Metric name must be provided");
        }
        
        try {
            // Build request body
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("metric_name", metricName);
            
            if (dimensions != null && dimensions.length > 0) {
                requestBody.put("dimensions", Arrays.asList(dimensions));
            }
            
            if (orderBy != null && !orderBy.trim().isEmpty()) {
                requestBody.put("order_by", orderBy);
            }
            
            if (orderDirection != null && !orderDirection.trim().isEmpty()) {
                requestBody.put("order_direction", orderDirection.toUpperCase());
            }
            
            if (limit != null && limit > 0) {
                requestBody.put("limit", limit);
            }

            if (startTime != null && !startTime.trim().isEmpty()) {
                requestBody.put("start_time", startTime.trim());
            }

            if (endTime != null && !endTime.trim().isEmpty()) {
                requestBody.put("end_time", endTime.trim());
            }

            if (entityFilter != null && entityFilter.length > 0) {
                requestBody.put("entity_filter", Arrays.asList(entityFilter));
            }

            if (entityColumn != null && !entityColumn.trim().isEmpty()) {
                requestBody.put("entity_column", entityColumn.trim());
            }

            String url = metricflowUrl + "/api/v1/query";
            ResponseEntity<Map> response = restTemplate.postForEntity(url, requestBody, Map.class);
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                logger.info("Successfully queried metric: {} with dimensions: {}", metricName, 
                    dimensions != null ? Arrays.toString(dimensions) : "none");
                return response.getBody();
            }
            return Map.of("error", "Failed to query metric: " + metricName);
        } catch (Exception e) {
            logger.error("Error querying metric {}: {}", metricName, e.getMessage());
            return Map.of("error", e.getMessage());
        }
    }
    
    @Tool(name = "explain_metric_query", description = "Get the SQL query that metricflow would generate for a metric query without executing it. Useful for understanding how the semantic layer translates metrics into SQL. Parameters: metricName (required), dimensions (optional array), orderBy (optional), limit (optional).")
    public Object explainMetricQuery(String metricName, String[] dimensions, String orderBy, Integer limit) {
        if (metricName == null || metricName.trim().isEmpty()) {
            return Map.of("error", "Metric name must be provided");
        }
        
        try {
            // Build request body
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("metric_name", metricName);
            
            if (dimensions != null && dimensions.length > 0) {
                requestBody.put("dimensions", Arrays.asList(dimensions));
            }
            
            if (orderBy != null && !orderBy.trim().isEmpty()) {
                requestBody.put("order_by", orderBy);
            }
            
            if (limit != null && limit > 0) {
                requestBody.put("limit", limit);
            }
            
            String url = metricflowUrl + "/api/v1/explain";
            ResponseEntity<Map> response = restTemplate.postForEntity(url, requestBody, Map.class);
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                logger.info("Successfully explained metric query: {}", metricName);
                return response.getBody();
            }
            return Map.of("error", "Failed to explain metric query: " + metricName);
        } catch (Exception e) {
            logger.error("Error explaining metric query {}: {}", metricName, e.getMessage());
            return Map.of("error", e.getMessage());
        }
    }
}
