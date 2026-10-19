package io.github.sanskar121543.reconciliation.web;

/** The authenticated client behind a request. "Who" in the audit trail is this client id. */
public record ApiCaller(String clientId, Role role) {
}
