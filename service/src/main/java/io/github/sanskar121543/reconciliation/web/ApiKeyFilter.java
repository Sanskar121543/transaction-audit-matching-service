package io.github.sanskar121543.reconciliation.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Deny by default: every path needs a valid key except the dashboard's static files and the actuator
 * endpoints (nginx hides those from outside; Prometheus scrapes them on the internal network). A valid
 * key must also belong to a role that may call the path.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";
    public static final String CALLER_ATTRIBUTE = "matching.caller";

    private static final Set<String> OPEN_PATHS = Set.of("/", "/index.html", "/app.js", "/styles.css",
            "/favicon.ico");
    private static final List<String> OPEN_PREFIXES = List.of("/vendor/", "/actuator/");

    private final ApiKeys apiKeys;
    private final JsonMapper jsonMapper;

    public ApiKeyFilter(ApiKeys apiKeys, JsonMapper jsonMapper) {
        this.apiKeys = apiKeys;
        this.jsonMapper = jsonMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = routedPath(request);
        return OPEN_PATHS.contains(path) || OPEN_PREFIXES.stream().anyMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<ApiCaller> caller = apiKeys.callerFor(request.getHeader(HEADER));
        if (caller.isEmpty()) {
            reject(request, response, Problems.of(HttpStatus.UNAUTHORIZED, "unauthorized",
                    "Missing or invalid API key", "Send a valid key in the " + HEADER + " header."));
            return;
        }
        if (!AccessPolicy.allows(caller.get().role(), request.getMethod(), routedPath(request))) {
            reject(request, response, Problems.of(HttpStatus.FORBIDDEN, "forbidden", "Not allowed for this key",
                    "This API key is read-only."));
            return;
        }
        request.setAttribute(CALLER_ATTRIBUTE, caller.get());
        chain.doFilter(request, response);
    }

    // The decoded path without ;params, i.e. what Spring MVC routes on. The raw URI would let
    // "/v1;x/runs" or "/%761/runs" look like a different path and slip past these checks.
    private static String routedPath(HttpServletRequest request) {
        return UrlPathHelper.defaultInstance.getPathWithinApplication(request);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, ProblemDetail problem)
            throws IOException {
        problem.setInstance(URI.create(request.getRequestURI()));
        response.setStatus(problem.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        jsonMapper.writeValue(response.getOutputStream(), problem);
    }
}
