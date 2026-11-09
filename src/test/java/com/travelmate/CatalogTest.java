package com.travelmate;

import com.google.gson.JsonObject;
import com.travelmate.dao.CatalogDAO;
import com.travelmate.db.DBConnection;
import com.travelmate.db.DatabaseInitializer;
import com.travelmate.servlet.ApiServlet;
import com.travelmate.servlet.DestinationServlet;
import com.travelmate.servlet.HotelServlet;
import com.travelmate.servlet.RestaurantServlet;
import com.travelmate.servlet.TransportServlet;
import com.travelmate.util.JsonUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 3 automated test suite for Catalog Data Migration and Read-Only JSON APIs.
 * Runs against an isolated in-memory H2 database (MySQL compatibility mode).
 */
public class CatalogTest {

    private static final String TEST_DB_URL =
            "jdbc:h2:mem:tm_catalog_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    private final CatalogDAO catalogDAO = new CatalogDAO();

    @BeforeAll
    static void initDatabase() {
        DBConnection.configure(TEST_DB_URL, "sa", "");
        assertTrue(DatabaseInitializer.initialize(), "Database initialization should succeed");
    }

    @BeforeEach
    void ensureCatalogSeeded() throws SQLException, IOException {
        // Ensure catalog is seeded before each test
        DatabaseInitializer.seedCatalog();
    }

    @Test
    @DisplayName("Verify all four catalog tables exist and contain exact record counts")
    void testSchemaAndCatalogRecordCounts() throws SQLException {
        try (Connection con = DBConnection.getConnection()) {
            // Verify all 4 tables exist
            String[] tables = {"destinations", "hotels", "restaurants", "transport_options"};
            for (String table : tables) {
                try (Statement st = con.createStatement();
                     ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
                    assertTrue(rs.next(), "Table " + table + " should be queryable");
                }
            }
        }

        assertEquals(6, catalogDAO.count("destinations"), "Destinations count must be exactly 6");
        assertEquals(4, catalogDAO.count("hotels"), "Hotels count must be exactly 4");
        assertEquals(4, catalogDAO.count("restaurants"), "Restaurants count must be exactly 4");
        assertEquals(6, catalogDAO.count("transport_options"), "Transport options count must be exactly 6");

        int total = catalogDAO.count("destinations") + catalogDAO.count("hotels")
                + catalogDAO.count("restaurants") + catalogDAO.count("transport_options");
        assertEquals(20, total, "Total catalog records must be exactly 20");
    }

    @Test
    @DisplayName("Verify catalog seeding is idempotent and does not duplicate on re-initialization")
    void testSeedingIdempotency() throws SQLException, IOException {
        // Re-run initialization 3 times consecutively
        DatabaseInitializer.initialize();
        DatabaseInitializer.initialize();
        DatabaseInitializer.seedCatalog();

        assertEquals(6, catalogDAO.count("destinations"), "Destinations count must remain 6");
        assertEquals(4, catalogDAO.count("hotels"), "Hotels count must remain 4");
        assertEquals(4, catalogDAO.count("restaurants"), "Restaurants count must remain 4");
        assertEquals(6, catalogDAO.count("transport_options"), "Transport options count must remain 6");
    }

    @Test
    @DisplayName("Verify complete original JSON details are preserved in the details column")
    void testOriginalJsonDetailsPreserved() throws SQLException {
        // Destination check: Mysuru
        JsonObject mysuru = catalogDAO.findDestinationById("mysuru");
        assertNotNull(mysuru, "Mysuru destination must exist");
        assertEquals("Mysuru", mysuru.get("name").getAsString());
        assertEquals("Karnataka", mysuru.get("state").getAsString());
        assertEquals("Heritage", mysuru.get("category").getAsString());
        assertEquals("South", mysuru.get("region").getAsString());
        assertEquals(3, mysuru.get("suggestedDays").getAsInt());
        assertTrue(mysuru.has("coordinates"), "Coordinates array must be preserved");
        assertEquals(2, mysuru.getAsJsonArray("coordinates").size());
        assertTrue(mysuru.has("highlights"), "Highlights must be preserved");
        assertTrue(mysuru.has("cuisine"), "Cuisine list must be preserved");
        assertTrue(mysuru.has("tagline"), "Tagline must be preserved");
        assertTrue(mysuru.has("description"), "Description must be preserved");

        // Hotel check: Grand Mercure Mysuru
        JsonObject hotel = catalogDAO.findHotelById("ht-001");
        assertNotNull(hotel, "Hotel ht-001 must exist");
        assertEquals("Grand Mercure Mysuru — Accor", hotel.get("name").getAsString());
        assertEquals("Mysuru", hotel.get("destination").getAsString());
        assertEquals(4.6, hotel.get("rating").getAsDouble(), 0.01);
        assertEquals(4200, hotel.get("pricePerNight").getAsInt());
        assertTrue(hotel.has("amenities"), "Amenities must be preserved");
        assertTrue(hotel.has("reviewsCount"), "reviewsCount must be preserved");
        assertTrue(hotel.has("badge"), "badge must be preserved");

        // Restaurant check: Mylari
        JsonObject rest = catalogDAO.findRestaurantById("rest-001");
        assertNotNull(rest, "Restaurant rest-001 must exist");
        assertEquals("Hotel Original Vinayaka Mylari", rest.get("name").getAsString());
        assertEquals(4.8, rest.get("rating").getAsDouble(), 0.01);
        assertEquals(120, rest.get("pricePerPerson").getAsInt());
        assertTrue(rest.has("specialty"), "specialty must be preserved");
        assertTrue(rest.has("dietary"), "dietary must be preserved");

        // Transport check: Vande Bharat Express
        JsonObject transport = catalogDAO.findTransportOptionById("tr-001");
        assertNotNull(transport, "Transport tr-001 must exist");
        assertEquals("train", transport.get("mode").getAsString());
        assertEquals("1h 45m", transport.get("duration").getAsString());
        assertEquals(540, transport.get("price").getAsInt());
        assertTrue(transport.has("features"), "features must be preserved");
        assertTrue(transport.has("availability"), "availability must be preserved");
    }

    @Test
    @DisplayName("Verify duration conversion to integer minutes")
    void testDurationConversion() throws SQLException {
        assertEquals(105, DatabaseInitializer.parseDurationMinutes("1h 45m"));
        assertEquals(150, DatabaseInitializer.parseDurationMinutes("2h 30m"));
        assertEquals(165, DatabaseInitializer.parseDurationMinutes("2h 45m"));
        assertEquals(225, DatabaseInitializer.parseDurationMinutes("3h 45m"));
        assertEquals(50, DatabaseInitializer.parseDurationMinutes("50m"));
        assertEquals(180, DatabaseInitializer.parseDurationMinutes("3h 00m"));
        assertEquals(45, DatabaseInitializer.parseDurationMinutes("45m"));
        assertEquals(120, DatabaseInitializer.parseDurationMinutes("2h"));
        assertEquals(0, DatabaseInitializer.parseDurationMinutes(null));
        assertEquals(0, DatabaseInitializer.parseDurationMinutes("   "));

        // Query database duration_min column directly
        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT duration_min FROM transport_options WHERE id = ?")) {
            ps.setString(1, "tr-001");
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(105, rs.getInt(1), "tr-001 duration_min must be 105");
            }

            ps.setString(1, "tr-005");
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(50, rs.getInt(1), "tr-005 duration_min must be 50");
            }
        }
    }

    @Test
    @DisplayName("Verify DAO findById and unknown ID handling")
    void testDaoFindByIdAndUnknown() throws SQLException {
        assertNotNull(catalogDAO.findDestinationById("mysuru"));
        assertNotNull(catalogDAO.findDestinationById("jaipur"));
        assertNull(catalogDAO.findDestinationById("non-existent-destination"));
        assertNull(catalogDAO.findDestinationById(null));
        assertNull(catalogDAO.findDestinationById("   "));

        assertNotNull(catalogDAO.findHotelById("ht-001"));
        assertNull(catalogDAO.findHotelById("ht-999"));

        assertNotNull(catalogDAO.findRestaurantById("rest-001"));
        assertNull(catalogDAO.findRestaurantById("rest-999"));

        assertNotNull(catalogDAO.findTransportOptionById("tr-001"));
        assertNull(catalogDAO.findTransportOptionById("tr-999"));
    }

    @Test
    @DisplayName("Verify DAO filtering, case-insensitivity, and blank/all ignoring")
    void testDaoFilteringAndCaseInsensitivity() throws SQLException {
        // Destinations by state (case-insensitive)
        List<JsonObject> karnataka = catalogDAO.findDestinations("Karnataka", null, null, null, null, null, null);
        assertEquals(1, karnataka.size());
        assertEquals("mysuru", karnataka.get(0).get("id").getAsString());

        List<JsonObject> karnatakaLower = catalogDAO.findDestinations("karnataka", null, null, null, null, null, null);
        assertEquals(1, karnatakaLower.size());

        // Destinations by region
        List<JsonObject> south = catalogDAO.findDestinations(null, "South", null, null, null, null, null);
        assertEquals(4, south.size(), "South region has Mysuru, Ooty, Munnar, Hyderabad");

        // Destinations by category
        List<JsonObject> heritage = catalogDAO.findDestinations(null, null, "Heritage", null, null, null, null);
        assertEquals(3, heritage.size(), "Heritage category has Mysuru, Jaipur, Hyderabad");

        // Blank and 'all' filter ignored
        List<JsonObject> allState = catalogDAO.findDestinations("all", "ALL", "   ", null, null, null, null);
        assertEquals(6, allState.size(), "Ignoring blank/'all' should return all 6 destinations");

        // Hotels by destination and min rating
        List<JsonObject> mysHotels = catalogDAO.findHotels("Mysuru", null, null, null, null, null, null, null);
        assertEquals(4, mysHotels.size());

        List<JsonObject> luxuryHotels = catalogDAO.findHotels(null, null, 4.6, null, null, null, null, null);
        assertEquals(2, luxuryHotels.size(), "Hotels with rating >= 4.6 (ht-001: 4.6, ht-002: 4.7)");

        // Hotels by max price
        List<JsonObject> budgetHotels = catalogDAO.findHotels(null, null, null, 2000.0, null, null, null, null);
        assertEquals(2, budgetHotels.size(), "Hotels <= 2000 (ht-003: 1950, ht-004: 1450)");

        // Transport by mode
        List<JsonObject> trains = catalogDAO.findTransportOptions("train", null, null, null, null, null, null);
        assertEquals(2, trains.size(), "tr-001 and tr-002 are trains");

        List<JsonObject> flights = catalogDAO.findTransportOptions("flight", null, null, null, null, null, null);
        assertEquals(1, flights.size(), "tr-005 is a flight");
    }

    @Test
    @DisplayName("Verify DAO text search, wildcard sanitization, and SQL injection safety")
    void testDaoSearchAndSqlInjectionSafety() throws SQLException {
        // Search destination by name/tagline/highlights
        List<JsonObject> palaceSearch = catalogDAO.findDestinations(null, null, null, "palace", null, null, null);
        assertTrue(palaceSearch.stream().anyMatch(d -> d.get("id").getAsString().equals("mysuru")));

        // Search case-insensitivity
        List<JsonObject> teaSearch = catalogDAO.findDestinations(null, null, null, "TEA", null, null, null);
        assertTrue(teaSearch.stream().anyMatch(d -> d.get("id").getAsString().equals("munnar") || d.get("id").getAsString().equals("ooty")));

        // Search hotel
        List<JsonObject> haveli = catalogDAO.findHotels(null, null, null, null, "haveli", null, null, null);
        assertEquals(1, haveli.size());
        assertEquals("ht-003", haveli.get(0).get("id").getAsString());

        // Search restaurant
        List<JsonObject> dosa = catalogDAO.findRestaurants(null, null, null, null, null, "dosa", null, null, null);
        assertEquals(1, dosa.size());
        assertEquals("rest-001", dosa.get(0).get("id").getAsString());

        // Wildcard stripping (% and _)
        List<JsonObject> wildcardOnly = catalogDAO.findDestinations(null, null, null, "%%%%", null, null, null);
        assertEquals(6, wildcardOnly.size(), "Wildcard-only search stripped to empty, returns all items safely");

        // SQL injection attempts
        String[] attacks = {
                "' OR '1'='1",
                "'; DROP TABLE destinations; --",
                "admin'--",
                "\" OR \"\"=\""
        };
        for (String attack : attacks) {
            List<JsonObject> result = catalogDAO.findDestinations(null, null, null, attack, null, null, null);
            assertNotNull(result, "Attack query must return empty list without throwing");
            assertEquals(0, result.size(), "Malicious search input must match 0 records");
        }

        // Verify table was not altered or dropped
        assertEquals(6, catalogDAO.count("destinations"), "Destinations table must be intact");
    }

    @Test
    @DisplayName("Verify DAO sorting and deterministic secondary sorting")
    void testDaoSortingAndDeterministicOrder() throws SQLException {
        // Destinations sorted by name ASC
        List<JsonObject> asc = catalogDAO.findDestinations(null, null, null, null, "name", "asc", null);
        assertEquals("goa", asc.get(0).get("id").getAsString());

        // Destinations sorted by name DESC
        List<JsonObject> desc = catalogDAO.findDestinations(null, null, null, null, "name", "desc", null);
        assertEquals("ooty", desc.get(0).get("id").getAsString());

        // Transport sorted by price ASC
        List<JsonObject> trAsc = catalogDAO.findTransportOptions(null, null, null, null, "price", "asc", null);
        assertEquals(135, trAsc.get(0).get("price").getAsInt(), "Cheapest transport option is tr-002 (135)");

        // Transport sorted by price DESC
        List<JsonObject> trDesc = catalogDAO.findTransportOptions(null, null, null, null, "price", "desc", null);
        assertEquals(2450, trDesc.get(0).get("price").getAsInt(), "Most expensive transport option is tr-006 (2450)");

        // Invalid sort parameter safely defaults
        List<JsonObject> safeDefault = catalogDAO.findDestinations(null, null, null, null, "INVALID_COL; DROP TABLE", "asc", null);
        assertEquals(6, safeDefault.size());
    }

    @Test
    @DisplayName("Verify DAO limit clamping between 1 and 200")
    void testDaoLimitClamping() throws SQLException {
        assertEquals(1, CatalogDAO.clampLimit(-10, 50));
        assertEquals(1, CatalogDAO.clampLimit(0, 50));
        assertEquals(200, CatalogDAO.clampLimit(500, 50));
        assertEquals(50, CatalogDAO.clampLimit(null, 50));
        assertEquals(10, CatalogDAO.clampLimit(10, 50));

        List<JsonObject> limited = catalogDAO.findDestinations(null, null, null, null, null, null, 2);
        assertEquals(2, limited.size(), "Limit 2 must return exactly 2 items");

        List<JsonObject> clampedMin = catalogDAO.findDestinations(null, null, null, null, null, null, -5);
        assertEquals(1, clampedMin.size(), "Negative limit must clamp to 1 item");
    }

    @Test
    @DisplayName("Verify transaction rollback on simulated catalog seeding failure")
    void testTransactionRollbackOnSimulatedFailure() throws SQLException, IOException {
        // Clear all catalog records
        catalogDAO.deleteAll();
        assertEquals(0, catalogDAO.count("destinations"));
        assertEquals(0, catalogDAO.count("hotels"));
        assertEquals(0, catalogDAO.count("restaurants"));
        assertEquals(0, catalogDAO.count("transport_options"));

        // Simulate failure by executing a transaction where an error occurs midway
        try (Connection con = DBConnection.getConnection()) {
            boolean originalAutoCommit = con.getAutoCommit();
            con.setAutoCommit(false);
            try (Statement st = con.createStatement()) {
                st.executeUpdate("INSERT INTO destinations (id, name, details) VALUES ('partial-1', 'Partial Dest', '{}')");
                // Intentionally cause SQL syntax exception to simulate failure midway
                st.executeUpdate("INSERT INTO non_existent_table_for_rollback_test VALUES (1)");
                con.commit();
                fail("Should have thrown SQLException");
            } catch (SQLException expected) {
                con.rollback();
            } finally {
                con.setAutoCommit(originalAutoCommit);
            }
        }

        // Verify partial insert was rolled back completely
        assertEquals(0, catalogDAO.count("destinations"), "Partial destinations insert must be rolled back to 0");

        // Verify database can initialize/seed successfully afterward
        DatabaseInitializer.seedCatalog();
        assertEquals(6, catalogDAO.count("destinations"), "Catalog should be re-seeded successfully");
        assertEquals(4, catalogDAO.count("hotels"));
        assertEquals(4, catalogDAO.count("restaurants"));
        assertEquals(6, catalogDAO.count("transport_options"));
    }

    @Test
    @DisplayName("Verify partial catalog state seeds missing tables while preserving existing tables")
    void testPartialCatalogSeedingPreservesExistingAndFillsMissing() throws SQLException, IOException {
        // Clear all catalog records
        catalogDAO.deleteAll();

        // Populate ONLY destinations with a custom record
        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "INSERT INTO destinations (id, name, state, category, region, suggested_days, details) " +
                             "VALUES ('custom-dest', 'Custom Heritage City', 'Custom State', 'Heritage', 'South', 2, " +
                             "'{\"id\":\"custom-dest\",\"name\":\"Custom Heritage City\"}')")) {
            ps.executeUpdate();
        }

        assertEquals(1, catalogDAO.count("destinations"));
        assertEquals(0, catalogDAO.count("hotels"));
        assertEquals(0, catalogDAO.count("restaurants"));
        assertEquals(0, catalogDAO.count("transport_options"));

        try {
            // Execute seedCatalog() on this partial state
            DatabaseInitializer.seedCatalog();

            // Verify existing destination was NOT overwritten or duplicated
            assertEquals(1, catalogDAO.count("destinations"), "Existing destinations table must remain untouched (count 1)");
            JsonObject custom = catalogDAO.findDestinationById("custom-dest");
            assertNotNull(custom);
            assertEquals("Custom Heritage City", custom.get("name").getAsString());

            // Verify missing tables were successfully seeded
            assertEquals(4, catalogDAO.count("hotels"), "Hotels should be seeded (count 4)");
            assertEquals(4, catalogDAO.count("restaurants"), "Restaurants should be seeded (count 4)");
            assertEquals(6, catalogDAO.count("transport_options"), "Transport options should be seeded (count 6)");

            // Verify subsequent calls remain idempotent
            DatabaseInitializer.seedCatalog();
            assertEquals(1, catalogDAO.count("destinations"));
            assertEquals(4, catalogDAO.count("hotels"));
            assertEquals(4, catalogDAO.count("restaurants"));
            assertEquals(6, catalogDAO.count("transport_options"));
        } finally {
            // Restore full catalog for subsequent tests
            catalogDAO.deleteAll();
            DatabaseInitializer.seedCatalog();
        }
    }

    // --- Servlet Unit Integration Tests ---

    @Test
    @DisplayName("Verify DestinationServlet HTTP GET collection and item details")
    void testDestinationServletEndpoints() throws IOException {
        DestinationServlet servlet = new DestinationServlet();

        // 1. GET /api/destinations
        Map<String, String> params = new HashMap<>();
        MockResponse resp1 = invokeServlet(servlet, "GET", "", params);
        assertEquals(200, resp1.status);
        JsonObject json1 = JsonUtil.GSON.fromJson(resp1.content, JsonObject.class);
        assertEquals(6, json1.get("count").getAsInt());
        assertEquals(6, json1.getAsJsonArray("items").size());

        // 2. GET /api/destinations/mysuru
        MockResponse resp2 = invokeServlet(servlet, "GET", "mysuru", params);
        assertEquals(200, resp2.status);
        JsonObject dest = JsonUtil.GSON.fromJson(resp2.content, JsonObject.class);
        assertEquals("mysuru", dest.get("id").getAsString());

        // 3. GET /api/destinations/missing-id -> 404
        MockResponse resp3 = invokeServlet(servlet, "GET", "missing-id", params);
        assertEquals(404, resp3.status);

        // 4. GET /api/destinations?limit=invalid -> 400
        params.put("limit", "abc");
        MockResponse resp4 = invokeServlet(servlet, "GET", "", params);
        assertEquals(400, resp4.status);

        // 5. POST /api/destinations -> 405
        MockResponse resp5 = invokeServlet(servlet, "POST", "", new HashMap<>());
        assertEquals(405, resp5.status);
    }

    @Test
    @DisplayName("Verify HotelServlet HTTP GET collection, details, and 400 validation")
    void testHotelServletEndpoints() throws IOException {
        HotelServlet servlet = new HotelServlet();

        // 1. GET /api/hotels
        Map<String, String> params = new HashMap<>();
        MockResponse resp1 = invokeServlet(servlet, "GET", "", params);
        assertEquals(200, resp1.status);
        JsonObject json1 = JsonUtil.GSON.fromJson(resp1.content, JsonObject.class);
        assertEquals(4, json1.get("count").getAsInt());

        // 2. GET /api/hotels/ht-001
        MockResponse resp2 = invokeServlet(servlet, "GET", "ht-001", params);
        assertEquals(200, resp2.status);
        JsonObject hotel = JsonUtil.GSON.fromJson(resp2.content, JsonObject.class);
        assertEquals("ht-001", hotel.get("id").getAsString());

        // 3. GET /api/hotels?minRating=invalid -> 400
        params.put("minRating", "five");
        MockResponse resp3 = invokeServlet(servlet, "GET", "", params);
        assertEquals(400, resp3.status);

        // 4. PUT /api/hotels -> 405
        MockResponse resp4 = invokeServlet(servlet, "PUT", "", new HashMap<>());
        assertEquals(405, resp4.status);
    }

    @Test
    @DisplayName("Verify RestaurantServlet HTTP GET collection, details, and 404/405")
    void testRestaurantServletEndpoints() throws IOException {
        RestaurantServlet servlet = new RestaurantServlet();

        // 1. GET /api/restaurants
        Map<String, String> params = new HashMap<>();
        MockResponse resp1 = invokeServlet(servlet, "GET", "", params);
        assertEquals(200, resp1.status);
        JsonObject json1 = JsonUtil.GSON.fromJson(resp1.content, JsonObject.class);
        assertEquals(4, json1.get("count").getAsInt());

        // 2. GET /api/restaurants/rest-001
        MockResponse resp2 = invokeServlet(servlet, "GET", "rest-001", params);
        assertEquals(200, resp2.status);
        JsonObject rest = JsonUtil.GSON.fromJson(resp2.content, JsonObject.class);
        assertEquals("rest-001", rest.get("id").getAsString());

        // 3. GET /api/restaurants/rest-999 -> 404
        MockResponse resp3 = invokeServlet(servlet, "GET", "rest-999", params);
        assertEquals(404, resp3.status);

        // 4. DELETE /api/restaurants/rest-001 -> 405
        MockResponse resp4 = invokeServlet(servlet, "DELETE", "rest-001", new HashMap<>());
        assertEquals(405, resp4.status);
    }

    @Test
    @DisplayName("Verify TransportServlet HTTP GET /search and /{id}")
    void testTransportServletEndpoints() throws IOException {
        TransportServlet servlet = new TransportServlet();

        // 1. GET /api/transport/search
        Map<String, String> params = new HashMap<>();
        MockResponse resp1 = invokeServlet(servlet, "GET", "search", params);
        assertEquals(200, resp1.status);
        JsonObject json1 = JsonUtil.GSON.fromJson(resp1.content, JsonObject.class);
        assertEquals(6, json1.get("count").getAsInt());

        // 2. GET /api/transport/tr-001
        MockResponse resp2 = invokeServlet(servlet, "GET", "tr-001", params);
        assertEquals(200, resp2.status);
        JsonObject tr = JsonUtil.GSON.fromJson(resp2.content, JsonObject.class);
        assertEquals("tr-001", tr.get("id").getAsString());

        // 3. GET /api/transport/unknown-id -> 404
        MockResponse resp3 = invokeServlet(servlet, "GET", "tr-unknown", params);
        assertEquals(404, resp3.status);

        // 4. POST /api/transport/search -> 405
        MockResponse resp4 = invokeServlet(servlet, "POST", "search", new HashMap<>());
        assertEquals(405, resp4.status);
    }

    // --- Mock Servlet Helpers ---

    private static class MockResponse {
        final int status;
        final String content;

        MockResponse(int status, String content) {
            this.status = status;
            this.content = content;
        }
    }

    private static MockResponse invokeServlet(ApiServlet servlet, String method, String pathInfo, Map<String, String> params) throws IOException {
        int[] status = new int[]{200};
        StringWriter sw = new StringWriter();

        HttpServletRequest req = (HttpServletRequest) Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class},
                (proxy, m, args) -> {
                    String name = m.getName();
                    if (name.equals("getMethod")) return method;
                    if (name.equals("getPathInfo")) return pathInfo.isEmpty() ? null : "/" + pathInfo;
                    if (name.equals("getParameter")) return params.get((String) args[0]);
                    if (name.equals("setCharacterEncoding")) return null;
                    if (name.equals("getCharacterEncoding")) return "UTF-8";
                    return null;
                }
        );

        HttpServletResponse resp = (HttpServletResponse) Proxy.newProxyInstance(
                HttpServletResponse.class.getClassLoader(),
                new Class<?>[]{HttpServletResponse.class},
                (proxy, m, args) -> {
                    String name = m.getName();
                    if (name.equals("setStatus")) {
                        status[0] = (int) args[0];
                        return null;
                    }
                    if (name.equals("getStatus")) return status[0];
                    if (name.equals("getWriter")) return new PrintWriter(sw);
                    if (name.equals("setContentType") || name.equals("setCharacterEncoding") || name.equals("setHeader")) {
                        return null;
                    }
                    return null;
                }
        );

        try {
            servlet.service(req, resp);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return new MockResponse(status[0], sw.toString());
    }
}
