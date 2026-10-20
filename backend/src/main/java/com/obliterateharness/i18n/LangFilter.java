package com.obliterateharness.i18n;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Sets the message language for the request. X-Lang (sent by the UI) wins; otherwise English.
 * Accept-Language is ignored on purpose: MCP clients and scripts get English unless a tool switches to the project locale.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LangFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        I18n.set(Lang.parse(request.getHeader("X-Lang"), Lang.en));
        try {
            chain.doFilter(request, response);
        } finally {
            I18n.clear();
        }
    }
}
