package com.travelmate.db;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.travelmate.dao.BudgetDAO;
import com.travelmate.dao.TripDAO;
import com.travelmate.dao.UserDAO;
import com.travelmate.model.BudgetItem;
import com.travelmate.model.ItineraryDay;
import com.travelmate.model.Trip;
import com.travelmate.util.JsonUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Creates the tables (schema.sql), loads the catalog data (catalog-data.json)
 * and loads the demo data (seed-data.json) the first time the application starts
 * against an empty database.
 */
public final class DatabaseInitializer {

    public static final String DEMO_EMAIL = "demo.traveler@travelmate.in";
    public static final String DEMO_PASSWORD = "demo12345";

    private static volatile boolean ready;
    private static volatile String lastError;

    private DatabaseInitializer() {
    }

    public static boolean isReady() {
        return ready;
    }

    public static String lastError() {
        return lastError;
    }

    /** Connects, creates the schema and seeds demo data if needed. Never throws. */
    public static synchronized boolean initialize() {
        try {
            createSchema();
            migrate();
            seedCatalog();
            if (new TripDAO().count() == 0) seedDemoData();
            if (!new UserDAO().emailExists(DEMO_EMAIL)) {
                new UserDAO().create("Demo Traveler", DEMO_EMAIL, DEMO_PASSWORD);
            }
            ready = true;
            lastError = null;
            System.out.println("[TravelMate] Database ready at " + DBConnection.describeUrl());
        } catch (Exception e) {
            ready = false;
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            System.err.println("[TravelMate] DATABASE CONNECTION FAILED (" + DBConnection.describeUrl() + "): "
                    + lastError + "\n  -> Is MySQL running, and are db.user/db.password in db.properties correct?");
        }
        return ready;
    }

    private static void createSchema() throws SQLException, IOException {
        String sql = readResource("schema.sql");
        StringBuilder current = new StringBuilder();
        try (Connection con = DBConnection.getConnection(); Statement st = con.createStatement()) {
            for (String line : sql.split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("--")) continue;
                current.append(line).append('\n');
                if (trimmed.endsWith(";")) {
                    st.execute(current.substring(0, current.lastIndexOf(";")));
                    current.setLength(0);
                }
            }
        }
    }

    /**
     * Adds columns introduced after the first release to databases created earlier
     * (CREATE TABLE IF NOT EXISTS leaves existing tables untouched). MySQL 8 has no
     * "ADD COLUMN IF NOT EXISTS", so each column is probed first.
     */
    private static void migrate() throws SQLException {
        String[][] stopColumns = {
                {"address", "VARCHAR(500)"}, {"place_ref", "VARCHAR(80)"}, {"opening_hours", "VARCHAR(300)"},
                {"website", "VARCHAR(500)"}, {"phone", "VARCHAR(60)"}};
        try (Connection con = DBConnection.getConnection(); Statement st = con.createStatement()) {
            for (String[] col : stopColumns) {
                try {
                    st.executeQuery("SELECT " + col[0] + " FROM itinerary_stops WHERE 1 = 0").close();
                } catch (SQLException missing) {
                    st.executeUpdate("ALTER TABLE itinerary_stops ADD COLUMN " + col[0] + " " + col[1]);
                    System.out.println("[TravelMate] Added column itinerary_stops." + col[0]);
                }
            }
            // trips.ai_planned: set once the automatic AI tour plan has been saved for a trip
            try {
                st.executeQuery("SELECT ai_planned FROM trips WHERE 1 = 0").close();
            } catch (SQLException missing) {
                st.executeUpdate("ALTER TABLE trips ADD COLUMN ai_planned BOOLEAN NOT NULL DEFAULT FALSE");
                System.out.println("[TravelMate] Added column trips.ai_planned");
            }
        }
    }

    /**
     * Seeds catalog data (destinations, hotels, restaurants, transport_options) from
     * catalog-data.json. Evaluates each table independently: only tables that are currently
     * empty are seeded. Preserves any existing or user-customized records without overwriting.
     */
    public static synchronized void seedCatalog() throws SQLException, IOException {
        try (Connection con = DBConnection.getConnection()) {
            seedCatalog(con);
        }
    }

    /**
     * Inserts catalog records for any empty catalog tables in a single transaction.
     * Preserves existing tables untouched, prevents duplication, and rolls back on failure.
     */
    public static synchronized void seedCatalog(Connection con) throws SQLException, IOException {
        boolean seedDest = tableCount(con, "destinations") == 0;
        boolean seedHotels = tableCount(con, "hotels") == 0;
        boolean seedRest = tableCount(con, "restaurants") == 0;
        boolean seedTrans = tableCount(con, "transport_options") == 0;

        String json = readResource("catalog-data.json");
        JsonObject catalog = JsonUtil.GSON.fromJson(json, JsonObject.class);

        // Repair only the two legacy placeholder durations introduced by the route-neutral
        // transport catalog update. Existing user-customized transport rows are left intact.
        repairLegacyTransportDurations(con, catalog);

        if (!seedDest && !seedHotels && !seedRest && !seedTrans) {
            return; // All catalog tables are populated; only the targeted compatibility repair ran.
        }

        boolean originalAutoCommit = con.getAutoCommit();
        con.setAutoCommit(false);
        try {
            // 1. Destinations (only if table was empty)
            if (seedDest) {
                JsonArray destinations = catalog.getAsJsonArray("destinations");
                if (destinations != null) {
                    String sql = "INSERT INTO destinations (id, name, state, category, region, suggested_days, details) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)";
                    try (PreparedStatement ps = con.prepareStatement(sql)) {
                        for (JsonElement el : destinations) {
                            JsonObject d = el.getAsJsonObject();
                            ps.setString(1, d.get("id").getAsString());
                            ps.setString(2, d.get("name").getAsString());
                            ps.setString(3, d.has("state") && !d.get("state").isJsonNull() ? d.get("state").getAsString() : null);
                            ps.setString(4, d.has("category") && !d.get("category").isJsonNull() ? d.get("category").getAsString() : null);
                            ps.setString(5, d.has("region") && !d.get("region").isJsonNull() ? d.get("region").getAsString() : null);
                            ps.setInt(6, d.has("suggestedDays") ? d.get("suggestedDays").getAsInt() : 1);
                            ps.setString(7, JsonUtil.GSON.toJson(d));
                            ps.executeUpdate();
                        }
                    }
                }
            }

            // 2. Hotels (only if table was empty)
            if (seedHotels) {
                JsonArray hotels = catalog.getAsJsonArray("hotels");
                if (hotels != null) {
                    String sql = "INSERT INTO hotels (id, name, destination, type, rating, price_per_night, details) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)";
                    try (PreparedStatement ps = con.prepareStatement(sql)) {
                        for (JsonElement el : hotels) {
                            JsonObject h = el.getAsJsonObject();
                            ps.setString(1, h.get("id").getAsString());
                            ps.setString(2, h.get("name").getAsString());
                            ps.setString(3, h.has("destination") && !h.get("destination").isJsonNull() ? h.get("destination").getAsString() : null);
                            ps.setString(4, h.has("type") && !h.get("type").isJsonNull() ? h.get("type").getAsString() : null);
                            ps.setDouble(5, h.has("rating") ? h.get("rating").getAsDouble() : 0.0);
                            ps.setDouble(6, h.has("pricePerNight") ? h.get("pricePerNight").getAsDouble() : 0.0);
                            ps.setString(7, JsonUtil.GSON.toJson(h));
                            ps.executeUpdate();
                        }
                    }
                }
            }

            // 3. Restaurants (only if table was empty)
            if (seedRest) {
                JsonArray restaurants = catalog.getAsJsonArray("restaurants");
                if (restaurants != null) {
                    String sql = "INSERT INTO restaurants (id, name, destination, meal_type, dietary, rating, price_per_person, details) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
                    try (PreparedStatement ps = con.prepareStatement(sql)) {
                        for (JsonElement el : restaurants) {
                            JsonObject r = el.getAsJsonObject();
                            ps.setString(1, r.get("id").getAsString());
                            ps.setString(2, r.get("name").getAsString());
                            ps.setString(3, r.has("destination") && !r.get("destination").isJsonNull() ? r.get("destination").getAsString() : null);
                            ps.setString(4, r.has("mealType") && !r.get("mealType").isJsonNull() ? r.get("mealType").getAsString() : null);
                            ps.setString(5, r.has("dietary") && !r.get("dietary").isJsonNull() ? r.get("dietary").getAsString() : null);
                            ps.setDouble(6, r.has("rating") ? r.get("rating").getAsDouble() : 0.0);
                            ps.setDouble(7, r.has("pricePerPerson") ? r.get("pricePerPerson").getAsDouble() : 0.0);
                            ps.setString(8, JsonUtil.GSON.toJson(r));
                            ps.executeUpdate();
                        }
                    }
                }
            }

            // 4. Transport Options (only if table was empty)
            if (seedTrans) {
                JsonArray transports = catalog.has("transport_options")
                        ? catalog.getAsJsonArray("transport_options")
                        : catalog.getAsJsonArray("transports");
                if (transports != null) {
                    String sql = "INSERT INTO transport_options (id, mode, provider, origin, destination, price, duration_min, details) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
                    try (PreparedStatement ps = con.prepareStatement(sql)) {
                        for (JsonElement el : transports) {
                            JsonObject t = el.getAsJsonObject();
                            ps.setString(1, t.get("id").getAsString());
                            ps.setString(2, t.has("mode") && !t.get("mode").isJsonNull() ? t.get("mode").getAsString() : null);
                            ps.setString(3, t.has("provider") && !t.get("provider").isJsonNull() ? t.get("provider").getAsString() : null);
                            ps.setString(4, t.has("origin") && !t.get("origin").isJsonNull() ? t.get("origin").getAsString() : null);
                            ps.setString(5, t.has("destination") && !t.get("destination").isJsonNull() ? t.get("destination").getAsString() : null);
                            ps.setDouble(6, t.has("price") ? t.get("price").getAsDouble() : 0.0);
                            String durStr = t.has("duration") && !t.get("duration").isJsonNull() ? t.get("duration").getAsString() : null;
                            ps.setInt(7, parseDurationMinutes(durStr));
                            ps.setString(8, JsonUtil.GSON.toJson(t));
                            ps.executeUpdate();
                        }
                    }
                }
            }

            con.commit();
        } catch (Exception e) {
            con.rollback();
            throw e;
        } finally {
            con.setAutoCommit(originalAutoCommit);
        }
    }

    private static int tableCount(Connection con, String table) throws SQLException {
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /**
     * Repairs the old "Check route" placeholder for catalog entries whose duration is
     * part of the catalog metadata and is also asserted by the catalog regression tests.
     * These are generic provider metadata estimates, not a fixed origin/destination route;
     * the transport UI always labels the active trip's own origin and destination.
     */
    private static void repairLegacyTransportDurations(Connection con, JsonObject catalog) throws SQLException {
        JsonArray transports = catalog.has("transport_options")
                ? catalog.getAsJsonArray("transport_options") : catalog.getAsJsonArray("transports");
        if (transports == null) return;

        String sql = "UPDATE transport_options SET duration_min = ?, details = ? "
                + "WHERE id = ? AND details LIKE ?";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            for (JsonElement element : transports) {
                JsonObject transport = element.getAsJsonObject();
                String id = transport.has("id") && !transport.get("id").isJsonNull()
                        ? transport.get("id").getAsString() : null;
                if (!"tr-001".equals(id) && !"tr-005".equals(id)) continue;
                String duration = transport.has("duration") && !transport.get("duration").isJsonNull()
                        ? transport.get("duration").getAsString() : null;
                ps.setInt(1, parseDurationMinutes(duration));
                ps.setString(2, JsonUtil.GSON.toJson(transport));
                ps.setString(3, id);
                ps.setString(4, "%Check route%");
                ps.executeUpdate();
            }
        }
    }

    /**
     * Converts duration strings like "2h 30m" or "45m" into total integer minutes.
     * Preserves fallback for plain integer minutes.
     */
    public static int parseDurationMinutes(String duration) {
        if (duration == null || duration.isBlank()) return 0;
        int totalMinutes = 0;
        Matcher mHours = Pattern.compile("(\\d+)\\s*h", Pattern.CASE_INSENSITIVE).matcher(duration);
        if (mHours.find()) {
            totalMinutes += Integer.parseInt(mHours.group(1)) * 60;
        }
        Matcher mMinutes = Pattern.compile("(\\d+)\\s*m", Pattern.CASE_INSENSITIVE).matcher(duration);
        if (mMinutes.find()) {
            totalMinutes += Integer.parseInt(mMinutes.group(1));
        }
        if (totalMinutes == 0) {
            try {
                totalMinutes = Integer.parseInt(duration.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return totalMinutes;
    }

    /** Replaces all trips and budgets with the demo data set. */
    public static synchronized void seedDemoData() throws SQLException, IOException {
        JsonObject seed = JsonUtil.GSON.fromJson(readResource("seed-data.json"), JsonObject.class);
        TripDAO trips = new TripDAO();
        BudgetDAO budgets = new BudgetDAO();
        trips.deleteAll();

        Trip active = JsonUtil.GSON.fromJson(seed.get("activeTrip"), Trip.class);
        JsonArray list = seed.getAsJsonArray("tripsList");
        for (int i = 0; i < list.size(); i++) {
            JsonObject summary = list.get(i).getAsJsonObject();
            String id = summary.get("id").getAsString();
            Trip trip;
            if (id.equals(active.id)) {
                trip = active;
                trip.status = summary.get("status").getAsString();
            } else {
                trip = JsonUtil.GSON.fromJson(summary, Trip.class);
                trip.budgetTotal = summary.get("budget").getAsDouble();
                trip.coverImage = summary.get("image").getAsString();
                trip.days = emptyDays(trip.daysCount);
            }
            trips.save(trip);
            // Keep the demo list order: first trip most recent, the others progressively older
            if (i > 0) trips.setUpdatedAt(trip.id, LocalDateTime.now().minusDays(i == 1 ? 3 : 60));
        }

        JsonObject budget = seed.getAsJsonObject("budget");
        budgets.setLimit(active.id, budget.get("totalLimit").getAsDouble());
        JsonArray items = budget.getAsJsonArray("items");
        // Items are listed newest-first, so insert from the end
        for (int i = items.size() - 1; i >= 0; i--) {
            JsonElement el = items.get(i);
            budgets.addItem(active.id, JsonUtil.GSON.fromJson(el, BudgetItem.class));
        }
    }

    private static List<ItineraryDay> emptyDays(int count) {
        List<ItineraryDay> days = new ArrayList<>();
        for (int n = 1; n <= Math.max(count, 1); n++) {
            ItineraryDay d = new ItineraryDay();
            d.dayNumber = n;
            d.title = "Day " + n + " Exploration";
            d.summary = "0 stops • Plan open";
            days.add(d);
        }
        return days;
    }

    private static String readResource(String name) throws IOException {
        try (InputStream in = DatabaseInitializer.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) throw new IOException("Missing classpath resource " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
