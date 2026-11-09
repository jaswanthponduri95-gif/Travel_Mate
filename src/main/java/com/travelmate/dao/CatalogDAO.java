package com.travelmate.dao;

import com.google.gson.JsonObject;
import com.travelmate.db.DBConnection;
import com.travelmate.util.JsonUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Access to static catalog data: destinations, hotels, restaurants, and transport options.
 * Provides read-only querying with filtering, text search, whitelisted sorting, and limit clamping.
 * Returns complete parsed JSON objects from the details column.
 */
public class CatalogDAO {

    private static final Map<String, String> DESTINATION_SORT_COLUMNS = Map.of(
            "name", "name",
            "state", "state",
            "region", "region",
            "category", "category",
            "suggesteddays", "suggested_days",
            "suggested_days", "suggested_days"
    );

    private static final Map<String, String> HOTEL_SORT_COLUMNS = Map.of(
            "name", "name",
            "destination", "destination",
            "type", "type",
            "rating", "rating",
            "price", "price_per_night",
            "pricepernight", "price_per_night",
            "price_per_night", "price_per_night"
    );

    private static final Map<String, String> RESTAURANT_SORT_COLUMNS = Map.of(
            "name", "name",
            "destination", "destination",
            "mealtype", "meal_type",
            "meal_type", "meal_type",
            "dietary", "dietary",
            "rating", "rating",
            "price", "price_per_person",
            "priceperperson", "price_per_person",
            "price_per_person", "price_per_person"
    );

    private static final Map<String, String> TRANSPORT_SORT_COLUMNS = Map.of(
            "price", "price",
            "mode", "mode",
            "origin", "origin",
            "destination", "destination",
            "duration", "duration_min",
            "durationmin", "duration_min",
            "duration_min", "duration_min"
    );

    // --- Search & Filter Helpers ---

    public static String sanitizeSearch(String input) {
        if (input == null) return null;
        // Strip SQL wildcards % and _ to prevent wildcard injection
        String clean = input.replace("%", "").replace("_", "").trim();
        return clean.isEmpty() ? null : clean;
    }

    public static int clampLimit(Integer limit, int defaultLimit) {
        if (limit == null) return defaultLimit;
        if (limit < 1) return 1;
        if (limit > 200) return 200;
        return limit;
    }

    private static boolean isFilterActive(String value) {
        return value != null && !value.isBlank() && !value.equalsIgnoreCase("all");
    }

    // --- Destinations ---

    public List<JsonObject> findDestinations(String state, String region, String category,
                                             String search, String sort, String order, Integer limit) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT details FROM destinations WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (isFilterActive(state)) {
            sql.append(" AND LOWER(state) = LOWER(?)");
            params.add(state.trim());
        }
        if (isFilterActive(region)) {
            sql.append(" AND LOWER(region) = LOWER(?)");
            params.add(region.trim());
        }
        if (isFilterActive(category)) {
            sql.append(" AND LOWER(category) = LOWER(?)");
            params.add(category.trim());
        }
        String cleanSearch = sanitizeSearch(search);
        if (cleanSearch != null) {
            sql.append(" AND (LOWER(name) LIKE ? OR LOWER(details) LIKE ?)");
            String pattern = "%" + cleanSearch.toLowerCase(Locale.ROOT) + "%";
            params.add(pattern);
            params.add(pattern);
        }

        String sortKey = sort == null ? "" : sort.trim().toLowerCase(Locale.ROOT);
        String sortCol = DESTINATION_SORT_COLUMNS.getOrDefault(sortKey, "name");
        String dir = "desc".equalsIgnoreCase(order) ? "DESC" : "ASC";
        sql.append(" ORDER BY ").append(sortCol).append(" ").append(dir).append(", id ASC");

        sql.append(" LIMIT ?");
        params.add(clampLimit(limit, 50));

        return executeQuery(sql.toString(), params);
    }

    public JsonObject findDestinationById(String id) throws SQLException {
        return findById("destinations", id);
    }

    // --- Hotels ---

    public List<JsonObject> findHotels(String destination, String type, Double minRating, Double maxPrice,
                                       String search, String sort, String order, Integer limit) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT details FROM hotels WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (isFilterActive(destination)) {
            sql.append(" AND LOWER(destination) = LOWER(?)");
            params.add(destination.trim());
        }
        if (isFilterActive(type)) {
            sql.append(" AND LOWER(type) LIKE LOWER(?)");
            params.add("%" + sanitizeSearch(type).toLowerCase(Locale.ROOT) + "%");
        }
        if (minRating != null) {
            sql.append(" AND rating >= ?");
            params.add(minRating);
        }
        if (maxPrice != null) {
            sql.append(" AND price_per_night <= ?");
            params.add(maxPrice);
        }
        String cleanSearch = sanitizeSearch(search);
        if (cleanSearch != null) {
            sql.append(" AND (LOWER(name) LIKE ? OR LOWER(details) LIKE ?)");
            String pattern = "%" + cleanSearch.toLowerCase(Locale.ROOT) + "%";
            params.add(pattern);
            params.add(pattern);
        }

        String sortKey = sort == null ? "" : sort.trim().toLowerCase(Locale.ROOT);
        String sortCol = HOTEL_SORT_COLUMNS.getOrDefault(sortKey, "rating");
        String dir = sortKey.isEmpty() ? "DESC" : ("desc".equalsIgnoreCase(order) ? "DESC" : "ASC");
        sql.append(" ORDER BY ").append(sortCol).append(" ").append(dir).append(", id ASC");

        sql.append(" LIMIT ?");
        params.add(clampLimit(limit, 50));

        return executeQuery(sql.toString(), params);
    }

    public JsonObject findHotelById(String id) throws SQLException {
        return findById("hotels", id);
    }

    // --- Restaurants ---

    public List<JsonObject> findRestaurants(String destination, String mealType, String dietary,
                                            Double minRating, Double maxPrice, String search,
                                            String sort, String order, Integer limit) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT details FROM restaurants WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (isFilterActive(destination)) {
            sql.append(" AND LOWER(destination) = LOWER(?)");
            params.add(destination.trim());
        }
        if (isFilterActive(mealType)) {
            sql.append(" AND LOWER(meal_type) LIKE LOWER(?)");
            params.add("%" + sanitizeSearch(mealType).toLowerCase(Locale.ROOT) + "%");
        }
        if (isFilterActive(dietary)) {
            sql.append(" AND LOWER(dietary) LIKE LOWER(?)");
            params.add("%" + sanitizeSearch(dietary).toLowerCase(Locale.ROOT) + "%");
        }
        if (minRating != null) {
            sql.append(" AND rating >= ?");
            params.add(minRating);
        }
        if (maxPrice != null) {
            sql.append(" AND price_per_person <= ?");
            params.add(maxPrice);
        }
        String cleanSearch = sanitizeSearch(search);
        if (cleanSearch != null) {
            sql.append(" AND (LOWER(name) LIKE ? OR LOWER(details) LIKE ?)");
            String pattern = "%" + cleanSearch.toLowerCase(Locale.ROOT) + "%";
            params.add(pattern);
            params.add(pattern);
        }

        String sortKey = sort == null ? "" : sort.trim().toLowerCase(Locale.ROOT);
        String sortCol = RESTAURANT_SORT_COLUMNS.getOrDefault(sortKey, "rating");
        String dir = sortKey.isEmpty() ? "DESC" : ("desc".equalsIgnoreCase(order) ? "DESC" : "ASC");
        sql.append(" ORDER BY ").append(sortCol).append(" ").append(dir).append(", id ASC");

        sql.append(" LIMIT ?");
        params.add(clampLimit(limit, 50));

        return executeQuery(sql.toString(), params);
    }

    public JsonObject findRestaurantById(String id) throws SQLException {
        return findById("restaurants", id);
    }

    // --- Transport Options ---

    public List<JsonObject> findTransportOptions(String mode, String origin, String destination,
                                                 String search, String sort, String order, Integer limit) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT details FROM transport_options WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (isFilterActive(mode)) {
            sql.append(" AND LOWER(mode) = LOWER(?)");
            params.add(mode.trim());
        }
        if (isFilterActive(origin)) {
            sql.append(" AND LOWER(origin) LIKE LOWER(?)");
            params.add("%" + sanitizeSearch(origin).toLowerCase(Locale.ROOT) + "%");
        }
        if (isFilterActive(destination)) {
            sql.append(" AND LOWER(destination) LIKE LOWER(?)");
            params.add("%" + sanitizeSearch(destination).toLowerCase(Locale.ROOT) + "%");
        }
        String cleanSearch = sanitizeSearch(search);
        if (cleanSearch != null) {
            sql.append(" AND (LOWER(provider) LIKE ? OR LOWER(origin) LIKE ? OR LOWER(destination) LIKE ? OR LOWER(details) LIKE ?)");
            String pattern = "%" + cleanSearch.toLowerCase(Locale.ROOT) + "%";
            params.add(pattern);
            params.add(pattern);
            params.add(pattern);
            params.add(pattern);
        }

        String sortKey = sort == null ? "" : sort.trim().toLowerCase(Locale.ROOT);
        String sortCol = TRANSPORT_SORT_COLUMNS.getOrDefault(sortKey, "price");
        String dir = "desc".equalsIgnoreCase(order) ? "DESC" : "ASC";
        sql.append(" ORDER BY ").append(sortCol).append(" ").append(dir).append(", id ASC");

        sql.append(" LIMIT ?");
        params.add(clampLimit(limit, 50));

        return executeQuery(sql.toString(), params);
    }

    public JsonObject findTransportOptionById(String id) throws SQLException {
        return findById("transport_options", id);
    }

    // --- Common Execution Helpers ---

    private JsonObject findById(String table, String id) throws SQLException {
        if (id == null || id.isBlank()) return null;
        // Strictly whitelisted tables
        if (!table.equals("destinations") && !table.equals("hotels")
                && !table.equals("restaurants") && !table.equals("transport_options")) {
            throw new IllegalArgumentException("Unknown catalog table: " + table);
        }
        String sql = "SELECT details FROM " + table + " WHERE id = ?";
        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, id.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return JsonUtil.GSON.fromJson(rs.getString("details"), JsonObject.class);
            }
        }
    }

    private List<JsonObject> executeQuery(String sql, List<Object> params) throws SQLException {
        List<JsonObject> list = new ArrayList<>();
        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JsonObject obj = JsonUtil.GSON.fromJson(rs.getString("details"), JsonObject.class);
                    list.add(obj);
                }
            }
        }
        return list;
    }

    public int count(String table) throws SQLException {
        if (!table.equals("destinations") && !table.equals("hotels")
                && !table.equals("restaurants") && !table.equals("transport_options")) {
            throw new IllegalArgumentException("Unknown catalog table: " + table);
        }
        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT COUNT(*) FROM " + table);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    public void deleteAll() throws SQLException {
        try (Connection con = DBConnection.getConnection()) {
            boolean ac = con.getAutoCommit();
            con.setAutoCommit(false);
            try (java.sql.Statement st = con.createStatement()) {
                st.executeUpdate("DELETE FROM destinations");
                st.executeUpdate("DELETE FROM hotels");
                st.executeUpdate("DELETE FROM restaurants");
                st.executeUpdate("DELETE FROM transport_options");
                con.commit();
            } catch (Exception e) {
                con.rollback();
                throw e;
            } finally {
                con.setAutoCommit(ac);
            }
        }
    }
}

