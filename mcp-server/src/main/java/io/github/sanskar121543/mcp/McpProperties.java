package io.github.sanskar121543.mcp;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param serviceUrl the matching service, usually through nginx
 * @param apiKey a <b>read-only</b> API key for it ({@link KeyCheck} refuses to work with any other)
 * @param timeout per request to the service
 * @param maxResultChars a tool result longer than this is refused with a hint to page instead of truncated
 * @param http the Streamable HTTP transport's own protection
 */
@ConfigurationProperties("mcp")
public record McpProperties(URI serviceUrl, String apiKey, Duration timeout, int maxResultChars, Http http) {

    /**
     * @param token clients must send {@code Authorization: Bearer <token>}
     * @param allowedHosts accepted Host header names (any port), against DNS rebinding
     * @param allowedOrigins accepted Origin values, for browser-based clients (absent Origin is accepted)
     */
    public record Http(String token, List<String> allowedHosts, List<String> allowedOrigins) {
    }
}
