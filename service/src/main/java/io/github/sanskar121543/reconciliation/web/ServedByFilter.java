package io.github.sanskar121543.reconciliation.web;

import io.github.sanskar121543.reconciliation.common.InstanceInfo;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class ServedByFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Served-By";

    private final InstanceInfo instance;

    public ServedByFilter(InstanceInfo instance) {
        this.instance = instance;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader(HEADER, instance.id());
        chain.doFilter(request, response);
    }
}
