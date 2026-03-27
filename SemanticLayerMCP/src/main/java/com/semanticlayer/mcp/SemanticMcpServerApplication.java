package com.semanticlayer.mcp;

import com.semanticlayer.mcp.service.KnowledgeGraphToolService;
import com.semanticlayer.mcp.service.MetricFlowToolService;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class SemanticMcpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SemanticMcpServerApplication.class, args);
    }

    @Bean
    public ToolCallbackProvider semanticLayerTools(MetricFlowToolService metricFlowToolService,
                                                    KnowledgeGraphToolService knowledgeGraphToolService) {
        return MethodToolCallbackProvider.builder()
            .toolObjects(metricFlowToolService, knowledgeGraphToolService)
            .build();
    }
}
