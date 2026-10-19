package io.github.sanskar121543.reconciliation.web;

import java.net.URI;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

public final class Problems {

    private Problems() {
    }

    public static ProblemDetail of(HttpStatusCode status, String type, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(URI.create("/problems/" + type));
        problem.setTitle(title);
        if (detail != null) {
            problem.setDetail(detail);
        }
        return problem;
    }
}
