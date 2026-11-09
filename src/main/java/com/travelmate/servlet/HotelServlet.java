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
 * Read-only catalog endpoints for hotels.
 *   GET /api/hotels         list with optional filters (destination, type, minRating/rating, maxPrice/price, search/q, sort, order, limit)
 *   GET /api/hotels/{id}    full hotel object
 */
@WebServlet("/api/hotels/*")
public class HotelServlet extends ApiServlet {

    private final CatalogDAO catalog = new CatalogDAO();

    @Override
    public void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String[] parts = JsonUtil.pathParts(req);
        try {
            if (parts.length == 0) {
                String destination = req.getParameter("destination");
                String type = req.getParameter("type");
                Double minRating = DestinationServlet.parseDouble(req, "minRating", "rating");
                Double maxPrice = DestinationServlet.parseDouble(req, "maxPrice", "price");
                String search = req.getParameter("search");
                if (search == null) search = req.getParameter("q");
                String sort = req.getParameter("sort");
                String order = req.getParameter("order");
                Integer limit = DestinationServlet.parseInteger(req, "limit");

                List<JsonObject> items = catalog.findHotels(destination, type, minRating, maxPrice, search, sort, order, limit);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("count", items.size());
                result.put("items", items);
                JsonUtil.ok(resp, result);
                return;
            }
            if (parts.length == 1) {
                JsonObject hotel = catalog.findHotelById(parts[0]);
                if (hotel == null) {
                    JsonUtil.error(resp, HttpServletResponse.SC_NOT_FOUND, "Hotel not found: " + parts[0]);
                } else {
                    JsonUtil.ok(resp, hotel);
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
}
