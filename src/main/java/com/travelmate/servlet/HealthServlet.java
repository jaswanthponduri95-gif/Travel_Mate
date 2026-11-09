package com.travelmate.servlet;

import com.travelmate.db.DBConnection;
import com.travelmate.db.DatabaseInitializer;
import com.travelmate.util.JsonUtil;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;

/** GET /api/health — reports whether the backend can reach the database. */
@WebServlet("/api/health")
public class HealthServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("server", "up");
        boolean ok = DatabaseInitializer.isReady() || DatabaseInitializer.initialize();
        if (ok) {
            try (Connection con = DBConnection.getConnection()) {
                body.put("database", "up");
                body.put("product", con.getMetaData().getDatabaseProductName() + " "
                        + con.getMetaData().getDatabaseProductVersion());
            } catch (Exception e) {
                ok = false;
                body.put("database", "down");
                body.put("error", e.getMessage());
            }
        } else {
            body.put("database", "down");
            body.put("error", DatabaseInitializer.lastError());
        }
        body.put("url", DBConnection.describeUrl());
        JsonUtil.write(resp, ok ? 200 : 503, body);
    }
}
