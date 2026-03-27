package com.mcp.datalakehouse;

import com.mcp.datalakehouse.tool.TrinoToolService;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class McpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpServerApplication.class, args);
    }

    @Bean
    public ToolCallbackProvider dataLakehouseTools(TrinoToolService trinoToolService) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(trinoToolService)
                .build();
    }
}
