package com.travelmate.servlet;

import com.travelmate.db.DatabaseInitializer;
import com.travelmate.util.JsonUtil;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.sql.SQLException;

/**
 * Base class for the JSON API servlets: answers 503 while the database is unreachable
 * (retrying the connection on each request) and turns SQL errors into JSON 500s.
 */
public abstract class ApiServlet extends HttpServlet {

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        req.setCharacterEncoding("UTF-8");
        if (!DatabaseInitializer.isReady() && !DatabaseInitializer.initialize()) {
            JsonUtil.error(resp, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "Database unavailable: " + DatabaseInitializer.lastError());
            return;
        }
        try {
            super.service(req, resp);
        } catch (RuntimeException e) {
            if (!(e.getCause() instanceof SQLException)) throw e;
            log("Database error", e.getCause());
            JsonUtil.error(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Database error: " + e.getCause().getMessage());
        }
    }

    /** Wraps checked SQL exceptions so handlers can stay compact. */
    protected static RuntimeException db(SQLException e) {
        return new RuntimeException(e);
    }
}
