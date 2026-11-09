package com.travelmate.servlet;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Lets the HTML pages call the API when they are opened from disk (Origin "null") or
 * from a local dev server such as VS Code Live Server. Only local origins are allowed.
 */
@WebFilter("/api/*")
public class LocalCorsFilter extends HttpFilter {

    private static final Pattern LOCAL_ORIGIN = Pattern.compile("^https?://(localhost|127\\.0\\.0\\.1)(:\\d+)?$");

    @Override
    protected void doFilter(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws IOException, ServletException {
        String origin = req.getHeader("Origin");
        if (origin != null && (origin.equals("null") || LOCAL_ORIGIN.matcher(origin).matches())) {
            resp.setHeader("Access-Control-Allow-Origin", origin);
            resp.setHeader("Access-Control-Allow-Credentials", "true");
            resp.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
            resp.setHeader("Access-Control-Allow-Headers", "Content-Type");
            resp.setHeader("Vary", "Origin");
            if ("OPTIONS".equals(req.getMethod())) {
                resp.setStatus(HttpServletResponse.SC_NO_CONTENT);
                return;
            }
        }
        chain.doFilter(req, resp);
    }
}
