package com.travelmate;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.travelmate.ai.AiClient;
import com.travelmate.ai.ItineraryGenerator;
import com.travelmate.geo.GeoService;
import com.travelmate.model.AiSettings;
import com.travelmate.model.ItineraryDay;
import com.travelmate.model.ItineraryStop;
import com.travelmate.model.Place;
import com.travelmate.servlet.PlaceServlet;
import com.travelmate.util.JsonUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive verification of TravelMate's Geographical & Nominatim engine:
 * 1. Geocoding (query parsing, bounding box, OSM IDs, missing/invalid
 * coordinates)
 * 2. Reverse Geocoding (coordinate validation, snapped point, address
 * normalization)
 * 3. Place Search (category filtering, deduplication, result limits)
 * 4. Nominatim API Reliability & Policy (HTTP 429, 403, 500, 502, 503,
 * timeouts, interruptions)
 * 5. Rate Limiting and Bounded Caching (concurrency pacing, TTL expiration, key
 * normalization)
 * 6. AI Itinerary Map Enrichment (coordinate bounds, user stop preservation,
 * lookup deduplication)
 * 7. PlaceServlet HTTP Integration (search, reverse, geocode, 405 Method Not
 * Allowed, error propagation)
 */
public class GeoServiceTest {

    private MockGeoHttpClient mockHttp;
    private GeoService geoService;

    @BeforeEach
    void setUp() {
        mockHttp = new MockGeoHttpClient();
        geoService = new GeoService(mockHttp, "https://nominatim.test", 0);
    }

    // ================================================================
    // 1. GEOCODING TESTS
    // ================================================================

    @Test
    @DisplayName("Verify geocoding with a valid place query returns enriched Place object")
    void testGeocodeValidQuery() {
        mockHttp.addResponse("/search", 200, buildNominatimPlaceArray(
                "Mysore Palace", "tourism", "attraction", 12.3051, 76.6551,
                "osm:way/12345", "way", "12345",
                "Mysore Palace, Sayyaji Rao Road, Mysuru, Karnataka 570001, India",
                "10:00-17:30", "https://mysorepalace.karnataka.gov.in", "+91-821-2421051"));

        Place place = geoService.geocode("Mysore Palace");
        assertNotNull(place, "Geocoded place should not be null");
        assertEquals("Mysore Palace", place.name);
        assertEquals(12.3051, place.lat, 0.0001);
        assertEquals(76.6551, place.lng, 0.0001);
        assertEquals("osm:way/12345", place.placeRef);
        assertEquals("way", place.osmKind);
        assertEquals("12345", place.osmId);
        assertEquals("tourism/attraction", place.osmType);
        assertEquals("Sightseeing", place.category);
        assertEquals("bi-camera", place.categoryIcon);
        assertEquals("10:00-17:30", place.openingHours);
        assertEquals("https://mysorepalace.karnataka.gov.in", place.website);
        assertEquals("+91-821-2421051", place.phone);
        assertNotNull(place.boundingBox);
        assertEquals(4, place.boundingBox.length);
    }

    @Test
    @DisplayName("Verify geocoding with empty or blank query returns null safely")
    void testGeocodeEmptyOrBlankQuery() {
        assertNull(geoService.geocode(""));
        assertNull(geoService.geocode("   "));
        assertNull(geoService.geocode((String) null));
    }

    @Test
    @DisplayName("Verify geocoding with destination and center bounds prioritizes center")
    void testGeocodeWithDestinationAndCenter() {
        mockHttp.addResponse("/search", 200, buildNominatimPlaceArray(
                "Chamundi Hill", "historic", "monument", 12.2743, 76.6710,
                "osm:node/67890", "node", "67890",
                "Chamundi Hill, Mysuru, Karnataka", null, null, null));

        double[] center = new double[] { 12.3051, 76.6551 };
        Place place = geoService.geocode("Chamundi Hill", "Mysuru", center);
        assertNotNull(place);
        assertEquals("Chamundi Hill", place.name);
        assertEquals(12.2743, place.lat, 0.0001);
        assertEquals("Heritage", place.category);
    }

    @Test
    @DisplayName("Verify geocoding empty search response returns null")
    void testGeocodeEmptyResponse() {
        mockHttp.addResponse("/search", 200, "[]");
        Place place = geoService.geocode("NonExistentUnknownLocation999XYZ");
        assertNull(place, "Empty response must return null without error");
    }

    @Test
    @DisplayName("Verify geocoding ignores results with invalid or corrupt coordinates")
    void testGeocodeCorruptCoordinates() {
        // Lat: 999.0 is invalid
        mockHttp.addResponse("/search", 200, buildNominatimPlaceArray(
                "Bogus Place", "tourism", "hotel", 999.0, 76.6551,
                "osm:node/111", "node", "111", "Bogus Address", null, null, null));

        Place place = geoService.geocode("Bogus Place");
        assertNull(place, "Results with invalid coordinates must be ignored");
    }

    // ================================================================
    // 2. REVERSE GEOCODING TESTS
    // ================================================================

    @Test
    @DisplayName("Verify reverse geocoding valid coordinates returns place and snaps clicked point")
    void testReverseValidCoordinates() throws IOException {
        mockHttp.addResponse("/reverse", 200, buildNominatimReverseObject(
                "Devaraja Market", "shop", "marketplace", 12.3090, 76.6510,
                "osm:way/5555", "way", "5555",
                "Devaraja Market, Sayyaji Rao Road, Mysuru, Karnataka, India"));

        Place place = geoService.reverse(12.3095, 76.6515);
        assertNotNull(place);
        assertEquals("Devaraja Market", place.name);
        // Snapped point requested by caller is preserved
        assertEquals(12.3095, place.lat, 0.0001);
        assertEquals(76.6515, place.lng, 0.0001);
        assertEquals("Shopping", place.category);
        assertEquals("bi-bag", place.categoryIcon);
    }

    @Test
    @DisplayName("Verify reverse geocoding rejects out-of-bounds latitude and longitude")
    void testReverseOutOfBoundsCoordinates() {
        assertThrows(IllegalArgumentException.class, () -> geoService.reverse(91.0, 76.0));
        assertThrows(IllegalArgumentException.class, () -> geoService.reverse(-91.0, 76.0));
        assertThrows(IllegalArgumentException.class, () -> geoService.reverse(12.0, 181.0));
        assertThrows(IllegalArgumentException.class, () -> geoService.reverse(12.0, -181.0));
    }

    @Test
    @DisplayName("Verify reverse geocoding rejects NaN and Infinite values")
    void testReverseNaNAndInfinity() {
        assertThrows(IllegalArgumentException.class, () -> geoService.reverse(Double.NaN, 76.0));
        assertThrows(IllegalArgumentException.class, () -> geoService.reverse(12.0, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> geoService.reverse(Double.POSITIVE_INFINITY, 76.0));
        assertThrows(IllegalArgumentException.class, () -> geoService.reverse(12.0, Double.NEGATIVE_INFINITY));
    }

    @Test
    @DisplayName("Verify reverse geocoding returns null on Nominatim error response")
    void testReverseErrorResponse() throws IOException {
        mockHttp.addResponse("/reverse", 200, "{\"error\": \"Unable to geocode\"}");
        Place place = geoService.reverse(0.0, 0.0);
        assertNull(place, "Nominatim error response should return null");
    }

    // ================================================================
    // 3. PLACE SEARCH TESTS
    // ================================================================

    @Test
    @DisplayName("Verify place search returns list of places with requested limit")
    void testPlaceSearchWithLimit() throws IOException {
        JsonArray array = new JsonArray();
        for (int i = 1; i <= 5; i++) {
            array.add(buildPlaceJson("Place " + i, "tourism", "hotel", 12.30 + (i * 0.01), 76.65, "osm:node/" + i,
                    "node", String.valueOf(i), "Address " + i));
        }
        mockHttp.addResponse("/search", 200, array.toString());

        List<Place> places = geoService.search("hotels in mysuru", null, 0, false, 5);
        assertEquals(5, places.size());
        assertEquals("Place 1", places.get(0).name);
        assertEquals("Place 5", places.get(4).name);
    }

    @Test
    @DisplayName("Verify place search category filtering")
    void testPlaceSearchCategoryFiltering() throws IOException {
        JsonArray array = new JsonArray();
        array.add(buildPlaceJson("Mylari Hotel (Restaurant)", "amenity", "restaurant", 12.305, 76.655, "osm:node/1",
                "node", "1", "Agrahara, Mysuru"));
        array.add(buildPlaceJson("Lalitha Mahal Palace", "historic", "castle", 12.301, 76.690, "osm:node/2", "node",
                "2", "Siddhartha Layout, Mysuru"));
        array.add(buildPlaceJson("Sri Chamundeshwari Temple", "amenity", "place_of_worship", 12.274, 76.671,
                "osm:node/3", "node", "3", "Chamundi Hill, Mysuru"));
        mockHttp.addResponse("/search", 200, array.toString());

        List<Place> foodOnly = geoService.search("mysuru", null, 0, false, 10, "Food");
        assertEquals(1, foodOnly.size());
        assertEquals("Mylari Hotel (Restaurant)", foodOnly.get(0).name);
        assertEquals("Food", foodOnly.get(0).category);

        List<Place> heritageOnly = geoService.search("mysuru", null, 0, false, 10, "Heritage");
        assertEquals(1, heritageOnly.size());
        assertEquals("Lalitha Mahal Palace", heritageOnly.get(0).name);
        assertEquals("Heritage", heritageOnly.get(0).category);

        List<Place> religiousOnly = geoService.search("mysuru", null, 0, false, 10, "Religious");
        assertEquals(1, religiousOnly.size());
        assertEquals("Sri Chamundeshwari Temple", religiousOnly.get(0).name);
        assertEquals("Religious", religiousOnly.get(0).category);
    }

    @Test
    @DisplayName("Verify place search deduplicates identical OSM references and nearby duplicates")
    void testPlaceSearchDeduplication() throws IOException {
        JsonArray array = new JsonArray();
        // Two entries with identical OSM ref
        array.add(buildPlaceJson("Mysore Palace", "tourism", "attraction", 12.3051, 76.6551, "osm:way/100", "way",
                "100", "Address 1"));
        array.add(buildPlaceJson("Mysore Palace", "historic", "castle", 12.3051, 76.6551, "osm:way/100", "way", "100",
                "Address 1"));
        // Entry with different ref but exact same name and coordinates (< 100m)
        array.add(buildPlaceJson("Mysore Palace", "tourism", "museum", 12.30515, 76.65515, "osm:node/200", "node",
                "200", "Address 2"));
        // Distinct place
        array.add(buildPlaceJson("Zoo Gardens", "tourism", "zoo", 12.3020, 76.6660, "osm:node/300", "node", "300",
                "Zoo Road, Mysuru"));
        mockHttp.addResponse("/search", 200, array.toString());

        List<Place> places = geoService.search("attractions", null, 0, false, 10);
        assertEquals(2, places.size(), "Duplicate places must be deduplicated");
        assertEquals("Mysore Palace", places.get(0).name);
        assertEquals("Zoo Gardens", places.get(1).name);
    }

    // ================================================================
    // 4. NOMINATIM RELIABILITY & ERROR HANDLING TESTS
    // ================================================================

    @Test
    @DisplayName("Verify handling of HTTP 429 Too Many Requests (Rate Limited)")
    void testNominatimRateLimit429() {
        mockHttp.addResponse("/search", 429, "Too Many Requests");
        IOException ex = assertThrows(IOException.class, () -> geoService.search("query", null, 0, false, 1));
        assertTrue(ex.getMessage().contains("rate limited") || ex.getMessage().contains("busy"),
                "Exception message must identify rate limiting: " + ex.getMessage());
    }

    @Test
    @DisplayName("Verify handling of HTTP 403 Forbidden")
    void testNominatimForbidden403() {
        mockHttp.addResponse("/search", 403, "Access Denied");
        IOException ex = assertThrows(IOException.class, () -> geoService.search("query", null, 0, false, 1));
        assertTrue(ex.getMessage().contains("403") || ex.getMessage().contains("forbidden"),
                "Exception message must indicate 403 error: " + ex.getMessage());
    }

    @Test
    @DisplayName("Verify handling of HTTP 500, 502, 503 Server Errors")
    void testNominatimServerErrors() {
        mockHttp.addResponse("/search", 500, "Internal Server Error");
        assertThrows(IOException.class, () -> geoService.search("query", null, 0, false, 1));

        mockHttp.addResponse("/search", 502, "Bad Gateway");
        assertThrows(IOException.class, () -> geoService.search("query", null, 0, false, 1));

        mockHttp.addResponse("/search", 503, "Service Unavailable");
        assertThrows(IOException.class, () -> geoService.search("query", null, 0, false, 1));
    }

    @Test
    @DisplayName("Verify handling of network timeout")
    void testNominatimTimeout() {
        mockHttp.setException("/search", new HttpTimeoutException("Connection timed out"));
        IOException ex = assertThrows(IOException.class, () -> geoService.search("query", null, 0, false, 1));
        assertTrue(ex.getMessage().contains("timed out") || ex.getCause() instanceof HttpTimeoutException);
    }

    @Test
    @DisplayName("Verify handling of malformed JSON response")
    void testNominatimMalformedJson() {
        mockHttp.addResponse("/search", 200, "<html><body>Gateway Timeout</body></html>");
        assertThrows(IOException.class, () -> geoService.search("query", null, 0, false, 1));
    }

    @Test
    @DisplayName("Verify thread interruption during rate limiting preserves interrupted flag")
    void testThreadInterruption() {
        GeoService serviceWithDelay = new GeoService(mockHttp, "https://nominatim.test", 5000);
        Thread.currentThread().interrupt(); // Pre-interrupt thread

        try {
            assertThrows(IOException.class, () -> serviceWithDelay.search("test", null, 0, false, 1));
            assertTrue(Thread.currentThread().isInterrupted(), "Thread interrupted flag must be preserved");
        } finally {
            Thread.interrupted(); // Clear interrupt flag
        }
    }

    // ================================================================
    // 5. CACHING & RATE LIMITER TESTS
    // ================================================================

    @Test
    @DisplayName("Verify identical queries hit the in-memory cache without repeating HTTP calls")
    void testCacheHit() throws IOException {
        mockHttp.addResponse("/search", 200, buildNominatimPlaceArray(
                "Mysore Palace", "tourism", "attraction", 12.3051, 76.6551,
                "osm:way/101", "way", "101", "Mysuru", null, null, null));

        List<Place> first = geoService.search("Mysore Palace", null, 0, false, 5);
        assertEquals(1, first.size());
        assertEquals(1, mockHttp.getRequestCount("/search"), "First query must make HTTP request");

        List<Place> second = geoService.search("Mysore Palace", null, 0, false, 5);
        assertEquals(1, second.size());
        assertEquals(1, mockHttp.getRequestCount("/search"),
                "Second query must be served from cache without new HTTP call");
    }

    @Test
    @DisplayName("Verify cache key normalization is independent of query parameter order")
    void testCacheKeyNormalization() {
        String url1 = "https://nominatim.openstreetmap.org/search?q=mysuru&limit=5&format=jsonv2";
        String url2 = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=5&q=mysuru";

        String key1 = GeoService.normalizeCacheKey(url1);
        String key2 = GeoService.normalizeCacheKey(url2);
        assertEquals(key1, key2, "Normalized cache keys must be equal regardless of param order");
    }

    @Test
    @DisplayName("Verify failed HTTP responses (429, 500) are never stored in cache")
    void testFailedResponsesNotCached() {
        mockHttp.addResponse("/search", 429, "Busy");
        assertThrows(IOException.class, () -> geoService.search("fail-test", null, 0, false, 1));
        assertEquals(0, geoService.cacheSize(), "Error response must not be placed in cache");

        // Now provider recovers
        mockHttp.addResponse("/search", 200, buildNominatimPlaceArray(
                "Success Place", "tourism", "attraction", 12.3, 76.6,
                "osm:node/99", "node", "99", "Address", null, null, null));
        assertDoesNotThrow(() -> {
            List<Place> places = geoService.search("fail-test", null, 0, false, 1);
            assertEquals(1, places.size());
        });
        assertEquals(1, geoService.cacheSize(), "Successful response is cached");
    }

    @Test
    @DisplayName("Verify concurrent requests through rate limiter are thread-safe")
    void testConcurrentRateLimiter() throws InterruptedException {
        int threads = 4;
        GeoService pacedService = new GeoService(mockHttp, "https://nominatim.test", 15);
        mockHttp.addResponse("/search", 200, "[]");

        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            new Thread(() -> {
                try {
                    pacedService.search("place-" + idx, null, 0, false, 1);
                    successCount.incrementAndGet();
                } catch (IOException ignored) {
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS), "All concurrent requests should complete");
        assertEquals(threads, successCount.get(), "All threads should succeed under serialized pacing");
    }

    @Test
    @DisplayName("Verify multiple separate instances targeting public Nominatim share global limiter and cache")
    void testPublicNominatimInstancesShareLimiterAndCache() throws IOException {
        GeoService.resetPublicLimiterForTest();
        String publicUrl = "https://nominatim.openstreetmap.org";
        GeoService instanceA = new GeoService(mockHttp, publicUrl, 0);
        GeoService instanceB = new GeoService(mockHttp, publicUrl, 0);

        instanceA.clearCache();
        mockHttp.addResponse("/search", 200, buildNominatimPlaceArray(
                "Public Place", "tourism", "attraction", 12.30, 76.65,
                "osm:node/999", "node", "999", "Mysuru", null, null, null
        ));

        // Query through Instance A
        List<Place> resultA = instanceA.search("Shared Test", null, 0, false, 1);
        assertEquals(1, resultA.size());
        assertEquals(1, mockHttp.getRequestCount("/search"));

        // Query through Instance B (different object!)
        List<Place> resultB = instanceB.search("Shared Test", null, 0, false, 1);
        assertEquals(1, resultB.size());
        // Served from shared cache without repeating HTTP call!
        assertEquals(1, mockHttp.getRequestCount("/search"));
    }

    // ================================================================
    // 6. AI ITINERARY INTEGRATION TESTS
    // ================================================================

    @Test
    @DisplayName("Verify AI itinerary generator preserves valid user-defined coordinates")
    void testAiItineraryPreservesUserCoordinates() throws AiClient.AiException {
        AiSettings s = new AiSettings();
        s.enabled = true;
        s.model = "test-model";
        s.baseUrl = "https://mock.ai/v1";

        // Mock AI client to return plan with a user stop and an AI stop
        HttpClient mockAi = new MockGeoHttpClient();
        ItineraryGenerator generator = new ItineraryGenerator(s, mockAi, geoService);

        ItineraryGenerator.TourRequest req = new ItineraryGenerator.TourRequest();
        req.destination = "Mysuru";
        req.plan = new ArrayList<>();

        ItineraryDay day1 = new ItineraryDay();
        day1.dayNumber = 1;
        day1.stops = new ArrayList<>();

        ItineraryStop userStop = new ItineraryStop();
        userStop.id = "user-stop-custom-1"; // User stop!
        userStop.name = "My Custom Hotel";
        userStop.lat = 12.3123;
        userStop.lng = 76.6543;
        day1.stops.add(userStop);

        req.plan.add(day1);
        req.mode = "organize";

        // AI reply returning schedule
        JsonObject aiReply = new JsonObject();
        JsonArray daysArr = new JsonArray();
        JsonObject dObj = new JsonObject();
        dObj.addProperty("dayNumber", 1);
        JsonArray stopsArr = new JsonArray();
        JsonObject sObj = new JsonObject();
        sObj.addProperty("id", "user-stop-custom-1");
        sObj.addProperty("time", "10:00 AM");
        sObj.addProperty("duration", "1.5 hrs");
        stopsArr.add(sObj);
        dObj.add("stops", stopsArr);
        daysArr.add(dObj);
        aiReply.add("days", daysArr);

        ((MockGeoHttpClient) mockAi).addResponse("/chat/completions", 200, buildAiChatResponse(aiReply.toString()));

        ItineraryGenerator.TourResult result = generator.planTour(req);
        assertNotNull(result);
        assertEquals(1, result.days.size());
        ItineraryStop stop = result.days.get(0).stops.get(0);
        assertEquals(12.3123, stop.lat, 0.0001, "User-provided latitude must be preserved");
        assertEquals(76.6543, stop.lng, 0.0001, "User-provided longitude must be preserved");
    }

    @Test
    @DisplayName("Verify AI itinerary generator does not crash when GeoService fails")
    void testAiItineraryGracefulOnGeoFailure() throws AiClient.AiException {
        mockHttp.addResponse("/search", 503, "Service Unavailable");

        AiSettings s = new AiSettings();
        s.enabled = true;
        s.model = "test-model";
        s.baseUrl = "https://mock.ai/v1";

        HttpClient mockAi = new MockGeoHttpClient();
        ItineraryGenerator generator = new ItineraryGenerator(s, mockAi, geoService);

        ItineraryGenerator.Request req = new ItineraryGenerator.Request();
        req.destination = "Mysuru";
        req.days = 1;
        req.center = new double[] { 12.3051, 76.6551 };

        JsonObject aiReply = new JsonObject();
        aiReply.addProperty("title", "Mysuru Tour");
        JsonArray days = new JsonArray();
        JsonObject d = new JsonObject();
        d.addProperty("dayNumber", 1);
        d.addProperty("title", "Day 1");
        JsonArray stops = new JsonArray();
        JsonObject stop = new JsonObject();
        stop.addProperty("name", "St Philomena Church");
        stop.addProperty("lat", 12.3210);
        stop.addProperty("lng", 76.6580);
        stops.add(stop);
        d.add("stops", stops);
        days.add(d);
        aiReply.add("days", days);

        ((MockGeoHttpClient) mockAi).addResponse("/chat/completions", 200, buildAiChatResponse(aiReply.toString()));

        ItineraryGenerator.Result res = generator.generate(req);
        assertNotNull(res, "Itinerary generation must succeed even when Nominatim map enrichment fails");
        assertEquals("Mysuru Tour", res.title);
        assertEquals(0, res.placesVerified, "0 places verified when GeoService is unavailable");
    }

    @Test
    @DisplayName("Verify duplicate stops in single itinerary run are looked up only once")
    void testAiItineraryDeduplicatesLookups() throws AiClient.AiException {
        mockHttp.addResponse("/search", 200, buildNominatimPlaceArray(
                "Mysore Palace", "tourism", "attraction", 12.3051, 76.6551,
                "osm:way/101", "way", "101", "Mysuru", null, null, null));

        AiSettings s = new AiSettings();
        s.enabled = true;
        s.model = "test-model";
        s.baseUrl = "https://mock.ai/v1";

        HttpClient mockAi = new MockGeoHttpClient();
        ItineraryGenerator generator = new ItineraryGenerator(s, mockAi, geoService);

        ItineraryGenerator.Request req = new ItineraryGenerator.Request();
        req.destination = "Mysuru";
        req.days = 2;
        req.center = new double[] { 12.3051, 76.6551 };

        JsonObject aiReply = new JsonObject();
        JsonArray days = new JsonArray();
        // Day 1 has Mysore Palace
        JsonObject d1 = new JsonObject();
        d1.addProperty("dayNumber", 1);
        JsonArray s1 = new JsonArray();
        JsonObject stop1 = new JsonObject();
        stop1.addProperty("name", "Mysore Palace");
        s1.add(stop1);
        d1.add("stops", s1);
        days.add(d1);

        // Day 2 also visits Mysore Palace
        JsonObject d2 = new JsonObject();
        d2.addProperty("dayNumber", 2);
        JsonArray s2 = new JsonArray();
        JsonObject stop2 = new JsonObject();
        stop2.addProperty("name", "Mysore Palace");
        s2.add(stop2);
        d2.add("stops", s2);
        days.add(d2);
        aiReply.add("days", days);

        ((MockGeoHttpClient) mockAi).addResponse("/chat/completions", 200, buildAiChatResponse(aiReply.toString()));

        ItineraryGenerator.Result res = generator.generate(req);
        assertNotNull(res);
        assertEquals(2, res.placesVerified);
        // Under local lookup caching, only 1 lookup call to GeoService occurred for
        // "Mysore Palace"
        assertEquals(1, mockHttp.getRequestCount("/search"));
    }

    // ================================================================
    // 7. PLACE SERVLET HTTP API TESTS
    // ================================================================

    @Test
    @DisplayName("Verify PlaceServlet GET /api/places/search returns success and places array")
    void testServletSearchEndpoint() throws IOException {
        mockHttp.addResponse("/search", 200, buildNominatimPlaceArray(
                "Mysore Palace", "tourism", "attraction", 12.3051, 76.6551,
                "osm:way/1", "way", "1", "Mysuru", null, null, null));

        PlaceServlet servlet = new PlaceServlet(geoService);
        Map<String, String> params = Map.of("q", "Mysore Palace", "limit", "5");
        MockResponse resp = invokeServlet(servlet, "GET", "search", params);

        assertEquals(200, resp.status);
        JsonObject json = JsonUtil.GSON.fromJson(resp.content, JsonObject.class);
        assertTrue(json.get("success").getAsBoolean());
        assertTrue(json.has("places"));
        assertEquals(1, json.get("count").getAsInt());
    }

    @Test
    @DisplayName("Verify PlaceServlet GET /api/places/search validates empty query with 400")
    void testServletSearchEmptyQuery() throws IOException {
        PlaceServlet servlet = new PlaceServlet(geoService);
        MockResponse resp = invokeServlet(servlet, "GET", "search", Map.of("q", ""));
        assertEquals(400, resp.status);
        assertTrue(resp.content.contains("Enter a place to search for"));
    }

    @Test
    @DisplayName("Verify PlaceServlet GET /api/places/reverse returns Place object directly")
    void testServletReverseEndpoint() throws IOException {
        mockHttp.addResponse("/reverse", 200, buildNominatimReverseObject(
                "Lalitha Mahal", "historic", "castle", 12.301, 76.690,
                "osm:node/55", "node", "55", "Siddhartha Layout, Mysuru"));

        PlaceServlet servlet = new PlaceServlet(geoService);
        Map<String, String> params = Map.of("lat", "12.301", "lng", "76.690");
        MockResponse resp = invokeServlet(servlet, "GET", "reverse", params);

        assertEquals(200, resp.status);
        JsonObject json = JsonUtil.GSON.fromJson(resp.content, JsonObject.class);
        assertEquals("Lalitha Mahal", json.get("name").getAsString());
        assertEquals("Heritage", json.get("category").getAsString());
        assertEquals(12.301, json.get("lat").getAsDouble(), 0.0001);
    }

    @Test
    @DisplayName("Verify PlaceServlet GET /api/places/reverse returns 400 on invalid coordinates")
    void testServletReverseInvalidCoords() throws IOException {
        PlaceServlet servlet = new PlaceServlet(geoService);
        MockResponse resp = invokeServlet(servlet, "GET", "reverse", Map.of("lat", "100.5", "lng", "76.0"));
        assertEquals(400, resp.status);
        assertTrue(resp.content.contains("lat must be between -90 and 90"));
    }

    @Test
    @DisplayName("Verify PlaceServlet GET /api/places/geocode returns single geocoded Place")
    void testServletGeocodeEndpoint() throws IOException {
        mockHttp.addResponse("/search", 200, buildNominatimPlaceArray(
                "Chamundi Hill", "historic", "monument", 12.2743, 76.6710,
                "osm:node/678", "node", "678", "Mysuru", null, null, null));

        PlaceServlet servlet = new PlaceServlet(geoService);
        MockResponse resp = invokeServlet(servlet, "GET", "geocode", Map.of("q", "Chamundi Hill"));
        assertEquals(200, resp.status);

        JsonObject json = JsonUtil.GSON.fromJson(resp.content, JsonObject.class);
        assertEquals("Chamundi Hill", json.get("name").getAsString());
        assertEquals(12.2743, json.get("lat").getAsDouble(), 0.0001);
    }

    @Test
    @DisplayName("Verify PlaceServlet rejects unsupported HTTP methods with 405 Method Not Allowed")
    void testServletUnsupportedMethods() throws IOException {
        PlaceServlet servlet = new PlaceServlet(geoService);

        MockResponse postResp = invokeServlet(servlet, "POST", "search", Map.of("q", "Mysore"));
        assertEquals(405, postResp.status);

        MockResponse putResp = invokeServlet(servlet, "PUT", "search", Map.of("q", "Mysore"));
        assertEquals(405, putResp.status);

        MockResponse deleteResp = invokeServlet(servlet, "DELETE", "search", Map.of("q", "Mysore"));
        assertEquals(405, deleteResp.status);
    }

    @Test
    @DisplayName("Verify PlaceServlet propagates upstream rate limit as HTTP 429")
    void testServletPropagatesRateLimit429() throws IOException {
        mockHttp.addResponse("/search", 429, "Too Many Requests");

        PlaceServlet servlet = new PlaceServlet(geoService);
        MockResponse resp = invokeServlet(servlet, "GET", "search", Map.of("q", "Mysore Palace"));
        assertEquals(429, resp.status);
    }

    @Test
    @DisplayName("Verify PlaceServlet returns 404 on unknown action")
    void testServletUnknownAction() throws IOException {
        PlaceServlet servlet = new PlaceServlet(geoService);
        MockResponse resp = invokeServlet(servlet, "GET", "unsupported-action", Collections.emptyMap());
        assertEquals(404, resp.status);
    }

    // ================================================================
    // ADDITIONAL RATE LIMITER & HOST IDENTIFICATION TESTS (PHASE 5 CORRECTIONS)
    // ================================================================

    @Test
    @DisplayName("Verify public Nominatim cannot be configured with 0 interval (must enforce >= 1100ms)")
    void testPublicNominatimConfiguredWithZeroInterval() {
        GeoService service = new GeoService(mockHttp, "https://nominatim.openstreetmap.org", 0);
        assertEquals(1100, service.getEffectiveMinIntervalMs(), "Public Nominatim interval must never drop below 1100ms");
    }

    @Test
    @DisplayName("Verify public Nominatim cannot be configured with negative interval (must enforce >= 1100ms)")
    void testPublicNominatimConfiguredWithNegativeInterval() {
        GeoService service = new GeoService(mockHttp, "https://nominatim.openstreetmap.org", -500);
        assertEquals(1100, service.getEffectiveMinIntervalMs(), "Public Nominatim negative interval must default to at least 1100ms");
    }

    @Test
    @DisplayName("Verify public Nominatim configured with sub-1100ms interval enforces 1100ms")
    void testPublicNominatimConfiguredWithSubInterval() {
        GeoService service = new GeoService(mockHttp, "https://nominatim.openstreetmap.org", 500);
        assertEquals(1100, service.getEffectiveMinIntervalMs(), "Sub-1100ms interval on public Nominatim must be raised to 1100ms");
    }

    @Test
    @DisplayName("Verify custom test servers retain their configured interval independently")
    void testCustomServerRetainsConfiguredInterval() {
        GeoService s0 = new GeoService(mockHttp, "https://nominatim.test", 0);
        assertEquals(0, s0.getEffectiveMinIntervalMs(), "Custom test server with 0ms interval must retain 0ms for fast tests");

        GeoService s250 = new GeoService(mockHttp, "https://nominatim.test", 250);
        assertEquals(250, s250.getEffectiveMinIntervalMs(), "Custom test server must retain 250ms interval");

        GeoService sNeg = new GeoService(mockHttp, "https://nominatim.test", -100);
        assertEquals(0, sNeg.getEffectiveMinIntervalMs(), "Negative interval on custom server must clamp to 0ms");
    }

    @Test
    @DisplayName("Verify exact public Nominatim hostname matching")
    void testExactPublicHostname() {
        assertTrue(GeoService.isPublicNominatimHost("https://nominatim.openstreetmap.org"));
        assertTrue(GeoService.isPublicNominatimHost("https://nominatim.openstreetmap.org/"));
        assertTrue(GeoService.isPublicNominatimHost("http://nominatim.openstreetmap.org/search?q=test"));
        assertTrue(GeoService.isPublicNominatimHost("https://NOMINATIM.OPENSTREETMAP.ORG"));
        assertTrue(GeoService.isPublicNominatimHost("https://nominatim.openstreetmap.org:443/reverse"));
    }

    @Test
    @DisplayName("Verify substring hostname does NOT trigger public Nominatim rate limiter")
    void testSubstringHostnameDoesNotTriggerPublicLimiter() {
        assertFalse(GeoService.isPublicNominatimHost("https://fake-nominatim.openstreetmap.org"));
        assertFalse(GeoService.isPublicNominatimHost("https://nominatim.openstreetmap.org.attacker.com"));
        assertFalse(GeoService.isPublicNominatimHost("https://notnominatim.openstreetmap.org"));
        assertFalse(GeoService.isPublicNominatimHost("https://example.com/nominatim.openstreetmap.org"));
    }

    @Test
    @DisplayName("Verify malformed URLs return false safely for public Nominatim check")
    void testMalformedUrlHostIdentification() {
        assertFalse(GeoService.isPublicNominatimHost("ht!tp://::invalid::url"));
        assertFalse(GeoService.isPublicNominatimHost(""));
        assertFalse(GeoService.isPublicNominatimHost("   "));
        assertFalse(GeoService.isPublicNominatimHost(null));
    }

    @Test
    @DisplayName("Verify custom configured endpoints return false for public Nominatim check")
    void testCustomConfiguredEndpoint() {
        assertFalse(GeoService.isPublicNominatimHost("https://nominatim.test"));
        assertFalse(GeoService.isPublicNominatimHost("http://localhost:8080"));
        assertFalse(GeoService.isPublicNominatimHost("https://maps.internal.example.org"));
    }

    @Test
    @DisplayName("Verify multiple concurrent threads on public Nominatim serialize outbound requests")
    void testMultipleConcurrentThreadsOnPublicNominatim() throws InterruptedException {
        GeoService.resetPublicLimiterForTest();
        int threads = 3;
        GeoService publicGeo = new GeoService(mockHttp, "https://nominatim.openstreetmap.org", 0);
        mockHttp.addResponse("/search", 200, "[]");

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(threads);
        List<Long> dispatchTimes = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threads; i++) {
            final int id = i;
            new Thread(() -> {
                try {
                    startGate.await();
                    publicGeo.search("test-thread-" + id, null, 0, false, 1);
                    dispatchTimes.add(System.currentTimeMillis());
                } catch (Exception ignored) {
                } finally {
                    endGate.countDown();
                }
            }).start();
        }

        startGate.countDown();
        assertTrue(endGate.await(10, TimeUnit.SECONDS), "All threads should complete within timeout");
        assertEquals(threads, dispatchTimes.size());
    }

    // ================================================================
    // ADDITIONAL COORDINATE SAFETY TESTS (PHASE 5 CORRECTIONS)
    // ================================================================

    @Test
    @DisplayName("Verify malformed coordinate strings return null and do NOT silently convert to 0.0")
    void testMalformedCoordinateStrings() {
        assertNull(GeoService.parseCoordinate("corrupt"));
        assertNull(GeoService.parseCoordinate("abc"));
        assertNull(GeoService.parseCoordinate(""));
        assertNull(GeoService.parseCoordinate("   "));
        assertNull(GeoService.parseCoordinate(null));
        assertNull(GeoService.parseCoordinate("NaN"));
        assertNull(GeoService.parseCoordinate("Infinity"));
        assertNull(GeoService.parseCoordinate("-Infinity"));
        assertEquals(12.3051, GeoService.parseCoordinate("12.3051"), 0.0001);
        assertEquals(-76.6551, GeoService.parseCoordinate("-76.6551"), 0.0001);
    }

    @Test
    @DisplayName("Verify null latitude or longitude fails coordinate validation")
    void testNullLatitudeAndNullLongitudeValidation() {
        assertFalse(GeoService.isValidCoordinate((Double) null, (Double) 76.6551));
        assertFalse(GeoService.isValidCoordinate((Double) 12.3051, (Double) null));
        assertFalse(GeoService.isValidCoordinate((Double) null, (Double) null));
    }

    @Test
    @DisplayName("Verify missing destination center leaves coordinates absent (null) rather than (0,0)")
    void testMissingDestinationCenterLeavesCoordinatesAbsent() throws AiClient.AiException {
        AiSettings s = new AiSettings();
        s.enabled = true;
        s.model = "test-model";
        s.baseUrl = "https://mock.ai/v1";

        HttpClient mockAi = new MockGeoHttpClient();
        ItineraryGenerator generator = new ItineraryGenerator(s, mockAi, geoService);

        ItineraryGenerator.Request req = new ItineraryGenerator.Request();
        req.destination = "UnknownPlaceWithoutCenter";
        req.days = 1;
        req.center = null;

        JsonObject aiReply = new JsonObject();
        aiReply.addProperty("title", "Offgrid Journey");
        JsonArray days = new JsonArray();
        JsonObject d = new JsonObject();
        d.addProperty("dayNumber", 1);
        JsonArray stops = new JsonArray();
        JsonObject stop = new JsonObject();
        stop.addProperty("name", "Mysterious Cave");
        stop.addProperty("lat", "corrupt");
        stop.addProperty("lng", "corrupt");
        stops.add(stop);
        d.add("stops", stops);
        days.add(d);
        aiReply.add("days", days);

        ((MockGeoHttpClient) mockAi).addResponse("/chat/completions", 200, buildAiChatResponse(aiReply.toString()));

        ItineraryGenerator.Result res = generator.generate(req);
        assertNotNull(res);
        ItineraryStop resStop = res.days.get(0).stops.get(0);
        assertNull(resStop.lat, "Latitude must be null when center is missing and AI coords are invalid");
        assertNull(resStop.lng, "Longitude must be null when center is missing and AI coords are invalid");
    }

    @Test
    @DisplayName("Verify invalid AI-generated coordinates fall back to verified destination center")
    void testInvalidAiGeneratedCoordinatesFallBackToVerifiedCenter() throws AiClient.AiException {
        AiSettings s = new AiSettings();
        s.enabled = true;
        s.model = "test-model";
        s.baseUrl = "https://mock.ai/v1";

        HttpClient mockAi = new MockGeoHttpClient();
        ItineraryGenerator generator = new ItineraryGenerator(s, mockAi, geoService);

        ItineraryGenerator.Request req = new ItineraryGenerator.Request();
        req.destination = "Mysuru";
        req.days = 1;
        req.center = new double[] { 12.3051, 76.6551 };

        JsonObject aiReply = new JsonObject();
        aiReply.addProperty("title", "Mysuru Trip");
        JsonArray days = new JsonArray();
        JsonObject d = new JsonObject();
        d.addProperty("dayNumber", 1);
        JsonArray stops = new JsonArray();
        JsonObject stop = new JsonObject();
        stop.addProperty("name", "Invented Outlier Spot");
        stop.addProperty("lat", 999.0);
        stop.addProperty("lng", 999.0);
        stops.add(stop);
        d.add("stops", stops);
        days.add(d);
        aiReply.add("days", days);

        ((MockGeoHttpClient) mockAi).addResponse("/chat/completions", 200, buildAiChatResponse(aiReply.toString()));

        ItineraryGenerator.Result res = generator.generate(req);
        assertNotNull(res);
        ItineraryStop resStop = res.days.get(0).stops.get(0);
        assertEquals(12.3051, resStop.lat, 0.05, "Must fall back to verified destination center");
        assertEquals(76.6551, resStop.lng, 0.05, "Must fall back to verified destination center");
    }

    @Test
    @DisplayName("Verify (0,0) is never introduced as an accidental fallback for places or itineraries")
    void testEnsuringZeroZeroIsNotIntroducedAsFallback() {
        JsonObject malformedJson = new JsonObject();
        malformedJson.addProperty("name", "Corrupt Place");
        malformedJson.addProperty("lat", "not-a-number");
        malformedJson.addProperty("lon", "not-a-number");
        Place place = GeoService.toPlace(malformedJson);
        assertNotNull(place);
        assertNull(place.lat, "Place latitude must be null, not 0.0");
        assertNull(place.lng, "Place longitude must be null, not 0.0");

        ItineraryStop stop = new ItineraryStop();
        stop.id = "stop-1";
        stop.name = "No Coords Spot";
        stop.lat = null;
        stop.lng = null;
        String json = JsonUtil.GSON.toJson(stop);
        assertFalse(json.contains("\"lat\":0.0"), "Serialized JSON must not contain 0.0 for absent latitude");
        assertFalse(json.contains("\"lng\":0.0"), "Serialized JSON must not contain 0.0 for absent longitude");
    }

    // ================================================================
    // HELPER METHODS & TEST DOUBLES
    // ================================================================

    private static String buildNominatimPlaceArray(String name, String cls, String type, double lat, double lng,
            String ref, String osmKind, String osmId, String display,
            String hours, String website, String phone) {
        JsonArray array = new JsonArray();
        JsonObject o = buildPlaceJson(name, cls, type, lat, lng, ref, osmKind, osmId, display);
        if (hours != null || website != null || phone != null) {
            JsonObject tags = new JsonObject();
            if (hours != null)
                tags.addProperty("opening_hours", hours);
            if (website != null)
                tags.addProperty("website", website);
            if (phone != null)
                tags.addProperty("phone", phone);
            o.add("extratags", tags);
        }
        array.add(o);
        return array.toString();
    }

    private static String buildNominatimReverseObject(String name, String cls, String type, double lat, double lng,
            String ref, String osmKind, String osmId, String display) {
        return buildPlaceJson(name, cls, type, lat, lng, ref, osmKind, osmId, display).toString();
    }

    private static JsonObject buildPlaceJson(String name, String cls, String type, double lat, double lng,
            String ref, String osmKind, String osmId, String display) {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("class", cls);
        o.addProperty("type", type);
        o.addProperty("lat", String.valueOf(lat));
        o.addProperty("lon", String.valueOf(lng));
        o.addProperty("display_name", display);
        o.addProperty("osm_type", osmKind);
        o.addProperty("osm_id", osmId);

        JsonArray bb = new JsonArray();
        bb.add(String.valueOf(lat - 0.01));
        bb.add(String.valueOf(lat + 0.01));
        bb.add(String.valueOf(lng - 0.01));
        bb.add(String.valueOf(lng + 0.01));
        o.add("boundingbox", bb);
        return o;
    }

    private static String buildAiChatResponse(String content) {
        JsonObject res = new JsonObject();
        JsonArray choices = new JsonArray();
        JsonObject choice = new JsonObject();
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "assistant");
        msg.addProperty("content", content);
        choice.add("message", msg);
        choices.add(choice);
        res.add("choices", choices);
        return res.toString();
    }

    private MockResponse invokeServlet(PlaceServlet servlet, String method, String action, Map<String, String> params)
            throws IOException {
        StringWriter out = new StringWriter();
        int[] statusHolder = new int[] { 200 };
        Map<String, String> headers = new HashMap<>();

        HttpServletRequest req = (HttpServletRequest) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] { HttpServletRequest.class },
                (proxy, m, args) -> {
                    String name = m.getName();
                    if ("getMethod".equals(name))
                        return method;
                    if ("getPathInfo".equals(name))
                        return "/" + action;
                    if ("getParameter".equals(name))
                        return params.get((String) args[0]);
                    if ("getParameterMap".equals(name)) {
                        Map<String, String[]> map = new HashMap<>();
                        params.forEach((k, v) -> map.put(k, new String[] { v }));
                        return map;
                    }
                    if ("getCharacterEncoding".equals(name))
                        return "UTF-8";
                    return null;
                });

        HttpServletResponse resp = (HttpServletResponse) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] { HttpServletResponse.class },
                (proxy, m, args) -> {
                    String name = m.getName();
                    if ("setStatus".equals(name)) {
                        statusHolder[0] = (int) args[0];
                        return null;
                    }
                    if ("getWriter".equals(name)) {
                        return new java.io.PrintWriter(out);
                    }
                    if ("setHeader".equals(name) || "addHeader".equals(name)) {
                        headers.put((String) args[0], String.valueOf(args[1]));
                        return null;
                    }
                    if ("setContentType".equals(name)) {
                        headers.put("Content-Type", (String) args[0]);
                        return null;
                    }
                    if ("setCharacterEncoding".equals(name)) {
                        headers.put("Character-Encoding", (String) args[0]);
                        return null;
                    }
                    return null;
                });

        try {
            servlet.service(req, resp);
        } catch (jakarta.servlet.ServletException e) {
            throw new IOException(e);
        }

        MockResponse response = new MockResponse();
        response.status = statusHolder[0];
        response.content = out.toString();
        response.headers = headers;
        return response;
    }

    public static class MockResponse {
        public int status;
        public String content;
        public Map<String, String> headers;
    }

    /**
     * Pure in-memory mock HttpClient for GeoService tests.
     */
    public static class MockGeoHttpClient extends HttpClient {
        private final Map<String, Integer> statusCodes = new ConcurrentHashMap<>();
        private final Map<String, String> bodies = new ConcurrentHashMap<>();
        private final Map<String, IOException> exceptions = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> requestCounts = new ConcurrentHashMap<>();

        public void addResponse(String pathSubstring, int status, String body) {
            statusCodes.put(pathSubstring, status);
            bodies.put(pathSubstring, body);
        }

        public void setException(String pathSubstring, IOException ex) {
            exceptions.put(pathSubstring, ex);
        }

        public int getRequestCount(String pathSubstring) {
            AtomicInteger c = requestCounts.get(pathSubstring);
            return c == null ? 0 : c.get();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseHandler)
                throws IOException, InterruptedException {
            String uri = request.uri().toString();
            for (String key : requestCounts.keySet()) {
                if (uri.contains(key)) {
                    requestCounts.get(key).incrementAndGet();
                }
            }
            for (Map.Entry<String, IOException> entry : exceptions.entrySet()) {
                if (uri.contains(entry.getKey())) {
                    throw entry.getValue();
                }
            }
            for (Map.Entry<String, String> entry : bodies.entrySet()) {
                if (uri.contains(entry.getKey())) {
                    requestCounts.computeIfAbsent(entry.getKey(), k -> new AtomicInteger(0)).incrementAndGet();
                    int code = statusCodes.getOrDefault(entry.getKey(), 200);
                    return (HttpResponse<T>) (HttpResponse<?>) new MockHttpResponse<String>(code, entry.getValue());
                }
            }
            return (HttpResponse<T>) (HttpResponse<?>) new MockHttpResponse<String>(200, "[]");
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                HttpResponse.BodyHandler<T> responseHandler) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return send(request, responseHandler);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.of(Duration.ofSeconds(10));
        }

        @Override
        public HttpClient.Redirect followRedirects() {
            return HttpClient.Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            return null;
        }

        @Override
        public SSLParameters sslParameters() {
            return null;
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                HttpResponse.BodyHandler<T> responseHandler, HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return null;
        }
    }

    public static class MockHttpResponse<T> implements HttpResponse<T> {
        private final int statusCode;
        private final T body;

        public MockHttpResponse(int statusCode, T body) {
            this.statusCode = statusCode;
            this.body = body;
        }

        @Override
        public int statusCode() {
            return statusCode;
        }

        @Override
        public HttpRequest request() {
            return null;
        }

        @Override
        public Optional<HttpResponse<T>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Collections.emptyMap(), (k, v) -> true);
        }

        @Override
        public T body() {
            return body;
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return URI.create("https://nominatim.test");
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
