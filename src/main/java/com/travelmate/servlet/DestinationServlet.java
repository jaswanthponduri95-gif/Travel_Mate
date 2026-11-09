package com.travelmate.servlet;

import com.google.gson.JsonObject;
import com.travelmate.dao.CatalogDAO;
import com.travelmate.util.JsonUtil;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only catalog endpoints for destinations.
 *   GET /api/destinations         list with optional filters (state, region, category, search/q, sort, order, limit)
 *   GET /api/destinations/{id}    full destination object
 */
@WebServlet("/api/destinations/*")
public class DestinationServlet extends ApiServlet {

    private final CatalogDAO catalog = new CatalogDAO();

    @Override
    public void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String[] parts = JsonUtil.pathParts(req);
        try {
            if (parts.length == 0) {
                String state = req.getParameter("state");
                String region = req.getParameter("region");
                String category = req.getParameter("category");
                String search = req.getParameter("search");
                if (search == null) search = req.getParameter("q");
                String sort = req.getParameter("sort");
                String order = req.getParameter("order");
                Integer limit = parseInteger(req, "limit");

                List<JsonObject> items = catalog.findDestinations(state, region, category, search, sort, order, limit);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("count", items.size());
                result.put("items", items);
                JsonUtil.ok(resp, result);
                return;
            }
            if (parts.length == 1) {
                JsonObject dest = catalog.findDestinationById(parts[0]);
                if (dest == null) {
                    JsonUtil.error(resp, HttpServletResponse.SC_NOT_FOUND, "Destination not found: " + parts[0]);
                } else {
                    JsonUtil.ok(resp, dest);
                }
                return;
            }
            JsonUtil.error(resp, HttpServletResponse.SC_NOT_FOUND, "Unknown endpoint");
        } catch (IllegalArgumentException e) {
            JsonUtil.error(resp, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        } catch (SQLException e) {
            throw db(e);
        }
    }

    @Override
    public void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        JsonUtil.error(resp, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "POST not supported on read-only catalog endpoint");
    }

    @Override
    public void doPut(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        JsonUtil.error(resp, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "PUT not supported on read-only catalog endpoint");
    }

    @Override
    public void doDelete(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        JsonUtil.error(resp, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "DELETE not supported on read-only catalog endpoint");
    }

    static Integer parseInteger(HttpServletRequest req, String name) {
        String val = req.getParameter(name);
        if (val == null || val.isBlank()) return null;
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid integer parameter '" + name + "': " + val);
        }
    }

    static Double parseDouble(HttpServletRequest req, String... names) {
        for (String name : names) {
            String val = req.getParameter(name);
            if (val != null && !val.isBlank()) {
                try {
                    return Double.parseDouble(val.trim());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid numeric parameter '" + name + "': " + val);
                }
            }
        }
        return null;
    }
}
