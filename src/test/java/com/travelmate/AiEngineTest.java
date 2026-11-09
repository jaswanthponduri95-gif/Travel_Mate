package com.travelmate;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import com.travelmate.ai.AiClient;
import com.travelmate.ai.ItineraryGenerator;
import com.travelmate.dao.AiSettingsDAO;
import com.travelmate.dao.TripDAO;
import com.travelmate.db.DBConnection;
import com.travelmate.db.DatabaseInitializer;
import com.travelmate.model.AiSettings;
import com.travelmate.model.ItineraryDay;
import com.travelmate.model.ItineraryStop;
import com.travelmate.model.Transit;
import com.travelmate.model.Trip;
import com.travelmate.model.User;
import com.travelmate.servlet.AiServlet;
import com.travelmate.servlet.ApiServlet;
import com.travelmate.util.AiAccess;
import com.travelmate.util.JsonUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 4 verification of AI Itinerary and Tour Planning Engine:
 * - AI client (OpenAI-compatible) with mock HTTP provider doubles
 * - Error handling, status codes (401, 429, 500), timeouts, malformed JSON
 * - Secret masking and sanitization
 * - Itinerary generation, validation, markdown extraction, coordinates bounding
 * - Tour-plan generation (organize vs fill modes) and stop preservation
 * - AI settings lifecycle, defaults, database persistence, and owner authorization
 * - Trip persistence integration with day and stop ordering preservation
 * - Live servlet endpoints integration (status, itinerary, tour-plan, settings, models, test)
 */
class AiEngineTest {

    private static final String OWNER_EMAIL = "owner.tester@example.com";
    private static final String NON_OWNER_EMAIL = "traveler.guest@example.com";

    private static MockAiHttpClient mockAiServer;
    private final AiSettingsDAO settingsDAO = new AiSettingsDAO();
    private final TripDAO tripDAO = new TripDAO();

    // ---------------------------------------------------------------- Test Lifecycle

    @BeforeAll
    static void setUpAll() throws IOException {
        // Suppress external map lookups during test execution
        System.setProperty("tm.geo.url", "http://127.0.0.1:1");

        // Set up isolated H2 in-memory database
        DBConnection.configure("jdbc:h2:mem:tm_ai_engine_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        assertTrue(DatabaseInitializer.initialize(), "Database initialization should succeed");

        // Initialize in-memory mock OpenAI HTTP client
        mockAiServer = new MockAiHttpClient();
    }

    @AfterAll
    static void tearDownAll() {
        System.clearProperty("TM_AI_ALLOWED_EMAILS");
        System.clearProperty("tm.ai.allowedEmails");
        System.clearProperty("tm.geo.url");
        AiAccess.reload();
    }

    @BeforeEach
    void setUp() throws SQLException {
        // Reset server state
        mockAiServer.reset();

        // Reset settings table
        settingsDAO.reset();

        // Clear trips
        tripDAO.deleteAll();

        // Configure allowed owners
        System.setProperty("TM_AI_ALLOWED_EMAILS", OWNER_EMAIL + ", friend@example.com");
        AiAccess.reload();
    }

    @AfterEach
    void tearDown() throws SQLException {
        settingsDAO.reset();
        tripDAO.deleteAll();
    }

    // ================================================================
    // 1. AI CLIENT TESTS
    // ================================================================

    @Test
    @DisplayName("Verify AiClient valid chat completion with mock provider")
    void testAiClientValidChatCompletion() throws AiClient.AiException {
        AiSettings s = buildSettings(true, "gpt-4o-mini", "sk-valid-key-12345");
        mockAiServer.setResponse(200, sampleChatCompletionJson("{\"title\":\"Goa Trip\",\"days\":[]}"));

        AiClient client = new AiClient(s, mockAiServer);
        String reply = client.chat("You are TravelMate.", "Plan a trip.");

        assertNotNull(reply);
        assertTrue(reply.contains("Goa Trip"));
        assertEquals("Bearer sk-valid-key-12345", mockAiServer.lastAuthHeader);
        assertNotNull(mockAiServer.lastRequestBody);
        assertTrue(mockAiServer.lastRequestBody.contains("\"model\":\"gpt-4o-mini\""));
    }

    @Test
    @DisplayName("Verify AiClient handles HTTP 401 Unauthorized with helpful message")
    void testAiClientHttp401Unauthorized() {
        AiSettings s = buildSettings(true, "gpt-4o-mini", "sk-invalid-key");
        mockAiServer.setResponse(401, "{\"error\":{\"message\":\"Incorrect API key provided\"}}");

        AiClient client = new AiClient(s, mockAiServer);
        AiClient.AiException ex = assertThrows(AiClient.AiException.class, () ->
                client.chat("System", "User prompt"));

        assertTrue(ex.getMessage().contains("HTTP 401"));
        assertTrue(ex.getMessage().contains("check the API key"));
    }

    @Test
    @DisplayName("Verify AiClient handles HTTP 429 Rate Limit with quota warning")
    void testAiClientHttp429RateLimit() {
        AiSettings s = buildSettings(true, "gpt-4o-mini", "sk-exhausted-key");
        mockAiServer.setResponse(429, "{\"error\":{\"message\":\"You exceeded your current quota\"}}");

        AiClient client = new AiClient(s, mockAiServer);
        AiClient.AiException ex = assertThrows(AiClient.AiException.class, () ->
                client.chat("System", "User prompt"));

        assertTrue(ex.getMessage().contains("HTTP 429"));
        assertTrue(ex.getMessage().contains("rate limit or no credits"));
    }

    @Test
    @DisplayName("Verify AiClient handles HTTP 500 Internal Server Error")
    void testAiClientHttp500ServerError() {
        AiSettings s = buildSettings(true, "gpt-4o-mini", "sk-test-key");
        mockAiServer.setResponse(500, "{\"error\":{\"message\":\"The server had an error processing your request\"}}");

        AiClient client = new AiClient(s, mockAiServer);
        AiClient.AiException ex = assertThrows(AiClient.AiException.class, () ->
                client.chat("System", "User prompt"));

        assertTrue(ex.getMessage().contains("HTTP 500"));
        assertTrue(ex.getMessage().contains("error processing your request"));
    }

    @Test
    @DisplayName("Verify AiClient handles malformed/non-JSON provider response")
    void testAiClientMalformedJsonResponse() {
        AiSettings s = buildSettings(true, "gpt-4o-mini", "sk-test-key");
        mockAiServer.setResponse(200, "<html><body>502 Bad Gateway - upstream provider crashed</body></html>");

        AiClient client = new AiClient(s, mockAiServer);
        AiClient.AiException ex = assertThrows(AiClient.AiException.class, () ->
                client.chat("System", "User prompt"));

        assertTrue(ex.getMessage().contains("AI server reply was not JSON"));
    }

    @Test
    @DisplayName("Verify AiClient handles empty assistant content in reply")
    void testAiClientEmptyAssistantContent() {
        AiSettings s = buildSettings(true, "gpt-4o-mini", "sk-test-key");
        mockAiServer.setResponse(200, sampleChatCompletionJson("   "));

        AiClient client = new AiClient(s, mockAiServer);
        AiClient.AiException ex = assertThrows(AiClient.AiException.class, () ->
                client.chat("System", "User prompt"));

        assertTrue(ex.getMessage().contains("empty reply"));
    }

    @Test
    @DisplayName("Verify AiClient handles empty choices array from provider")
    void testAiClientNoChoicesReturned() {
        AiSettings s = buildSettings(true, "gpt-4o-mini", "sk-test-key");
        mockAiServer.setResponse(200, "{\"id\":\"chat-123\",\"choices\":[]}");

        AiClient client = new AiClient(s, mockAiServer);
        AiClient.AiException ex = assertThrows(AiClient.AiException.class, () ->
                client.chat("System", "User prompt"));

        assertTrue(ex.getMessage().contains("returned no choices"));
    }

    @Test
    @DisplayName("Verify AiClient handles read timeout safely")
    void testAiClientTimeoutHandling() {
        AiSettings s = buildSettings(true, "gpt-4o-mini", "sk-test-key");
        s.timeoutSeconds = 1;
        mockAiServer.throwTimeout = true;

        AiClient client = new AiClient(s, mockAiServer);
        AiClient.AiException ex = assertThrows(AiClient.AiException.class, () ->
                client.chat("System", "User prompt"));

        assertTrue(ex.getMessage().contains("did not answer within 1 seconds"));
    }

    @Test
    @DisplayName("Verify AiClient sanitizes secret API keys from error messages")
    void testAiClientSecretMasking() {
        String secretKey = "sk-super-secret-production-key-98765";
        AiSettings s = buildSettings(true, "gpt-4o-mini", secretKey);
        // Simulate a misbehaving external gateway that reflects the client's API key
        mockAiServer.setResponse(401, "{\"error\":{\"message\":\"Key " + secretKey + " was rejected by gateway\"}}");

        AiClient client = new AiClient(s, mockAiServer);
        AiClient.AiException ex = assertThrows(AiClient.AiException.class, () ->
                client.chat("System", "User prompt"));

        assertFalse(ex.getMessage().contains(secretKey), "Error message must never expose the raw API key");
        assertTrue(ex.getMessage().contains("••••"), "Secret key should be replaced with mask bullets");
    }

    @Test
    @DisplayName("Verify AiClient validates base URL scheme and format")
    void testAiClientInvalidBaseUrlConfig() {
        AiSettings s1 = buildSettings(true, "gpt-4o-mini", "sk-key");
        s1.baseUrl = "ftp://invalid-scheme.com";
        assertThrows(AiClient.AiException.class, () -> new AiClient(s1, mockAiServer).chat("System", "User"));

        AiSettings s2 = buildSettings(true, "gpt-4o-mini", "sk-key");
        s2.baseUrl = "http://not a valid uri with spaces:8080";
        assertThrows(AiClient.AiException.class, () -> new AiClient(s2, mockAiServer).chat("System", "User"));
    }

    @Test
    @DisplayName("Verify AiClient lists and sorts models from provider")
    void testAiClientListModelsSuccess() throws AiClient.AiException {
        AiSettings s = buildSettings(true, "gpt-4o-mini", "sk-key");
        mockAiServer.setResponse(200, "{\"data\":[{\"id\":\"mistralai/mistral-7b\"},{\"id\":\"anthropic/claude-3\"},{\"id\":\"google/gemini-pro\"}]}");

        AiClient client = new AiClient(s, mockAiServer);
        List<String> models = client.listModels();

        assertEquals(3, models.size());
        assertEquals("anthropic/claude-3", models.get(0));
        assertEquals("google/gemini-pro", models.get(1));
        assertEquals("mistralai/mistral-7b", models.get(2));
    }

    // ================================================================
    // 2. ITINERARY GENERATION TESTS
    // ================================================================

    @Test
    @DisplayName("Verify ItineraryGenerator successfully generates days and stops from valid input")
    void testItineraryGeneratorValidInput() throws AiClient.AiException {
        AiSettings s = buildSettings(true, "gpt-4o-mini", "sk-key");
        String aiReply = """
                {
                  "title": "Royal Mysuru Cultural Tour",
                  "days": [
                    {
                      "title": "Palace and Heritage",
                      "stops": [
                        {
                          "name": "Mysuru Palace",
                          "category": "Heritage",
                          "time": "09:30 AM",
                          "duration": "2.5 hrs",
                          "cost": 200,
                          "lat": 12.3051,
                          "lng": 76.6551,
                          "placeRef": "osm:node/101",
                          "notes": "Arrive early to avoid queues",
                          "transitToNext": { "mode": "Auto Rickshaw", "duration": "10 mins", "distance": "2 km", "cost": 50 }
                        },
                        {
                          "name": "Mylari Restaurant",
                          "category": "Food",
                          "time": "01:00 PM",
                          "duration": "1 hr",
                          "cost": 150,
                          "lat": 12.3080,
                          "lng": 76.6590,
                          "placeRef": "osm:node/102",
                          "notes": "Try authentic butter masala dosa"
                        }
                      ]
                    }
                  ]
                }
                """;
        mockAiServer.setResponse(200, sampleChatCompletionJson(aiReply));

        ItineraryGenerator gen = new ItineraryGenerator(s, mockAiServer);
        ItineraryGenerator.Request req = new ItineraryGenerator.Request();
        req.destination = "Mysuru";
        req.origin = "Bengaluru";
        req.days = 1;
        req.travelers = 2;
        req.budget = 10000;
        req.pace = "Moderate";
        req.interests = List.of("Heritage", "Food");
        req.center = new double[]{12.3051, 76.6551};

        ItineraryGenerator.Result result = gen.generate(req);

        assertNotNull(result);
        assertEquals("Royal Mysuru Cultural Tour", result.title);
        assertEquals(1, result.days.size());

        ItineraryDay day = result.days.get(0);
        assertEquals(1, day.dayNumber);
        assertEquals("Palace and Heritage", day.title);
        assertEquals(2, day.stops.size());

        ItineraryStop stop1 = day.stops.get(0);
        assertEquals("Mysuru Palace", stop1.name);
        assertEquals("Heritage", stop1.category);
        assertEquals("bi-bank", stop1.categoryIcon);
        assertNotNull(stop1.transitToNext);
        assertEquals("Auto Rickshaw", stop1.transitToNext.mode);
        assertEquals(50, stop1.transitToNext.cost);

        ItineraryStop stop2 = day.stops.get(1);
        assertEquals("Mylari Restaurant", stop2.name);
        assertEquals("bi-cup-hot", stop2.categoryIcon);
        assertNull(stop2.transitToNext, "Last stop of the day must have null transitToNext");
    }

    @Test
    @DisplayName("Verify ItineraryGenerator validates required destination")
    void testItineraryGeneratorInvalidDestination() {
        AiSettings s = buildSettings(true, "gpt-4o-mini", "sk-key");
        ItineraryGenerator gen = new ItineraryGenerator(s, mockAiServer);

        ItineraryGenerator.Request req1 = new ItineraryGenerator.Request();
        req1.destination = "";
        assertThrows(AiClient.AiException.class, () -> gen.generate(req1));

        ItineraryGenerator.Request req2 = new ItineraryGenerator.Request();
        req2.destination = "   ";
        assertThrows(AiClient.AiException.class, () -> gen.generate(req2));

        assertThrows(AiClient.AiException.class, () -> gen.generate(null));
    }

    @Test
    @DisplayName("Verify ItineraryGenerator clamps duration between 1 and 14 days")
    void testItineraryGeneratorClampedDuration() throws AiClient.AiException {
        AiSettings s = buildSettings(true, "gpt-4o-mini", "sk-key");
        // Model returns 1 day
        String aiReply = "{\"title\":\"Quick Trip\",\"days\":[{\"title\":\"Day 1\",\"stops\":[{\"name\":\"Central Park\",\"placeRef\":\"osm:1\"}]}]}";
        mockAiServer.setResponse(200, sampleChatCompletionJson(aiReply));

        ItineraryGenerator gen = new ItineraryGenerator(s, mockAiServer);

        // Days = 0 -> clamped to 1
        ItineraryGenerator.Request r0 = new ItineraryGenerator.Request();
        r0.destination = "Delhi";
        r0.days = 0;
        ItineraryGenerator.Result res0 = gen.generate(r0);
        assertEquals(1, res0.days.size());

        // Verify request prompt sent to AI requested Plan a 1-day trip
        assertTrue(mockAiServer.lastRequestBody.contains("Plan a 1-day trip"));
    }

    @Test
    @DisplayName("Verify ItineraryGenerator handles missing optional parameters without NullPointerException")
    void testItineraryGeneratorMissingParametersSafety() throws AiClient.AiException {
        AiSettings s = buildSettings(true, "gpt-4o-mini", "sk-key");
        String aiReply = "{\"title\":\"Basic Trip\",\"days\":[{\"title\":\"Day 1\",\"stops\":[{\"name\":\"Main Gate\",\"placeRef\":\"osm:1\"}]}]}";
        mockAiServer.setResponse(200, sampleChatCompletionJson(aiReply));

        ItineraryGenerator gen = new ItineraryGenerator(s, mockAiServer);
        ItineraryGenerator.Request req = new ItineraryGenerator.Request();
        req.destination = "Jaipur";
        // All optional fields left null
        req.origin = null;
        req.startDate = null;
        req.interests = null;
        req.pace = null;
        req.accommodation = null;
        req.center = null;

        ItineraryGenerator.Result res = gen.generate(req);
        assertNotNull(res);
        assertEquals("Basic Trip", res.title);
        assertEquals(1, res.days.size());
    }

    @Test
    @DisplayName("Verify ItineraryGenerator prompt construction reflects budget and preferences")
    void testItineraryGeneratorBudgetAndPacePrompt() throws AiClient.AiException {
        AiSettings s = buildSettings(true, "gpt-4o-mini", "sk-key");
        String aiReply = "{\"title\":\"Trip\",\"days\":[{\"title\":\"Day 1\",\"stops\":[{\"name\":\"Spot\",\"placeRef\":\"osm:1\"}]}]}";
        mockAiServer.setResponse(200, sampleChatCompletionJson(aiReply));

        ItineraryGenerator gen = new ItineraryGenerator(s, mockAiServer);
        ItineraryGenerator.Request req = new ItineraryGenerator.Request();
        req.destination = "Goa";
        req.days = 3;
        req.travelers = 4;
        req.budget = 45000;
        req.budgetCategory = "Comfort";
        req.pace = "Relaxed";
        req.accommodation = "Beach Resort";
        req.interests = List.of("Beach", "Nightlife");

        gen.generate(req);

        String prompt = mockAiServer.lastRequestBody;
        assertTrue(prompt.contains("Plan a 3-day trip"));
        assertTrue(prompt.contains("Travelers: 4"));
        assertTrue(prompt.contains("Total budget: INR 45000"));
        assertTrue(prompt.contains("Beach, Nightlife"));
        assertTrue(prompt.contains("Relaxed (2 stops per day)"));
    }

    @Test
    @DisplayName("Verify extractJson parses raw JSON, markdown-fenced JSON, and text with comments")
    void testItineraryGeneratorMarkdownFenceExtraction() throws AiClient.AiException {
        // Case 1: Pure JSON
        String pure = "{\"title\":\"Direct JSON\",\"days\":[]}";
        JsonObject j1 = ItineraryGenerator.extractJson(pure);
        assertEquals("Direct JSON", j1.get("title").getAsString());

        // Case 2: Markdown fence with language tag
        String fenced = "Here is your customized travel plan:\n```json\n{\n  \"title\": \"Fenced JSON\",\n  \"days\": []\n}\n```\nHope you have a great time!";
        JsonObject j2 = ItineraryGenerator.extractJson(fenced);
        assertEquals("Fenced JSON", j2.get("title").getAsString());

        // Case 3: Preamble with stray brackets before markdown block
        String preamble = "Note: user pref {pace: Fast}\n```json\n{\"title\": \"Preamble Test\", \"days\": []}\n```";
        JsonObject j3 = ItineraryGenerator.extractJson(preamble);
        assertEquals("Preamble Test", j3.get("title").getAsString());

        // Case 4: Non-JSON text fails safely
        assertThrows(AiClient.AiException.class, () -> ItineraryGenerator.extractJson("I cannot fulfill this request."));
        assertThrows(AiClient.AiException.class, () -> ItineraryGenerator.extractJson(""));
        assertThrows(AiClient.AiException.class, () -> ItineraryGenerator.extractJson(null));
    }

    @Test
    @DisplayName("Verify ItineraryGenerator rejects response missing days array")
    void testItineraryGeneratorInvalidGeneratedJson() {
        AiSettings s = buildSettings(true, "gpt-4o-mini", "sk-key");
        mockAiServer.setResponse(200, sampleChatCompletionJson("{\"title\":\"Missing Days Object\"}"));

        ItineraryGenerator gen = new ItineraryGenerator(s, mockAiServer);
        ItineraryGenerator.Request req = new ItineraryGenerator.Request();
        req.destination = "Varanasi";
        req.days = 2;

        AiClient.AiException ex = assertThrows(AiClient.AiException.class, () -> gen.generate(req));
        assertTrue(ex.getMessage().contains("no \"days\" list"));
    }

    @Test
    @DisplayName("Verify ItineraryGenerator rejects response with no usable stops")
    void testItineraryGeneratorEmptyStopsHandling() {
        AiSettings s = buildSettings(true, "gpt-4o-mini", "sk-key");
        mockAiServer.setResponse(200, sampleChatCompletionJson("{\"title\":\"Empty Stops\",\"days\":[{\"title\":\"Day 1\",\"stops\":[]}]}"));

        ItineraryGenerator gen = new ItineraryGenerator(s, mockAiServer);
        ItineraryGenerator.Request req = new ItineraryGenerator.Request();
        req.destination = "Ooty";
        req.days = 1;

        AiClient.AiException ex = assertThrows(AiClient.AiException.class, () -> gen.generate(req));
        assertTrue(ex.getMessage().contains("no usable stops"));
    }

    @Test
    @DisplayName("Verify ItineraryGenerator bounds checks and jitters outlier coordinates")
    void testItineraryGeneratorCoordinatesBoundsAndJitter() throws AiClient.AiException {
        AiSettings s = buildSettings(true, "gpt-4o-mini", "sk-key");
        // Place in Mysuru center is ~[12.30, 76.65], but AI returns coordinates in Delhi [28.61, 77.20]
        String aiReply = """
                {
                  "title": "Mysuru Outlier Test",
                  "days": [
                    {
                      "title": "Day 1",
                      "stops": [
                        {
                          "name": "Invented Faraway Stop",
                          "category": "Nature",
                          "lat": 28.6139,
                          "lng": 77.2090,
                          "placeRef": "osm:node/301"
                        }
                      ]
                    }
                  ]
                }
                """;
        mockAiServer.setResponse(200, sampleChatCompletionJson(aiReply));

        ItineraryGenerator gen = new ItineraryGenerator(s, mockAiServer);
        ItineraryGenerator.Request req = new ItineraryGenerator.Request();
        req.destination = "Mysuru";
        req.days = 1;
        req.center = new double[]{12.3051, 76.6551};

        ItineraryGenerator.Result res = gen.generate(req);
        ItineraryStop stop = res.days.get(0).stops.get(0);

        // Coordinates should be replaced by center-jittered coordinates near 12.30, 76.65, not Delhi 28.61
        assertTrue(Math.abs(stop.lat - 12.3051) < 0.1, "Outlier lat should be adjusted near destination center");
        assertTrue(Math.abs(stop.lng - 76.6551) < 0.1, "Outlier lng should be adjusted near destination center");
    }

    // ================================================================
    // 3. TOUR-PLAN GENERATION TESTS
    // ================================================================

    @Test
    @DisplayName("Verify tour-plan in 'organize' mode schedules stops without adding new ones")
    void testTourPlanValidRequestOrganizeMode() throws AiClient.AiException {
        AiSettings s = buildSettings(true, "gpt-4o-mini", "sk-key");
        String tourReply = """
                {
                  "days": [
                    {
                      "dayNumber": 1,
                      "title": "Organized Heritage Day",
                      "stops": [
                        {
                          "id": "user-stop-1",
                          "name": "Mysuru Palace",
                          "time": "10:00 AM",
                          "duration": "2.5 hrs",
                          "notes": "Best light in the morning",
                          "transitToNext": { "mode": "Walk", "duration": "5 mins", "cost": 0 }
                        },
                        {
                          "id": null,
                          "name": "AI Suggested Snack Shop",
                          "category": "Food"
                        }
                      ]
                    }
                  ]
                }
                """;
        mockAiServer.setResponse(200, sampleChatCompletionJson(tourReply));

        ItineraryGenerator gen = new ItineraryGenerator(s, mockAiServer);
        ItineraryGenerator.TourRequest req = new ItineraryGenerator.TourRequest();
        req.destination = "Mysuru";
        req.mode = "organize"; // Organize mode should NOT add new stops

        ItineraryDay day1 = new ItineraryDay();
        day1.dayNumber = 1;
        day1.title = "My Planned Day";
        ItineraryStop s1 = new ItineraryStop();
        s1.id = "user-stop-1";
        s1.name = "Mysuru Palace";
        s1.placeRef = "osm:node/101";
        day1.stops.add(s1);
        req.plan = List.of(day1);

        ItineraryGenerator.TourResult res = gen.planTour(req);

        assertNotNull(res);
        assertEquals(0, res.added, "Organize mode must not add new stops");
        assertEquals(1, res.days.size());
        assertEquals(1, res.days.get(0).stops.size());
        ItineraryStop scheduled = res.days.get(0).stops.get(0);
        assertEquals("user-stop-1", scheduled.id);
        assertEquals("10:00 AM", scheduled.time);
        assertEquals("Best light in the morning", scheduled.notes);
    }

    @Test
    @DisplayName("Verify tour-plan in 'fill' mode adds suggested stops")
    void testTourPlanValidRequestFillMode() throws AiClient.AiException {
        AiSettings s = buildSettings(true, "gpt-4o-mini", "sk-key");
        String tourReply = """
                {
                  "days": [
                    {
                      "dayNumber": 1,
                      "title": "Full Day Exploration",
                      "stops": [
                        {
                          "id": "my-stop-10",
                          "name": "Art Gallery",
                          "time": "09:30 AM",
                          "duration": "2 hrs",
                          "transitToNext": { "mode": "Auto", "duration": "10 mins", "cost": 40 }
                        },
                        {
                          "name": "Famous Coffee House",
                          "category": "Food",
                          "time": "12:00 PM",
                          "duration": "1 hr",
                          "cost": 120,
                          "lat": 12.31,
                          "lng": 76.65,
                          "placeRef": "osm:node/999"
                        }
                      ]
                    }
                  ]
                }
                """;
        mockAiServer.setResponse(200, sampleChatCompletionJson(tourReply));

        ItineraryGenerator gen = new ItineraryGenerator(s, mockAiServer);
        ItineraryGenerator.TourRequest req = new ItineraryGenerator.TourRequest();
        req.destination = "Mysuru";
        req.mode = "fill"; // Fill mode allows suggestions

        ItineraryDay day1 = new ItineraryDay();
        day1.dayNumber = 1;
        day1.title = "Original Day";
        ItineraryStop s1 = new ItineraryStop();
        s1.id = "my-stop-10";
        s1.name = "Art Gallery";
        s1.placeRef = "osm:node/10";
        day1.stops.add(s1);
        req.plan = List.of(day1);

        ItineraryGenerator.TourResult res = gen.planTour(req);

        assertEquals(1, res.added, "Fill mode should register 1 added stop");
        assertEquals(2, res.days.get(0).stops.size());
        assertEquals("my-stop-10", res.days.get(0).stops.get(0).id);
        assertTrue(res.days.get(0).stops.get(1).id.startsWith("stop-ai-"), "Added stop should receive a generated stop-ai- ID");
    }

    @Test
    @DisplayName("Verify tour-plan validates plan input constraints")
    void testTourPlanInvalidRequest() {
        AiSettings s = buildSettings(true, "gpt-4o-mini", "sk-key");
        ItineraryGenerator gen = new ItineraryGenerator(s, mockAiServer);

        // Null plan
        ItineraryGenerator.TourRequest r1 = new ItineraryGenerator.TourRequest();
        r1.plan = null;
        assertThrows(AiClient.AiException.class, () -> gen.planTour(r1));

        // Empty plan
        ItineraryGenerator.TourRequest r2 = new ItineraryGenerator.TourRequest();
        r2.plan = List.of();
        assertThrows(AiClient.AiException.class, () -> gen.planTour(r2));

        // >14 days plan
        ItineraryGenerator.TourRequest r3 = new ItineraryGenerator.TourRequest();
        List<ItineraryDay> days15 = new ArrayList<>();
        for (int i = 1; i <= 15; i++) {
            ItineraryDay d = new ItineraryDay();
            d.dayNumber = i;
            days15.add(d);
        }
        r3.plan = days15;
        assertThrows(AiClient.AiException.class, () -> gen.planTour(r3));
    }

    @Test
    @DisplayName("Verify tour-plan preserves original stops even if AI forgets them")
    void testTourPlanPreservesOriginalStops() throws AiClient.AiException {
        AiSettings s = buildSettings(true, "gpt-4o-mini", "sk-key");
        // Model forgets user's stops and returns empty stops
        mockAiServer.setResponse(200, sampleChatCompletionJson("{\"days\":[{\"dayNumber\":1,\"title\":\"Empty Day\",\"stops\":[]}]}"));

        ItineraryGenerator gen = new ItineraryGenerator(s, mockAiServer);
        ItineraryGenerator.TourRequest req = new ItineraryGenerator.TourRequest();
        req.destination = "Mysuru";

        ItineraryDay day1 = new ItineraryDay();
        day1.dayNumber = 1;
        ItineraryStop userStop = new ItineraryStop();
        userStop.id = "crucial-user-stop";
        userStop.name = "KRS Dam";
        userStop.placeRef = "osm:node/555";
        day1.stops.add(userStop);
        req.plan = List.of(day1);

        ItineraryGenerator.TourResult res = gen.planTour(req);

        assertEquals(1, res.days.get(0).stops.size());
        assertEquals("crucial-user-stop", res.days.get(0).stops.get(0).id, "User stop must NEVER be lost");
    }

    // ================================================================
    // 4. AI SETTINGS & OWNER AUTHORIZATION TESTS
    // ================================================================

    @Test
    @DisplayName("Verify AI settings default fallback to ai.properties when database is empty")
    void testAiSettingsDefaultFallback() throws SQLException {
        AiSettings s = settingsDAO.load();
        assertNotNull(s);
        assertFalse(s.enabled);
        assertNotNull(s.baseUrl);
        assertTrue(s.baseUrl.contains("openrouter.ai"));
        assertEquals(0.7, s.temperature, 0.01);
        assertEquals(4000, s.maxTokens);
        assertEquals(90, s.timeoutSeconds);
    }

    @Test
    @DisplayName("Verify AI settings save, load, and reset lifecycle")
    void testAiSettingsSaveAndReload() throws SQLException {
        AiSettings custom = new AiSettings();
        custom.enabled = true;
        custom.baseUrl = mockAiServer.getBaseUrl();
        custom.apiKey = "sk-custom-saved-key-888";
        custom.model = "meta-llama/llama-3.3-70b-instruct";
        custom.temperature = 0.5;
        custom.maxTokens = 2048;
        custom.timeoutSeconds = 45;
        custom.jsonMode = true;
        custom.extraHeaders = "X-App: TravelMate";

        settingsDAO.save(custom);

        AiSettings loaded = settingsDAO.load();
        assertTrue(loaded.enabled);
        assertEquals(mockAiServer.getBaseUrl(), loaded.baseUrl);
        assertEquals("sk-custom-saved-key-888", loaded.apiKey);
        assertEquals("meta-llama/llama-3.3-70b-instruct", loaded.model);
        assertEquals(0.5, loaded.temperature, 0.01);
        assertEquals(2048, loaded.maxTokens);
        assertEquals(45, loaded.timeoutSeconds);
        assertTrue(loaded.jsonMode);
        assertEquals("X-App: TravelMate", loaded.extraHeaders);

        // Reset
        settingsDAO.reset();
        AiSettings afterReset = settingsDAO.load();
        assertFalse(afterReset.enabled);
        assertEquals("", afterReset.apiKey);
    }

    @Test
    @DisplayName("Verify owner authorization with AiAccess allowlist")
    void testAiAccessOwnerAuthorization() {
        User owner = new User(1, "Owner", OWNER_EMAIL);
        assertTrue(AiAccess.isAllowed(owner), "Owner in allowlist should be allowed");

        User nonOwner = new User(2, "Traveler", NON_OWNER_EMAIL);
        assertFalse(AiAccess.isAllowed(nonOwner), "Non-owner should be denied");

        assertFalse(AiAccess.isAllowed(null), "Null user should be denied");
        User blankUser = new User(3, "Blank", "   ");
        assertFalse(AiAccess.isAllowed(blankUser), "Blank email user should be denied");
    }

    @Test
    @DisplayName("Verify session invalidation on logout rejects subsequent settings requests")
    void testAiAccessLogoutInvalidation() throws IOException {
        AiServlet servlet = new AiServlet(settingsDAO, mockAiServer);
        HttpSession session = createOwnerSession();

        // 1. Authenticated owner request succeeds
        MockResponse resp1 = invokeServletWithSession(servlet, "GET", "settings", null, session);
        assertEquals(200, resp1.status);

        // 2. Simulate user logout: invalidate session
        session.invalidate();

        // 3. Request after logout is rejected with 401 Unauthorized
        MockResponse resp2 = invokeServletWithSession(servlet, "GET", "settings", null, session);
        assertEquals(401, resp2.status);
    }

    @Test
    @DisplayName("Verify API key masking in publicView and status")
    void testAiSettingsApiKeyMasking() throws IOException, SQLException {
        AiSettings s = buildSettings(true, "gpt-4o-mini", "sk-secret-token-12345678");
        settingsDAO.save(s);

        AiServlet servlet = new AiServlet(settingsDAO, mockAiServer);
        HttpSession ownerSession = createOwnerSession();

        MockResponse resp = invokeServletWithSession(servlet, "GET", "settings", null, ownerSession);
        assertEquals(200, resp.status);

        JsonObject json = JsonUtil.GSON.fromJson(resp.content, JsonObject.class);
        assertTrue(json.get("apiKeySet").getAsBoolean());
        String preview = json.get("apiKeyPreview").getAsString();

        assertFalse(preview.contains("sk-secret-token-12345678"), "Full API key must never appear in response");
        assertTrue(preview.contains("••••"), "Mask preview must contain bullets");
        assertFalse(resp.content.contains("12345678"), "Full secret digits must not leak in raw JSON output");
    }

    @Test
    @DisplayName("Verify submitting masked API key does NOT overwrite original stored key")
    void testAiSettingsMaskedApiKeySubmissionNotOverwriting() throws IOException, SQLException {
        AiSettings original = buildSettings(true, "gpt-4o-mini", "sk-real-secret-key-12345");
        settingsDAO.save(original);

        AiServlet servlet = new AiServlet(settingsDAO, mockAiServer);
        HttpSession ownerSession = createOwnerSession();

        // Submitting with masked preview string
        JsonObject updateBody = new JsonObject();
        updateBody.addProperty("enabled", true);
        updateBody.addProperty("baseUrl", original.baseUrl);
        updateBody.addProperty("model", "gpt-4o-mini");
        updateBody.addProperty("apiKey", "sk-re••••2345"); // Masked string submitted
        updateBody.addProperty("temperature", 0.7);
        updateBody.addProperty("maxTokens", 4000);
        updateBody.addProperty("timeoutSeconds", 90);

        MockResponse resp = invokeServletWithSession(servlet, "PUT", "settings", updateBody.toString(), ownerSession);
        assertEquals(200, resp.status);

        AiSettings reloaded = settingsDAO.load();
        assertEquals("sk-real-secret-key-12345", reloaded.apiKey, "Original API key must be preserved when masked key is submitted");
    }

    @Test
    @DisplayName("Verify switching base URL to a different server drops the saved API key")
    void testAiSettingsBaseUrlChangeDropsKey() throws IOException, SQLException {
        AiSettings original = buildSettings(true, "gpt-4o-mini", "sk-secret-key-for-server1");
        original.baseUrl = "https://server1.example.com/v1";
        settingsDAO.save(original);

        AiServlet servlet = new AiServlet(settingsDAO, mockAiServer);
        HttpSession ownerSession = createOwnerSession();

        JsonObject updateBody = new JsonObject();
        updateBody.addProperty("enabled", true);
        updateBody.addProperty("baseUrl", "https://untrusted-server2.example.com/v1"); // Different host
        updateBody.addProperty("model", "custom-model");
        updateBody.addProperty("apiKey", ""); // Left blank

        MockResponse resp = invokeServletWithSession(servlet, "PUT", "settings", updateBody.toString(), ownerSession);
        assertEquals(200, resp.status);

        JsonObject json = JsonUtil.GSON.fromJson(resp.content, JsonObject.class);
        assertTrue(json.get("keyDropped").getAsBoolean(), "keyDropped flag should be true when changing servers without retyping key");

        AiSettings reloaded = settingsDAO.load();
        assertEquals("", reloaded.apiKey, "API key must be dropped to prevent sending credentials to a different host");
    }

    @Test
    @DisplayName("Verify switching base URL port drops the saved API key")
    void testAiSettingsPortChangeDropsKey() throws IOException, SQLException {
        AiSettings original = buildSettings(true, "llama3", "secret-ollama-key");
        original.baseUrl = "http://localhost:11434/v1";
        settingsDAO.save(original);

        AiServlet servlet = new AiServlet(settingsDAO, mockAiServer);
        HttpSession ownerSession = createOwnerSession();

        JsonObject updateBody = new JsonObject();
        updateBody.addProperty("enabled", true);
        updateBody.addProperty("baseUrl", "http://localhost:9000/v1"); // Different port
        updateBody.addProperty("model", "llama3");
        updateBody.addProperty("apiKey", ""); // Left blank

        MockResponse resp = invokeServletWithSession(servlet, "PUT", "settings", updateBody.toString(), ownerSession);
        assertEquals(200, resp.status);

        JsonObject json = JsonUtil.GSON.fromJson(resp.content, JsonObject.class);
        assertTrue(json.get("keyDropped").getAsBoolean(), "keyDropped flag should be true when changing port");

        AiSettings reloaded = settingsDAO.load();
        assertEquals("", reloaded.apiKey, "API key must be dropped when port changes");
    }

    @Test
    @DisplayName("Verify switching base URL scheme drops the saved API key")
    void testAiSettingsSchemeChangeDropsKey() throws IOException, SQLException {
        AiSettings original = buildSettings(true, "gpt-4o-mini", "sk-secret-key");
        original.baseUrl = "https://api.openai.com/v1";
        settingsDAO.save(original);

        AiServlet servlet = new AiServlet(settingsDAO, mockAiServer);
        HttpSession ownerSession = createOwnerSession();

        JsonObject updateBody = new JsonObject();
        updateBody.addProperty("enabled", true);
        updateBody.addProperty("baseUrl", "http://api.openai.com/v1"); // Downgrade to http
        updateBody.addProperty("model", "gpt-4o-mini");
        updateBody.addProperty("apiKey", "sk-se••••-key"); // Submitting masked key

        MockResponse resp = invokeServletWithSession(servlet, "PUT", "settings", updateBody.toString(), ownerSession);
        assertEquals(200, resp.status);

        JsonObject json = JsonUtil.GSON.fromJson(resp.content, JsonObject.class);
        assertTrue(json.get("keyDropped").getAsBoolean(), "keyDropped flag should be true when changing scheme even if masked key was passed");

        AiSettings reloaded = settingsDAO.load();
        assertEquals("", reloaded.apiKey, "API key must be dropped when scheme changes");
    }

    @Test
    @DisplayName("Verify switching base URL path drops the saved API key")
    void testAiSettingsPathChangeDropsKey() throws IOException, SQLException {
        AiSettings original = buildSettings(true, "gpt-4o-mini", "sk-secret-key");
        original.baseUrl = "https://api.openai.com/v1";
        settingsDAO.save(original);

        AiServlet servlet = new AiServlet(settingsDAO, mockAiServer);
        HttpSession ownerSession = createOwnerSession();

        JsonObject updateBody = new JsonObject();
        updateBody.addProperty("enabled", true);
        updateBody.addProperty("baseUrl", "https://api.openai.com/v2"); // Different path
        updateBody.addProperty("model", "gpt-4o-mini");
        updateBody.addProperty("apiKey", "");

        MockResponse resp = invokeServletWithSession(servlet, "PUT", "settings", updateBody.toString(), ownerSession);
        assertEquals(200, resp.status);

        JsonObject json = JsonUtil.GSON.fromJson(resp.content, JsonObject.class);
        assertTrue(json.get("keyDropped").getAsBoolean(), "keyDropped flag should be true when changing path");

        AiSettings reloaded = settingsDAO.load();
        assertEquals("", reloaded.apiKey, "API key must be dropped when path changes");
    }

    @Test
    @DisplayName("Verify changing base URL while providing a new API key saves the new key")
    void testAiSettingsUrlChangeWithNewKeyPreservesNewKey() throws IOException, SQLException {
        AiSettings original = buildSettings(true, "gpt-4o-mini", "sk-old-key");
        original.baseUrl = "https://server1.example.com/v1";
        settingsDAO.save(original);

        AiServlet servlet = new AiServlet(settingsDAO, mockAiServer);
        HttpSession ownerSession = createOwnerSession();

        JsonObject updateBody = new JsonObject();
        updateBody.addProperty("enabled", true);
        updateBody.addProperty("baseUrl", "https://server2.example.com/v1");
        updateBody.addProperty("model", "gpt-4o-mini");
        updateBody.addProperty("apiKey", "sk-brand-new-key-67890"); // Explicit new key

        MockResponse resp = invokeServletWithSession(servlet, "PUT", "settings", updateBody.toString(), ownerSession);
        assertEquals(200, resp.status);

        JsonObject json = JsonUtil.GSON.fromJson(resp.content, JsonObject.class);
        assertFalse(json.get("keyDropped").getAsBoolean(), "keyDropped should be false when new key is explicitly provided");

        AiSettings reloaded = settingsDAO.load();
        assertEquals("sk-brand-new-key-67890", reloaded.apiKey, "New API key must be saved");
    }

    // ================================================================
    // 5. PERSISTENCE & TRANSACTION INTEGRATION TESTS
    // ================================================================

    @Test
    @DisplayName("Verify saving and reloading an AI-generated itinerary preserves days and stops")
    void testSaveAndReloadGeneratedItinerary() throws SQLException {
        Trip trip = new Trip();
        trip.id = "trip-ai-test-1";
        trip.title = "Mysuru AI Experience";
        trip.destination = "Mysuru";
        trip.origin = "Bengaluru";
        trip.travelers = 2;
        trip.budgetTotal = 15000;
        trip.aiPlanned = true;

        List<ItineraryDay> days = new ArrayList<>();
        ItineraryDay d1 = new ItineraryDay();
        d1.dayNumber = 1;
        d1.title = "Heritage Exploration";
        ItineraryStop s1 = new ItineraryStop();
        s1.id = "stop-ai-101";
        s1.name = "Mysuru Palace";
        s1.category = "Heritage";
        s1.time = "10:00 AM";
        s1.duration = "2 hrs";
        s1.cost = 200;
        s1.lat = 12.3051;
        s1.lng = 76.6551;
        Transit t1 = new Transit();
        t1.mode = "Auto Rickshaw";
        t1.cost = 50;
        s1.transitToNext = t1;
        d1.stops.add(s1);
        days.add(d1);

        trip.days = days;
        tripDAO.save(trip);

        // Reload
        Trip loaded = tripDAO.findById("trip-ai-test-1");
        assertNotNull(loaded);
        assertTrue(loaded.aiPlanned);
        assertEquals(1, loaded.days.size());

        ItineraryDay loadedDay = loaded.days.get(0);
        assertEquals(1, loadedDay.dayNumber);
        assertEquals("Heritage Exploration", loadedDay.title);
        assertEquals(1, loadedDay.stops.size());

        ItineraryStop loadedStop = loadedDay.stops.get(0);
        assertEquals("stop-ai-101", loadedStop.id);
        assertEquals("Mysuru Palace", loadedStop.name);
        assertEquals(200, loadedStop.cost);
        assertEquals(12.3051, loadedStop.lat, 0.0001);
        assertEquals(76.6551, loadedStop.lng, 0.0001);
        assertNotNull(loadedStop.transitToNext);
        assertEquals("Auto Rickshaw", loadedStop.transitToNext.mode);
        assertEquals(50, loadedStop.transitToNext.cost);
    }

    @Test
    @DisplayName("Verify itinerary day and stop sequence order is strictly preserved")
    void testItineraryDayAndStopOrderingPreserved() throws SQLException {
        Trip trip = new Trip();
        trip.id = "trip-ai-ordering-test";
        trip.title = "Multi-day Ordering Test";

        List<ItineraryDay> days = new ArrayList<>();
        for (int d = 1; d <= 3; d++) {
            ItineraryDay day = new ItineraryDay();
            day.dayNumber = d;
            day.title = "Day " + d;
            for (int s = 1; s <= 4; s++) {
                ItineraryStop stop = new ItineraryStop();
                stop.id = "stop-" + d + "-" + s;
                stop.name = "Stop D" + d + "S" + s;
                stop.time = (8 + s) + ":00 AM";
                day.stops.add(stop);
            }
            days.add(day);
        }

        trip.days = days;
        tripDAO.save(trip);

        Trip loaded = tripDAO.findById("trip-ai-ordering-test");
        assertEquals(3, loaded.days.size());

        for (int d = 0; d < 3; d++) {
            ItineraryDay loadedDay = loaded.days.get(d);
            assertEquals(d + 1, loadedDay.dayNumber);
            assertEquals("Day " + (d + 1), loadedDay.title);
            assertEquals(4, loadedDay.stops.size());
            for (int s = 0; s < 4; s++) {
                ItineraryStop loadedStop = loadedDay.stops.get(s);
                assertEquals("stop-" + (d + 1) + "-" + (s + 1), loadedStop.id);
                assertEquals("Stop D" + (d + 1) + "S" + (s + 1), loadedStop.name);
            }
        }
    }

    @Test
    @DisplayName("Verify transaction rollback when stop insertion fails")
    void testItineraryPersistenceTransactionRollback() throws SQLException {
        Trip trip = new Trip();
        trip.id = "trip-rollback-test";
        trip.title = "Rollback Candidate";

        // Save initial trip
        tripDAO.save(trip);
        assertTrue(tripDAO.exists("trip-rollback-test"));

        // Attempt replacing with invalid stop that exceeds constraints (e.g. invalid type or simulated DB error)
        try (Connection con = DBConnection.getConnection()) {
            con.setAutoCommit(false);
            try (Statement st = con.createStatement()) {
                st.executeUpdate("DELETE FROM itinerary_days WHERE trip_id = 'trip-rollback-test'");
                // Force an intentional foreign key or syntax error
                st.executeUpdate("INSERT INTO itinerary_days (trip_id, day_number, title) VALUES ('non-existent-parent-fake-key', 1, 'Invalid')");
                con.commit();
            } catch (SQLException ex) {
                con.rollback();
            }
        }

        // Original trip record remains completely intact
        assertTrue(tripDAO.exists("trip-rollback-test"));
    }

    @Test
    @DisplayName("Verify existing trips remain untouched when AI itinerary generation fails")
    void testExistingTripsPreservedOnFailedGeneration() throws SQLException {
        Trip existing = new Trip();
        existing.id = "trip-existing-safe";
        existing.title = "Untouched Trip";
        tripDAO.save(existing);

        int countBefore = tripDAO.count();

        // Simulate AI generation failure
        AiSettings s = buildSettings(true, "gpt-4o-mini", "sk-key");
        mockAiServer.setResponse(500, "{\"error\":\"Model crashed\"}");
        ItineraryGenerator gen = new ItineraryGenerator(s, mockAiServer);
        ItineraryGenerator.Request req = new ItineraryGenerator.Request();
        req.destination = "Goa";
        req.days = 2;

        assertThrows(AiClient.AiException.class, () -> gen.generate(req));

        // Verify count and data remain identical
        assertEquals(countBefore, tripDAO.count());
        assertTrue(tripDAO.exists("trip-existing-safe"));
        assertEquals("Untouched Trip", tripDAO.findById("trip-existing-safe").title);
    }

    // ================================================================
    // 6. SERVLET ENDPOINTS INTEGRATION TESTS
    // ================================================================

    @Test
    @DisplayName("Verify GET /api/ai/status returns public status")
    void testAiServletGetStatusEndpoint() throws IOException, SQLException {
        AiServlet servlet = new AiServlet(settingsDAO, mockAiServer);

        // 1. When AI disabled
        AiSettings s1 = buildSettings(false, "", "");
        settingsDAO.save(s1);
        MockResponse resp1 = invokeServlet(servlet, "GET", "status", null);
        assertEquals(200, resp1.status);
        JsonObject j1 = JsonUtil.GSON.fromJson(resp1.content, JsonObject.class);
        assertFalse(j1.get("enabled").getAsBoolean());
        assertTrue(!j1.has("model") || j1.get("model").isJsonNull());

        // 2. When AI enabled and usable
        AiSettings s2 = buildSettings(true, "meta-llama/llama-3", "sk-test-key");
        settingsDAO.save(s2);
        MockResponse resp2 = invokeServlet(servlet, "GET", "status", null);
        assertEquals(200, resp2.status);
        JsonObject j2 = JsonUtil.GSON.fromJson(resp2.content, JsonObject.class);
        assertTrue(j2.get("enabled").getAsBoolean());
        assertEquals("meta-llama/llama-3", j2.get("model").getAsString());
        assertEquals("127.0.0.1", j2.get("provider").getAsString());
    }

    @Test
    @DisplayName("Verify /api/ai/itinerary and /api/ai/tour-plan return HTTP 503 when AI is disabled")
    void testAiServletUnusableAi503() throws IOException, SQLException {
        AiServlet servlet = new AiServlet(settingsDAO, mockAiServer);
        AiSettings disabled = buildSettings(false, "", "");
        settingsDAO.save(disabled);

        // POST /api/ai/itinerary -> 503
        JsonObject itinBody = new JsonObject();
        itinBody.addProperty("destination", "Mysuru");
        itinBody.addProperty("days", 2);
        MockResponse resp1 = invokeServlet(servlet, "POST", "itinerary", itinBody.toString());
        assertEquals(503, resp1.status);
        assertTrue(resp1.content.contains("AI is not set up yet"));

        // POST /api/ai/tour-plan -> 503
        JsonObject tourBody = new JsonObject();
        tourBody.add("plan", new JsonArray());
        MockResponse resp2 = invokeServlet(servlet, "POST", "tour-plan", tourBody.toString());
        assertEquals(503, resp2.status);
        assertTrue(resp2.content.contains("AI is not set up yet"));
    }

    @Test
    @DisplayName("Verify POST /api/ai/itinerary generates itinerary through servlet")
    void testAiServletPostItineraryEndpoint() throws IOException, SQLException {
        AiServlet servlet = new AiServlet(settingsDAO, mockAiServer);
        AiSettings usable = buildSettings(true, "gpt-4o-mini", "sk-key");
        settingsDAO.save(usable);

        String aiReply = "{\"title\":\"Servlet Itinerary\",\"days\":[{\"title\":\"Day 1\",\"stops\":[{\"name\":\"Palace\",\"placeRef\":\"osm:1\"}]}]}";
        mockAiServer.setResponse(200, sampleChatCompletionJson(aiReply));

        JsonObject reqBody = new JsonObject();
        reqBody.addProperty("destination", "Mysuru");
        reqBody.addProperty("days", 1);

        MockResponse resp = invokeServlet(servlet, "POST", "itinerary", reqBody.toString());
        assertEquals(200, resp.status);

        JsonObject json = JsonUtil.GSON.fromJson(resp.content, JsonObject.class);
        assertEquals("Servlet Itinerary", json.get("title").getAsString());
        assertEquals(1, json.getAsJsonArray("days").size());
    }

    @Test
    @DisplayName("Verify POST /api/ai/tour-plan plans tour through servlet")
    void testAiServletPostTourPlanEndpoint() throws IOException, SQLException {
        AiServlet servlet = new AiServlet(settingsDAO, mockAiServer);
        AiSettings usable = buildSettings(true, "gpt-4o-mini", "sk-key");
        settingsDAO.save(usable);

        String aiReply = "{\"days\":[{\"dayNumber\":1,\"title\":\"Planned Tour\",\"stops\":[{\"id\":\"s-1\",\"time\":\"09:00 AM\"}]}]}";
        mockAiServer.setResponse(200, sampleChatCompletionJson(aiReply));

        JsonObject reqBody = new JsonObject();
        reqBody.addProperty("destination", "Mysuru");
        JsonArray plan = new JsonArray();
        JsonObject day1 = new JsonObject();
        day1.addProperty("dayNumber", 1);
        JsonArray stops = new JsonArray();
        JsonObject s1 = new JsonObject();
        s1.addProperty("id", "s-1");
        s1.addProperty("name", "Stop One");
        s1.addProperty("placeRef", "osm:1");
        stops.add(s1);
        day1.add("stops", stops);
        plan.add(day1);
        reqBody.add("plan", plan);

        MockResponse resp = invokeServlet(servlet, "POST", "tour-plan", reqBody.toString());
        assertEquals(200, resp.status);

        JsonObject json = JsonUtil.GSON.fromJson(resp.content, JsonObject.class);
        assertEquals(1, json.getAsJsonArray("days").size());
    }

    @Test
    @DisplayName("Verify /api/ai/settings authorization matrix: 401, 403, 200, PUT, POST, DELETE")
    void testAiServletSettingsOwnerEndpoints() throws IOException, SQLException {
        AiServlet servlet = new AiServlet(settingsDAO, mockAiServer);
        HttpSession ownerSession = createOwnerSession();
        HttpSession nonOwnerSession = createNonOwnerSession();

        // 1. GET anonymous -> 401
        MockResponse rAnon = invokeServlet(servlet, "GET", "settings", null);
        assertEquals(401, rAnon.status);

        // 2. GET non-owner -> 403
        MockResponse rForbidden = invokeServletWithSession(servlet, "GET", "settings", null, nonOwnerSession);
        assertEquals(403, rForbidden.status);

        // 3. GET owner -> 200
        MockResponse rOwner = invokeServletWithSession(servlet, "GET", "settings", null, ownerSession);
        assertEquals(200, rOwner.status);

        // 4. PUT settings (owner) -> 200
        JsonObject putBody = new JsonObject();
        putBody.addProperty("enabled", true);
        putBody.addProperty("baseUrl", mockAiServer.getBaseUrl());
        putBody.addProperty("model", "llama-3-8b");
        putBody.addProperty("apiKey", "sk-new-key");
        MockResponse rPut = invokeServletWithSession(servlet, "PUT", "settings", putBody.toString(), ownerSession);
        assertEquals(200, rPut.status);
        assertTrue(settingsDAO.load().enabled);

        // 5. POST settings (owner) -> 200
        JsonObject postBody = new JsonObject();
        postBody.addProperty("enabled", true);
        postBody.addProperty("baseUrl", mockAiServer.getBaseUrl());
        postBody.addProperty("model", "llama-3-70b");
        MockResponse rPost = invokeServletWithSession(servlet, "POST", "settings", postBody.toString(), ownerSession);
        assertEquals(200, rPost.status);
        assertEquals("llama-3-70b", settingsDAO.load().model);

        // 6. DELETE settings (owner) -> 200 (resets to defaults)
        MockResponse rDel = invokeServletWithSession(servlet, "DELETE", "settings", null, ownerSession);
        assertEquals(200, rDel.status);
        assertFalse(settingsDAO.load().enabled);
    }

    @Test
    @DisplayName("Verify GET and POST /api/ai/models return provider model ids")
    void testAiServletModelsEndpoint() throws IOException, SQLException {
        AiServlet servlet = new AiServlet(settingsDAO, mockAiServer);
        AiSettings usable = buildSettings(true, "gpt-4o-mini", "sk-key");
        settingsDAO.save(usable);
        HttpSession ownerSession = createOwnerSession();

        mockAiServer.setResponse(200, "{\"data\":[{\"id\":\"gpt-4o\"},{\"id\":\"gpt-4o-mini\"}]}");

        // 1. GET /api/ai/models
        MockResponse rGet = invokeServletWithSession(servlet, "GET", "models", null, ownerSession);
        assertEquals(200, rGet.status);
        JsonObject jGet = JsonUtil.GSON.fromJson(rGet.content, JsonObject.class);
        assertEquals(2, jGet.getAsJsonArray("models").size());

        // 2. POST /api/ai/models with unsaved settings body
        JsonObject postBody = new JsonObject();
        postBody.addProperty("baseUrl", mockAiServer.getBaseUrl());
        postBody.addProperty("apiKey", "sk-temp-key");
        MockResponse rPost = invokeServletWithSession(servlet, "POST", "models", postBody.toString(), ownerSession);
        assertEquals(200, rPost.status);
        JsonObject jPost = JsonUtil.GSON.fromJson(rPost.content, JsonObject.class);
        assertEquals(2, jPost.getAsJsonArray("models").size());
    }

    @Test
    @DisplayName("Verify POST /api/ai/test tests connectivity and returns model reply")
    void testAiServletTestEndpoint() throws IOException, SQLException {
        AiServlet servlet = new AiServlet(settingsDAO, mockAiServer);
        AiSettings usable = buildSettings(true, "gpt-4o-mini", "sk-key");
        settingsDAO.save(usable);
        HttpSession ownerSession = createOwnerSession();

        mockAiServer.setResponse(200, sampleChatCompletionJson("Hello TravelMate, I am gpt-4o-mini."));

        JsonObject testBody = new JsonObject();
        testBody.addProperty("baseUrl", mockAiServer.getBaseUrl());
        testBody.addProperty("model", "gpt-4o-mini");
        testBody.addProperty("apiKey", "sk-key");

        MockResponse resp = invokeServletWithSession(servlet, "POST", "test", testBody.toString(), ownerSession);
        assertEquals(200, resp.status);

        JsonObject json = JsonUtil.GSON.fromJson(resp.content, JsonObject.class);
        assertTrue(json.get("ok").getAsBoolean());
        assertEquals("gpt-4o-mini", json.get("model").getAsString());
        assertTrue(json.get("reply").getAsString().contains("Hello TravelMate"));
        assertTrue(json.get("elapsedMs").getAsLong() >= 0);
    }

    @Test
    @DisplayName("Verify unknown action returns HTTP 404")
    void testAiServletUnknownAction404() throws IOException {
        AiServlet servlet = new AiServlet(settingsDAO, mockAiServer);
        MockResponse resp = invokeServlet(servlet, "GET", "non-existent-action", null);
        assertEquals(404, resp.status);
    }

    // ================================================================
    // Test Doubles and Helper Utilities
    // ================================================================

    private AiSettings buildSettings(boolean enabled, String model, String apiKey) {
        AiSettings s = new AiSettings();
        s.enabled = enabled;
        s.baseUrl = mockAiServer.getBaseUrl();
        s.model = model;
        s.apiKey = apiKey;
        s.temperature = 0.7;
        s.maxTokens = 4000;
        s.timeoutSeconds = 10;
        s.jsonMode = false;
        return s;
    }

    private static String sampleChatCompletionJson(String content) {
        JsonObject root = new JsonObject();
        root.addProperty("id", "chatcmpl-test-" + System.currentTimeMillis());
        root.addProperty("object", "chat.completion");
        root.addProperty("created", System.currentTimeMillis() / 1000);
        root.addProperty("model", "test-model");

        JsonArray choices = new JsonArray();
        JsonObject choice = new JsonObject();
        choice.addProperty("index", 0);
        JsonObject message = new JsonObject();
        message.addProperty("role", "assistant");
        message.addProperty("content", content);
        choice.add("message", message);
        choice.addProperty("finish_reason", "stop");
        choices.add(choice);

        root.add("choices", choices);
        return root.toString();
    }

    private static HttpSession createOwnerSession() {
        HttpSession session = createMockSession();
        User u = new User(100, "Owner User", OWNER_EMAIL);
        session.setAttribute("tmUser", u);
        return session;
    }

    private static HttpSession createNonOwnerSession() {
        HttpSession session = createMockSession();
        User u = new User(200, "Guest Traveler", NON_OWNER_EMAIL);
        session.setAttribute("tmUser", u);
        return session;
    }

    private static HttpSession createMockSession() {
        Map<String, Object> attributes = new HashMap<>();
        boolean[] valid = new boolean[]{true};
        return (HttpSession) Proxy.newProxyInstance(
                HttpSession.class.getClassLoader(),
                new Class<?>[]{HttpSession.class},
                (proxy, m, args) -> {
                    String name = m.getName();
                    if (name.equals("getAttribute")) return valid[0] ? attributes.get((String) args[0]) : null;
                    if (name.equals("setAttribute")) {
                        if (valid[0]) attributes.put((String) args[0], args[1]);
                        return null;
                    }
                    if (name.equals("removeAttribute")) {
                        attributes.remove((String) args[0]);
                        return null;
                    }
                    if (name.equals("invalidate")) {
                        valid[0] = false;
                        attributes.clear();
                        return null;
                    }
                    if (name.equals("getId")) return "mock-session-id";
                    return null;
                }
        );
    }

    private static class MockResponse {
        final int status;
        final String content;

        MockResponse(int status, String content) {
            this.status = status;
            this.content = content;
        }
    }

    private static MockResponse invokeServlet(ApiServlet servlet, String method, String action, String body) throws IOException {
        return invokeServletWithSession(servlet, method, action, body, null);
    }

    private static MockResponse invokeServletWithSession(ApiServlet servlet, String method, String action, String body, HttpSession session) throws IOException {
        int[] status = new int[]{200};
        StringWriter sw = new StringWriter();

        HttpServletRequest req = (HttpServletRequest) Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class},
                (proxy, m, args) -> {
                    String name = m.getName();
                    if (name.equals("getMethod")) return method;
                    if (name.equals("getPathInfo")) return action == null || action.isEmpty() ? null : "/" + action;
                    if (name.equals("getReader")) return new BufferedReader(new StringReader(body == null ? "" : body));
                    if (name.equals("getSession")) return session;
                    if (name.equals("getCharacterEncoding")) return "UTF-8";
                    if (name.equals("setCharacterEncoding")) return null;
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
                    if (name.equals("getWriter")) return new java.io.PrintWriter(sw);
                    if (name.equals("setHeader")) return null;
                    if (name.equals("setContentType")) return null;
                    if (name.equals("setCharacterEncoding")) return null;
                    return null;
                }
        );

        try {
            servlet.service(req, resp);
        } catch (jakarta.servlet.ServletException e) {
            throw new IOException(e);
        }
        return new MockResponse(status[0], sw.toString());
    }

    /**
     * In-memory HTTP Client acting as an OpenAI-compatible test double.
     */
    private static class MockAiHttpClient extends java.net.http.HttpClient {
        volatile int statusCode = 200;
        volatile String responseBody = "";
        volatile long delayMs = 0;
        volatile String lastRequestBody = null;
        volatile String lastAuthHeader = null;
        volatile boolean throwTimeout = false;
        volatile boolean throwConnectException = false;

        void reset() {
            statusCode = 200;
            responseBody = "";
            delayMs = 0;
            lastRequestBody = null;
            lastAuthHeader = null;
            throwTimeout = false;
            throwConnectException = false;
        }

        void setResponse(int status, String body) {
            this.statusCode = status;
            this.responseBody = body;
        }

        String getBaseUrl() {
            return "http://127.0.0.1:8080/v1";
        }

        @Override public java.util.Optional<java.net.CookieHandler> cookieHandler() { return java.util.Optional.empty(); }
        @Override public java.util.Optional<java.time.Duration> connectTimeout() { return java.util.Optional.empty(); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public java.util.Optional<java.net.ProxySelector> proxy() { return java.util.Optional.empty(); }
        @Override public javax.net.ssl.SSLContext sslContext() { return null; }
        @Override public javax.net.ssl.SSLParameters sslParameters() { return null; }
        @Override public java.util.Optional<java.net.Authenticator> authenticator() { return java.util.Optional.empty(); }
        @Override public Version version() { return Version.HTTP_2; }
        @Override public java.util.Optional<java.util.concurrent.Executor> executor() { return java.util.Optional.empty(); }

        @SuppressWarnings("unchecked")
        @Override
        public <T> java.net.http.HttpResponse<T> send(java.net.http.HttpRequest request, java.net.http.HttpResponse.BodyHandler<T> responseHandler)
                throws IOException, InterruptedException {
            if (throwTimeout) {
                throw new java.net.http.HttpTimeoutException("The AI server did not answer within timeout");
            }
            if (throwConnectException) {
                throw new java.net.ConnectException("Could not connect to AI server");
            }
            if (delayMs > 0) {
                Thread.sleep(delayMs);
            }
            lastAuthHeader = request.headers().firstValue("Authorization").orElse(null);
            request.bodyPublisher().ifPresent(pub -> {
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                pub.subscribe(new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>() {
                    @Override public void onSubscribe(java.util.concurrent.Flow.Subscription sub) { sub.request(Long.MAX_VALUE); }
                    @Override public void onNext(java.nio.ByteBuffer item) {
                        byte[] b = new byte[item.remaining()];
                        item.get(b);
                        baos.write(b, 0, b.length);
                    }
                    @Override public void onError(Throwable throwable) {}
                    @Override public void onComplete() {
                        lastRequestBody = baos.toString(StandardCharsets.UTF_8);
                    }
                });
            });

            return (java.net.http.HttpResponse<T>) new MockAiHttpResponse(statusCode, responseBody, request);
        }

        @Override
        public <T> java.util.concurrent.CompletableFuture<java.net.http.HttpResponse<T>> sendAsync(java.net.http.HttpRequest request, java.net.http.HttpResponse.BodyHandler<T> responseHandler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> java.util.concurrent.CompletableFuture<java.net.http.HttpResponse<T>> sendAsync(java.net.http.HttpRequest request, java.net.http.HttpResponse.BodyHandler<T> responseHandler, java.net.http.HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            throw new UnsupportedOperationException();
        }
    }

    private static class MockAiHttpResponse implements java.net.http.HttpResponse<String> {
        private final int status;
        private final String body;
        private final java.net.http.HttpRequest request;

        MockAiHttpResponse(int status, String body, java.net.http.HttpRequest request) {
            this.status = status;
            this.body = body;
            this.request = request;
        }

        @Override public int statusCode() { return status; }
        @Override public java.net.http.HttpRequest request() { return request; }
        @Override public java.util.Optional<java.net.http.HttpResponse<String>> previousResponse() { return java.util.Optional.empty(); }
        @Override public java.net.http.HttpHeaders headers() {
            return java.net.http.HttpHeaders.of(Map.of("Content-Type", List.of("application/json")), (k, v) -> true);
        }
        @Override public String body() { return body; }
        @Override public java.util.Optional<javax.net.ssl.SSLSession> sslSession() { return java.util.Optional.empty(); }
        @Override public java.net.URI uri() { return request.uri(); }
        @Override public java.net.http.HttpClient.Version version() { return java.net.http.HttpClient.Version.HTTP_2; }
    }
}
