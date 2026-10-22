package io.github.sanskar121543.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The packaged jar started the way a desktop AI client starts it: {@code java -jar mcp-server.jar --stdio},
 * configured through environment variables, speaking MCP over stdin/stdout. Anything else on stdout (a banner,
 * a log line) would break the protocol, so a working session is itself the check that nothing else is printed.
 */
class McpStdioIT {

    @Test
    void theJarServesTheSameToolsOverStdio() {
        ServiceUnderTest service = ServiceUnderTest.get();
        String java = ProcessHandle.current().info().command().orElse("java");
        ServerParameters parameters = ServerParameters.builder(java)
                .args("-Xmx128m", "-jar", packagedJar().toString(), "--stdio")
                .addEnvVar("SERVICE_URL", service.url())
                .addEnvVar("MCP_API_KEY", ServiceUnderTest.READ_ONLY_KEY)
                .build();
        McpSyncClient client = McpClient.sync(new StdioClientTransport(parameters,
                        new JacksonMcpJsonMapper(JsonMapper.builder().build())))
                .requestTimeout(Duration.ofSeconds(30))
                .initializationTimeout(Duration.ofSeconds(60))
                .build();
        try {
            client.initialize();

            assertThat(client.getServerInfo().name()).isEqualTo("transaction-audit-matching");
            assertThat(client.listTools().tools()).extracting(Tool::name).hasSize(7).contains("explain_break");
            CallToolResult result = client.callTool(new CallToolRequest("get_run", Map.of("runId", service.runId())));
            assertThat(result.isError()).isFalse();
            assertThat(((TextContent) result.content().getFirst()).text()).contains("\"label\":\"mcp fixture\"");
        } finally {
            client.closeGracefully();
        }
    }

    private static Path packagedJar() {
        try (Stream<Path> files = Files.list(Path.of("target"))) {
            return files.filter(p -> p.getFileName().toString().matches("mcp-server-.*\\.jar"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("No jar in target/: run ./mvnw verify, not just the test"));
        } catch (IOException e) {
            throw new IllegalStateException("No target/ directory: run ./mvnw verify", e);
        }
    }
}
