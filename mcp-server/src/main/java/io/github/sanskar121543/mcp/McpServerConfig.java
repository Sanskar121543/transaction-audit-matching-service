package io.github.sanskar121543.mcp;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import tools.jackson.databind.json.JsonMapper;

/** The same tools on either transport; only how messages arrive differs. */
@Configuration(proxyBeanMethods = false)
class McpServerConfig {

    static final String NAME = "transaction-audit-matching";
    static final String INSTRUCTIONS = """
            Read-only access to a bank reconciliation service. A run compares a ledger file with a bank statement \
            file; records that pair up are matches, the rest are breaks (MISSING_IN_BANK, MISSING_IN_LEDGER, \
            AMOUNT_MISMATCH, DUPLICATE). People work breaks from OPEN through INVESTIGATING to RESOLVED, and every \
            change is in the break's history. Start with list_runs, then run_breakdown for the big picture, \
            list_breaks to find specific ones, explain_break for why a break was not matched, and break_history \
            for who did what. Amounts are in cents. None of these tools can change anything. """
            + Tools.UNTRUSTED_TEXT;

    @Bean
    McpJsonMapper mcpJsonMapper(JsonMapper jsonMapper) {
        return new JacksonMcpJsonMapper(jsonMapper);
    }

    @Configuration(proxyBeanMethods = false)
    @Profile("!stdio")
    static class Http {

        @Bean
        HttpServletStreamableServerTransportProvider httpTransport(McpJsonMapper jsonMapper, McpProperties properties) {
            return HttpServletStreamableServerTransportProvider.builder()
                    .jsonMapper(jsonMapper)
                    .mcpEndpoint("/mcp")
                    .httpHeaderValidator(new HttpGuard(properties.http()))
                    .maxRequestSize(64 * 1024)
                    .keepAliveInterval(Duration.ofSeconds(30))
                    .build();
        }

        @Bean
        ServletRegistrationBean<HttpServletStreamableServerTransportProvider> mcpServlet(
                HttpServletStreamableServerTransportProvider transport) {
            ServletRegistrationBean<HttpServletStreamableServerTransportProvider> registration =
                    new ServletRegistrationBean<>(transport, "/mcp", "/mcp/*");
            registration.setAsyncSupported(true);
            return registration;
        }

        @Bean(destroyMethod = "closeGracefully")
        McpSyncServer mcpServer(HttpServletStreamableServerTransportProvider transport, McpJsonMapper jsonMapper,
                Tools tools) {
            return McpServer.sync(transport)
                    .serverInfo(NAME, "0.0.1")
                    .instructions(INSTRUCTIONS)
                    .jsonMapper(jsonMapper)
                    .capabilities(ServerCapabilities.builder().tools(false).build())
                    .tools(tools.all())
                    .build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Profile("stdio")
    static class Stdio {

        /** Released when the client closes our stdin: the session is over and the process should end. */
        @Bean
        CountDownLatch stdinClosed() {
            return new CountDownLatch(1);
        }

        @Bean(destroyMethod = "closeGracefully")
        McpSyncServer mcpServer(McpJsonMapper jsonMapper, Tools tools, CountDownLatch stdinClosed) {
            InputStream in = new FilterInputStream(System.in) {
                @Override
                public int read() throws IOException {
                    return ended(super.read());
                }

                @Override
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    return ended(super.read(buffer, offset, length));
                }

                private int ended(int result) {
                    if (result < 0) {
                        stdinClosed.countDown();
                    }
                    return result;
                }
            };
            return McpServer.sync(new StdioServerTransportProvider(jsonMapper, in, System.out))
                    .serverInfo(NAME, "0.0.1")
                    .instructions(INSTRUCTIONS)
                    .jsonMapper(jsonMapper)
                    .capabilities(ServerCapabilities.builder().tools(false).build())
                    .tools(tools.all())
                    .build();
        }

        /**
         * No web server keeps the JVM alive in stdio mode: wait for the client to close stdin, then shut down
         * cleanly. Clients that end the process instead are fine too.
         */
        @Bean
        ApplicationRunner exitWhenStdinCloses(CountDownLatch stdinClosed, ConfigurableApplicationContext context) {
            return args -> {
                stdinClosed.await();
                System.exit(SpringApplication.exit(context));
            };
        }
    }
}
