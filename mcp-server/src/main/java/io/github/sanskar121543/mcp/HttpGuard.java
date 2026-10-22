package io.github.sanskar121543.mcp;

import io.modelcontextprotocol.server.transport.HeaderAccessor;
import io.modelcontextprotocol.server.transport.ServerHttpHeaderValidator;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;

/**
 * Checks every request to the Streamable HTTP endpoint before the MCP SDK sees it:
 * <ol>
 * <li>{@code Authorization: Bearer <MCP_HTTP_TOKEN>}, compared in constant time (401 otherwise);</li>
 * <li>the Host header names an allowed host, so a web page that rebinds its own DNS name to 127.0.0.1 cannot
 * reach this server from the victim's browser (403);</li>
 * <li>an Origin header, when a browser sends one, is an allowed origin (403).</li>
 * </ol>
 * With no token configured the HTTP transport refuses everything: there is no unauthenticated mode.
 */
final class HttpGuard implements ServerHttpHeaderValidator {

    private final byte[] token;
    private final List<String> allowedHosts;
    private final List<String> allowedOrigins;

    HttpGuard(McpProperties.Http http) {
        this.token = http.token() == null || http.token().isBlank() ? null : http.token().getBytes(StandardCharsets.UTF_8);
        this.allowedHosts = http.allowedHosts().stream().map(h -> h.toLowerCase(Locale.ROOT)).toList();
        this.allowedOrigins = http.allowedOrigins().stream().map(o -> o.toLowerCase(Locale.ROOT)).toList();
    }

    @Override
    public void validate(HeaderAccessor headers) throws ServerTransportSecurityException {
        if (token == null) {
            throw new ServerTransportSecurityException(401, "MCP_HTTP_TOKEN is not set: the HTTP transport is closed");
        }
        String authorization = first(headers, "Authorization");
        byte[] presented = authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)
                ? authorization.substring(7).strip().getBytes(StandardCharsets.UTF_8) : new byte[0];
        if (!MessageDigest.isEqual(presented, token)) {
            throw new ServerTransportSecurityException(401, "Missing or wrong bearer token");
        }
        String host = first(headers, "Host");
        if (host == null || !allowedHosts.contains(hostName(host))) {
            throw new ServerTransportSecurityException(403, "Host not allowed");
        }
        String origin = first(headers, "Origin");
        if (origin != null && !allowedOrigins.contains(withoutPort(origin.toLowerCase(Locale.ROOT)))) {
            throw new ServerTransportSecurityException(403, "Origin not allowed");
        }
    }

    private static String first(HeaderAccessor headers, String name) {
        List<String> values = headers.getHeader(name);
        return values == null || values.isEmpty() ? null : values.getFirst();
    }

    /** "localhost:8095" -> "localhost"; "[::1]:8095" -> "[::1]". */
    static String hostName(String host) {
        String lower = host.strip().toLowerCase(Locale.ROOT);
        int colon = lower.lastIndexOf(':');
        return colon > lower.lastIndexOf(']') ? lower.substring(0, colon) : lower;
    }

    /** "http://localhost:6274" -> "http://localhost". */
    static String withoutPort(String origin) {
        int schemeEnd = origin.indexOf("://");
        int colon = origin.lastIndexOf(':');
        return colon > schemeEnd + 2 && colon > origin.lastIndexOf(']') ? origin.substring(0, colon) : origin;
    }
}
