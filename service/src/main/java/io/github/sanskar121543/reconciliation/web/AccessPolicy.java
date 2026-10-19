package io.github.sanskar121543.reconciliation.web;

/**
 * What each role may call. Operators may call everything; read-only clients (the MCP server) may only
 * read. The path is the normalised one Spring MVC routes on, never the raw URI.
 */
final class AccessPolicy {

    private AccessPolicy() {
    }

    static boolean allows(Role role, String method, String path) {
        if (role == Role.OPERATOR) {
            return true;
        }
        return "GET".equals(method) || "HEAD".equals(method);
    }
}
