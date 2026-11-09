package com.travelmate;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.travelmate.ai.AiClient;
import com.travelmate.ai.ItineraryGenerator;
import com.travelmate.geo.GeoService;
import com.travelmate.model.*;
import com.travelmate.weather.WeatherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests verifying weather enrichment within the AI itinerary planning engine.
 */
public class WeatherIntegrationTest {

    private static final String SAMPLE_WEATHER_RAIN_JSON = """
            {
              "latitude": 12.3051,
              "longitude": 76.6551,
              "timezone": "Asia/Kolkata",
              "current": {
                "time": "2026-11-14T10:00",
                "temperature_2m": 24.0,
                "apparent_temperature": 26.0,
                "relative_humidity_2m": 85,
                "precipitation": 5.0,
                "weather_code": 65,
                "wind_speed_10m": 18.0
              },
              "daily": {
                "time": ["2026-11-14", "2026-11-15"],
                "weather_code": [0, 95],
                "temperature_2m_max": [28.0, 25.0],
                "temperature_2m_min": [18.0, 19.0],
                "apparent_temperature_max": [30.0, 26.0],
                "apparent_temperature_min": [19.0, 20.0],
                "precipitation_sum": [0.0, 15.0],
                "precipitation_probability_max": [10, 90],
                "wind_speed_10m_max": [10.0, 25.0],
                "uv_index_max": [7.0, 3.5]
              }
            }
            """;

    private MockCountingHttpClient mockWeatherHttp;
    private WeatherService weatherService;
    private GeoService geoService;
    private AiSettings aiSettings;

    @BeforeEach
    void setUp() {
        mockWeatherHttp = new MockCountingHttpClient(200, SAMPLE_WEATHER_RAIN_JSON);
        weatherService = new WeatherService(mockWeatherHttp, "https://api.test-weather.com/v1/forecast", 1800_000L, 100, null);

        // Mock GeoService returning coordinates for Mysuru
        HttpClient mockGeoHttp = new MockGeoHttpClient();
        geoService = new GeoService(mockGeoHttp, "https://nominatim.test", 0);

        aiSettings = new AiSettings();
        aiSettings.enabled = true;
        aiSettings.model = "test-model";
        aiSettings.baseUrl = "https://mock.ai/v1";
    }

    @Test
    void testWeatherSuccessfullyEnrichesItineraryStops() throws AiClient.AiException {
        HttpClient mockAi = new MockAiClient(buildAiItineraryResponse());
        ItineraryGenerator generator = new ItineraryGenerator(aiSettings, mockAi, geoService, weatherService);

        ItineraryGenerator.Request req = new ItineraryGenerator.Request();
        req.destination = "Mysuru";
        req.startDate = "2026-11-14";
        req.days = 2;
        req.center = new double[]{12.3051, 76.6551};

        ItineraryGenerator.Result res = generator.generate(req);
        assertNotNull(res);
        assertEquals(2, res.days.size());

        // Day 1 has clear weather (weather_code 0)
        ItineraryDay day1 = res.days.get(0);
        assertNotNull(day1.weather);
        assertEquals("2026-11-14", day1.weather.date);
        assertEquals("Clear Sky", day1.weather.condition);
        assertFalse(day1.stops.get(0).isWeatherImpacted != null && day1.stops.get(0).isWeatherImpacted);

        // Day 2 has thunderstorm (weather_code 95)
        ItineraryDay day2 = res.days.get(1);
        assertNotNull(day2.weather);
        assertEquals("2026-11-15", day2.weather.date);
        assertEquals("Thunderstorm", day2.weather.condition);

        // Stop in Day 2 categorized as Outdoor/Nature should be marked isWeatherImpacted=true
        ItineraryStop outdoorStop = day2.stops.stream()
                .filter(s -> "Nature".equalsIgnoreCase(s.category) || "Outdoor".equalsIgnoreCase(s.category))
                .findFirst()
                .orElse(null);
        assertNotNull(outdoorStop, "Outdoor stop should be present in Day 2");
        assertTrue(outdoorStop.isWeatherImpacted, "Outdoor stop must be marked isWeatherImpacted during thunderstorm");

        // Indoor stop in Day 2 (e.g. Museum/Food) should NOT be weather impacted
        ItineraryStop indoorStop = day2.stops.stream()
                .filter(s -> "Museum".equalsIgnoreCase(s.category) || "Food".equalsIgnoreCase(s.category))
                .findFirst()
                .orElse(null);
        assertNotNull(indoorStop, "Indoor stop should be present in Day 2");
        assertFalse(indoorStop.isWeatherImpacted != null && indoorStop.isWeatherImpacted, "Indoor stop must not be weather impacted");
    }

    @Test
    void testWeatherFailureDoesNotBreakItineraryGeneration() throws AiClient.AiException {
        // Weather HTTP client returns 503 error
        mockWeatherHttp.setResponse(503, "Weather service unavailable");
        HttpClient mockAi = new MockAiClient(buildAiItineraryResponse());
        ItineraryGenerator generator = new ItineraryGenerator(aiSettings, mockAi, geoService, weatherService);

        ItineraryGenerator.Request req = new ItineraryGenerator.Request();
        req.destination = "Mysuru";
        req.startDate = "2026-11-14";
        req.days = 2;
        req.center = new double[]{12.3051, 76.6551};

        // Must complete itinerary generation gracefully
        ItineraryGenerator.Result res = generator.generate(req);
        assertNotNull(res);
        assertEquals(2, res.days.size());
        assertNull(res.days.get(0).weather, "Weather is null when provider fails, but itinerary succeeds");
    }

    @Test
    void testExistingUserStopsAndCoordinatesRemainUnchanged() throws AiClient.AiException {
        HttpClient mockAi = new MockAiClient(buildAiTourPlanResponse());
        ItineraryGenerator generator = new ItineraryGenerator(aiSettings, mockAi, geoService, weatherService);

        ItineraryGenerator.TourRequest req = new ItineraryGenerator.TourRequest();
        req.destination = "Mysuru";
        req.center = new double[]{12.3051, 76.6551};
        req.plan = new ArrayList<>();

        ItineraryDay day1 = new ItineraryDay();
        day1.dayNumber = 1;
        day1.date = "2026-11-14";
        day1.title = "User Custom Day";
        ItineraryStop userStop = new ItineraryStop();
        userStop.id = "user-stop-101";
        userStop.name = "My Custom Heritage Stop";
        userStop.category = "Heritage";
        userStop.lat = 12.3150;
        userStop.lng = 76.6520;
        day1.stops.add(userStop);
        req.plan.add(day1);
        req.mode = "organize";

        ItineraryGenerator.TourResult tourResult = generator.planTour(req);
        assertNotNull(tourResult);
        assertEquals(1, tourResult.days.size());

        ItineraryStop stop = tourResult.days.get(0).stops.get(0);
        assertEquals("user-stop-101", stop.id);
        assertEquals("My Custom Heritage Stop", stop.name);
        assertEquals(12.3150, stop.lat, 0.0001, "User-provided latitude must be strictly preserved");
        assertEquals(76.6520, stop.lng, 0.0001, "User-provided longitude must be strictly preserved");
    }

    @Test
    void testCorrectForecastDateMatchingPerDay() throws AiClient.AiException {
        HttpClient mockAi = new MockAiClient(buildAiItineraryResponse());
        ItineraryGenerator generator = new ItineraryGenerator(aiSettings, mockAi, geoService, weatherService);

        ItineraryGenerator.Request req = new ItineraryGenerator.Request();
        req.destination = "Mysuru";
        req.startDate = "2026-11-14";
        req.days = 2;
        req.center = new double[]{12.3051, 76.6551};

        ItineraryGenerator.Result res = generator.generate(req);
        assertEquals("2026-11-14", res.days.get(0).weather.date);
        assertEquals("2026-11-15", res.days.get(1).weather.date);
    }

    @Test
    void testDuplicateCoordinatesDoNotCauseRepeatedExternalCalls() throws AiClient.AiException {
        HttpClient mockAi = new MockAiClient(buildAiItineraryResponse());
        ItineraryGenerator generator = new ItineraryGenerator(aiSettings, mockAi, geoService, weatherService);

        ItineraryGenerator.Request req1 = new ItineraryGenerator.Request();
        req1.destination = "Mysuru";
        req1.days = 2;
        req1.center = new double[]{12.3051, 76.6551};

        generator.generate(req1);
        assertEquals(1, mockWeatherHttp.getRequestCount());

        // Second generation with same destination coordinates hits cache
        generator.generate(req1);
        assertEquals(1, mockWeatherHttp.getRequestCount(), "Repeated requests for identical coordinates must hit cache");
    }

    @Test
    void testSevereWeatherWarningPreservedEvenIfAiSuggestsOutdoor() throws AiClient.AiException {
        HttpClient mockAi = new MockAiClient(buildAiItineraryResponse());
        ItineraryGenerator generator = new ItineraryGenerator(aiSettings, mockAi, geoService, weatherService);

        ItineraryGenerator.Request req = new ItineraryGenerator.Request();
        req.destination = "Mysuru";
        req.startDate = "2026-11-14";
        req.days = 2;
        req.center = new double[]{12.3051, 76.6551};

        ItineraryGenerator.Result res = generator.generate(req);
        assertNotNull(res);
        ItineraryDay day2 = res.days.get(1);
        assertNotNull(day2.weather);
        assertTrue(day2.weather.alert, "Day 2 must have severe alert due to thunderstorm");

        boolean hasImpactedOutdoor = day2.stops.stream().anyMatch(s -> Boolean.TRUE.equals(s.isWeatherImpacted));
        assertTrue(hasImpactedOutdoor, "Severe weather must flag outdoor stops as weather impacted");
    }

    // =========================================================================
    // PHASE 7 REGRESSION TEST SUITE (MANDATORY SCENARIOS A THROUGH H)
    // =========================================================================

    @Test
    void testExactDateMatch_TestA() throws AiClient.AiException {
        // Scenario A: An itinerary day with a date matching a forecast date receives the correct forecast.
        HttpClient mockAi = new MockAiClient(buildAiItineraryResponse());
        ItineraryGenerator generator = new ItineraryGenerator(aiSettings, mockAi, geoService, weatherService);

        ItineraryGenerator.Request req = new ItineraryGenerator.Request();
        req.destination = "Mysuru";
        req.startDate = "2026-11-15"; // Starts on day 2 of forecast (Nov 15)
        req.days = 1;
        req.center = new double[]{12.3051, 76.6551};

        ItineraryGenerator.Result res = generator.generate(req);
        assertNotNull(res);
        ItineraryDay day1 = res.days.get(0);
        assertNotNull(day1.weather, "Must match exact calendar date");
        assertEquals("2026-11-15", day1.weather.date, "Should match Nov 15 forecast, not Nov 14 (index 0)");
        assertEquals("Thunderstorm", day1.weather.condition);
        assertEquals(90, day1.weather.precipitationProbability);
    }

    @Test
    void testDateOutsideForecastWindow_TestB() throws AiClient.AiException {
        // Scenario B: An itinerary day dated November 14 must not receive November 4 weather
        // when the forecast only covers November 4–10.
        String sevenDayForecast = buildSevenDayForecast("2026-11-04");
        mockWeatherHttp.setResponse(200, sevenDayForecast);
        WeatherService freshWeatherService = new WeatherService(mockWeatherHttp, "https://api.test-weather.com/v1/forecast", 0, 100, null);

        HttpClient mockAi = new MockAiClient(buildAiItineraryResponse());
        ItineraryGenerator generator = new ItineraryGenerator(aiSettings, mockAi, geoService, freshWeatherService);

        ItineraryGenerator.Request req = new ItineraryGenerator.Request();
        req.destination = "Mysuru";
        req.startDate = "2026-11-14"; // Outside Nov 4–10 window
        req.days = 1;
        req.center = new double[]{12.3051, 76.6551};

        ItineraryGenerator.Result res = generator.generate(req);
        assertNotNull(res);
        ItineraryDay day1 = res.days.get(0);
        assertNull(day1.weather, "Itinerary day dated Nov 14 must NOT receive Nov 4 weather when forecast only covers Nov 4-10");
        for (ItineraryStop stop : day1.stops) {
            assertFalse(Boolean.TRUE.equals(stop.isWeatherImpacted),
                    "Stops on future dates outside window must not be weather impacted");
        }
    }

    @Test
    void testNoExactMatch_TestC() throws AiClient.AiException {
        // Scenario C: A dated itinerary day without a matching forecast receives no unrelated forecast.
        HttpClient mockAi = new MockAiClient(buildAiItineraryResponse());
        ItineraryGenerator generator = new ItineraryGenerator(aiSettings, mockAi, geoService, weatherService);

        ItineraryGenerator.Request req = new ItineraryGenerator.Request();
        req.destination = "Mysuru";
        req.startDate = "2026-12-01"; // Far future date with no matching forecast
        req.days = 2;
        req.center = new double[]{12.3051, 76.6551};

        ItineraryGenerator.Result res = generator.generate(req);
        assertNotNull(res);
        for (ItineraryDay day : res.days) {
            assertNull(day.weather, "Dated day without exact forecast match must have null weather");
            for (ItineraryStop stop : day.stops) {
                assertFalse(Boolean.TRUE.equals(stop.isWeatherImpacted),
                        "Outdoor stops must not receive unrelated weather impact");
            }
        }
    }

    @Test
    void testUndatedItineraryDay_TestD() throws AiClient.AiException {
        // Scenario D: An itinerary day with no usable date can use positional fallback only if the relationship is valid.
        HttpClient mockAi = new MockAiClient(buildAiItineraryResponse());
        ItineraryGenerator generator = new ItineraryGenerator(aiSettings, mockAi, geoService, weatherService);

        ItineraryGenerator.Request req = new ItineraryGenerator.Request();
        req.destination = "Mysuru";
        req.startDate = null; // Undated demo itinerary
        req.days = 2;
        req.center = new double[]{12.3051, 76.6551};

        ItineraryGenerator.Result res = generator.generate(req);
        assertNotNull(res);
        assertEquals(2, res.days.size());
        assertNotNull(res.days.get(0).weather, "Undated demo itinerary Day 1 should receive fallback index 0");
        assertNotNull(res.days.get(1).weather, "Undated demo itinerary Day 2 should receive fallback index 1");
        assertEquals("Clear Sky", res.days.get(0).weather.condition);
        assertEquals("Thunderstorm", res.days.get(1).weather.condition);
    }

    @Test
    void testNonconsecutiveDates_TestE() throws AiClient.AiException {
        // Scenario E: For itinerary dates such as November 4, November 8 and November 14,
        // weather must match those dates individually. Do not assume itinerary day number equals forecast day number.
        String sevenDayForecast = buildSevenDayForecast("2026-11-04"); // Nov 4 through Nov 10
        mockWeatherHttp.setResponse(200, sevenDayForecast);
        WeatherService freshWeatherService = new WeatherService(mockWeatherHttp, "https://api.test-weather.com/v1/forecast", 0, 100, null);

        HttpClient mockAi = new MockAiClient(buildAiTourPlanResponse());
        ItineraryGenerator generator = new ItineraryGenerator(aiSettings, mockAi, geoService, freshWeatherService);

        ItineraryGenerator.TourRequest req = new ItineraryGenerator.TourRequest();
        req.destination = "Mysuru";
        req.center = new double[]{12.3051, 76.6551};
        req.plan = new ArrayList<>();

        // Day 1: Nov 4 (matches forecast index 0)
        ItineraryDay day1 = new ItineraryDay();
        day1.dayNumber = 1;
        day1.date = "2026-11-04";
        day1.title = "Day 1 - Nov 4";
        ItineraryStop s1 = new ItineraryStop();
        s1.id = "s-1";
        s1.name = "Palace";
        s1.category = "Heritage";
        day1.stops.add(s1);
        req.plan.add(day1);

        // Day 2: Nov 8 (matches forecast index 4)
        ItineraryDay day2 = new ItineraryDay();
        day2.dayNumber = 2;
        day2.date = "2026-11-08";
        day2.title = "Day 2 - Nov 8";
        ItineraryStop s2 = new ItineraryStop();
        s2.id = "s-2";
        s2.name = "Brindavan Gardens";
        s2.category = "Nature";
        day2.stops.add(s2);
        req.plan.add(day2);

        // Day 3: Nov 14 (outside forecast Nov 4-10)
        ItineraryDay day3 = new ItineraryDay();
        day3.dayNumber = 3;
        day3.date = "2026-11-14";
        day3.title = "Day 3 - Nov 14";
        ItineraryStop s3 = new ItineraryStop();
        s3.id = "s-3";
        s3.name = "Museum";
        s3.category = "Culture";
        day3.stops.add(s3);
        req.plan.add(day3);

        ItineraryGenerator.TourResult res = generator.planTour(req);
        assertNotNull(res);
        assertEquals(3, res.days.size());

        // Day 1 matches Nov 4
        assertNotNull(res.days.get(0).weather, "Day 1 must match 2026-11-04");
        assertEquals("2026-11-04", res.days.get(0).weather.date);

        // Day 2 matches Nov 8
        assertNotNull(res.days.get(1).weather, "Day 2 must match 2026-11-08");
        assertEquals("2026-11-08", res.days.get(1).weather.date);
        assertTrue(res.days.get(1).weather.alert, "Nov 8 has thunderstorm in custom forecast");
        assertTrue(Boolean.TRUE.equals(res.days.get(1).stops.get(0).isWeatherImpacted));

        // Day 3 is outside forecast window and must NOT receive forecast index 2
        assertNull(res.days.get(2).weather, "Day 3 (2026-11-14) must NOT receive Nov 6 weather via index fallback");
        assertFalse(Boolean.TRUE.equals(res.days.get(2).stops.get(0).isWeatherImpacted));

        // All stops and activities preserved
        assertEquals("s-1", res.days.get(0).stops.get(0).id);
        assertEquals("s-2", res.days.get(1).stops.get(0).id);
        assertEquals("s-3", res.days.get(2).stops.get(0).id);
    }

    @Test
    void testMissingForecastEntries_TestF() throws AiClient.AiException {
        // Scenario F: If a forecast date is missing, the corresponding itinerary day
        // must not receive another day's forecast.
        // Forecast has Nov 14 and Nov 16 (Nov 15 is missing)
        String gapForecast = """
                {
                  "latitude": 12.3051,
                  "longitude": 76.6551,
                  "timezone": "Asia/Kolkata",
                  "daily": {
                    "time": ["2026-11-14", "2026-11-16"],
                    "weather_code": [0, 0],
                    "temperature_2m_max": [28.0, 29.0],
                    "temperature_2m_min": [18.0, 19.0],
                    "apparent_temperature_max": [30.0, 31.0],
                    "apparent_temperature_min": [19.0, 20.0],
                    "precipitation_sum": [0.0, 0.0],
                    "precipitation_probability_max": [10, 10],
                    "wind_speed_10m_max": [10.0, 12.0],
                    "uv_index_max": [7.0, 7.5]
                  }
                }
                """;
        mockWeatherHttp.setResponse(200, gapForecast);
        WeatherService freshWeatherService = new WeatherService(mockWeatherHttp, "https://api.test-weather.com/v1/forecast", 0, 100, null);

        HttpClient mockAi = new MockAiClient(buildAiTourPlanResponse());
        ItineraryGenerator generator = new ItineraryGenerator(aiSettings, mockAi, geoService, freshWeatherService);

        ItineraryGenerator.TourRequest req = new ItineraryGenerator.TourRequest();
        req.destination = "Mysuru";
        req.center = new double[]{12.3051, 76.6551};
        req.plan = new ArrayList<>();

        ItineraryDay day1 = new ItineraryDay();
        day1.dayNumber = 1;
        day1.date = "2026-11-14";
        day1.stops.add(new ItineraryStop());
        req.plan.add(day1);

        ItineraryDay day2 = new ItineraryDay();
        day2.dayNumber = 2;
        day2.date = "2026-11-15"; // Missing from forecast
        day2.stops.add(new ItineraryStop());
        req.plan.add(day2);

        ItineraryDay day3 = new ItineraryDay();
        day3.dayNumber = 3;
        day3.date = "2026-11-16";
        day3.stops.add(new ItineraryStop());
        req.plan.add(day3);

        ItineraryGenerator.TourResult res = generator.planTour(req);
        assertNotNull(res);
        assertEquals(3, res.days.size());

        assertNotNull(res.days.get(0).weather, "Day 1 must match 2026-11-14");
        assertEquals("2026-11-14", res.days.get(0).weather.date);

        assertNull(res.days.get(1).weather, "Day 2 (missing forecast date) must receive null weather, not Nov 16");

        assertNotNull(res.days.get(2).weather, "Day 3 must match 2026-11-16");
        assertEquals("2026-11-16", res.days.get(2).weather.date);
    }

    @Test
    void testTimezoneAndDateOnlySafety_TestG() throws AiClient.AiException {
        // Scenario G: Verify that a date-only value does not shift to the previous
        // or following calendar day because of timezone conversion.
        HttpClient mockAi = new MockAiClient(buildAiItineraryResponse());
        ItineraryGenerator generator = new ItineraryGenerator(aiSettings, mockAi, geoService, weatherService);

        ItineraryGenerator.Request req = new ItineraryGenerator.Request();
        req.destination = "Mysuru";
        req.startDate = "2026-11-14"; // ISO-8601 date string
        req.days = 1;
        req.center = new double[]{12.3051, 76.6551};

        ItineraryGenerator.Result res = generator.generate(req);
        assertNotNull(res);
        ItineraryDay day1 = res.days.get(0);
        assertNotNull(day1.weather);
        assertEquals("2026-11-14", day1.weather.date, "Date matching must preserve exact ISO date string without UTC shift");
    }

    @Test
    void testWeatherImpactAndStaleStateSafety_TestH() throws AiClient.AiException {
        // Scenario H: If a previously matched weather entry becomes unavailable after a date change,
        // remove stale weather information and associated weather-impact indicators.
        // Also verify: stops are preserved, activities are preserved, severe-weather warnings are not weakened,
        // no fabricated weather data is generated.
        HttpClient mockAi = new MockAiClient(buildAiTourPlanResponse());
        ItineraryGenerator generator = new ItineraryGenerator(aiSettings, mockAi, geoService, weatherService);

        ItineraryGenerator.TourRequest req = new ItineraryGenerator.TourRequest();
        req.destination = "Mysuru";
        req.center = new double[]{12.3051, 76.6551};
        req.plan = new ArrayList<>();

        ItineraryDay day1 = new ItineraryDay();
        day1.dayNumber = 1;
        // Previously had Nov 15 thunderstorm weather
        DailyForecast oldForecast = new DailyForecast();
        oldForecast.date = "2026-11-15";
        oldForecast.condition = "Thunderstorm";
        oldForecast.alert = true;
        day1.weather = oldForecast;
        // Date was changed to future December date outside forecast window
        day1.date = "2026-12-10";
        day1.title = "Rescheduled Day";
        ItineraryStop s = new ItineraryStop();
        s.id = "s-101";
        s.name = "Brindavan Garden Walk";
        s.category = "Nature";
        s.isWeatherImpacted = true; // Stale impact from previous storm
        day1.stops.add(s);
        req.plan.add(day1);

        ItineraryGenerator.TourResult res = generator.planTour(req);
        assertNotNull(res);

        // Stale weather must be cleared
        assertNull(res.days.get(0).weather, "Stale weather from Nov 15 must be cleared for Dec 10 date");
        // Stale weather impact indicator must not remain set based on old storm
        assertFalse(Boolean.TRUE.equals(res.days.get(0).stops.get(0).isWeatherImpacted),
                "Stale weather impact flag must be purged when weather becomes unavailable");

        // Stops, names, and activities are 100% preserved
        assertEquals("s-101", res.days.get(0).stops.get(0).id);
        assertEquals("Brindavan Garden Walk", res.days.get(0).stops.get(0).name);
        assertEquals("Nature", res.days.get(0).stops.get(0).category);
    }

    // Helper for 7-day forecast generation starting from a base date
    private static String buildSevenDayForecast(String baseDateStr) {
        java.time.LocalDate base = java.time.LocalDate.parse(baseDateStr);
        StringBuilder times = new StringBuilder();
        StringBuilder codes = new StringBuilder();
        StringBuilder rain = new StringBuilder();
        for (int i = 0; i < 7; i++) {
            if (i > 0) {
                times.append(",");
                codes.append(",");
                rain.append(",");
            }
            times.append("\"").append(base.plusDays(i).toString()).append("\"");
            codes.append(i == 4 ? 95 : 0); // Day 5 has thunderstorm (code 95)
            rain.append(i == 4 ? 90 : 10);
        }
        return """
                {
                  "latitude": 12.3051,
                  "longitude": 76.6551,
                  "timezone": "Asia/Kolkata",
                  "daily": {
                    "time": [%s],
                    "weather_code": [%s],
                    "temperature_2m_max": [28.0, 28.0, 27.0, 29.0, 24.0, 28.0, 29.0],
                    "temperature_2m_min": [18.0, 18.0, 19.0, 19.0, 18.0, 18.0, 19.0],
                    "apparent_temperature_max": [30.0, 30.0, 29.0, 31.0, 25.0, 30.0, 31.0],
                    "apparent_temperature_min": [19.0, 19.0, 20.0, 20.0, 19.0, 19.0, 20.0],
                    "precipitation_sum": [0.0, 0.0, 0.0, 0.0, 20.0, 0.0, 0.0],
                    "precipitation_probability_max": [%s],
                    "wind_speed_10m_max": [10.0, 12.0, 11.0, 14.0, 30.0, 15.0, 10.0],
                    "uv_index_max": [7.0, 7.5, 7.0, 8.0, 3.0, 7.5, 8.0]
                  }
                }
                """.formatted(times.toString(), codes.toString(), rain.toString());
    }

    // ---------------------------------------------------------------- helpers

    private static String buildAiItineraryResponse() {
        JsonObject root = new JsonObject();
        root.addProperty("title", "Mysuru Tour");
        JsonArray days = new JsonArray();

        // Day 1
        JsonObject d1 = new JsonObject();
        d1.addProperty("dayNumber", 1);
        d1.addProperty("title", "Heritage Walk");
        JsonArray s1 = new JsonArray();
        JsonObject stop1 = new JsonObject();
        stop1.addProperty("name", "Mysore Palace");
        stop1.addProperty("category", "Heritage");
        stop1.addProperty("lat", 12.3051);
        stop1.addProperty("lng", 76.6551);
        s1.add(stop1);
        d1.add("stops", s1);
        days.add(d1);

        // Day 2 (has outdoor and indoor stops)
        JsonObject d2 = new JsonObject();
        d2.addProperty("dayNumber", 2);
        d2.addProperty("title", "Nature and Culture");
        JsonArray s2 = new JsonArray();
        JsonObject stop2a = new JsonObject();
        stop2a.addProperty("name", "Brindavan Gardens Fountain Walk");
        stop2a.addProperty("category", "Nature");
        stop2a.addProperty("lat", 12.4220);
        stop2a.addProperty("lng", 76.5720);
        s2.add(stop2a);

        JsonObject stop2b = new JsonObject();
        stop2b.addProperty("name", "Mysuru Rail Museum");
        stop2b.addProperty("category", "Museum");
        stop2b.addProperty("lat", 12.3160);
        stop2b.addProperty("lng", 76.6450);
        s2.add(stop2b);
        d2.add("stops", s2);
        days.add(d2);

        root.add("days", days);

        JsonObject chatResp = new JsonObject();
        JsonArray choices = new JsonArray();
        JsonObject choice = new JsonObject();
        JsonObject message = new JsonObject();
        message.addProperty("content", root.toString());
        choice.add("message", message);
        choices.add(choice);
        chatResp.add("choices", choices);
        return chatResp.toString();
    }

    private static String buildAiTourPlanResponse() {
        JsonObject root = new JsonObject();
        JsonArray days = new JsonArray();
        JsonObject d1 = new JsonObject();
        d1.addProperty("dayNumber", 1);
        JsonArray stops = new JsonArray();
        JsonObject s = new JsonObject();
        s.addProperty("id", "user-stop-101");
        s.addProperty("name", "My Custom Heritage Stop");
        s.addProperty("category", "Heritage");
        s.addProperty("time", "10:00 AM");
        s.addProperty("duration", "2 hrs");
        stops.add(s);
        d1.add("stops", stops);
        days.add(d1);
        root.add("days", days);

        JsonObject chatResp = new JsonObject();
        JsonArray choices = new JsonArray();
        JsonObject choice = new JsonObject();
        JsonObject message = new JsonObject();
        message.addProperty("content", root.toString());
        choice.add("message", message);
        choices.add(choice);
        chatResp.add("choices", choices);
        return chatResp.toString();
    }

    private static class MockCountingHttpClient extends HttpClient {
        private int statusCode;
        private String responseBody;
        private final AtomicInteger count = new AtomicInteger(0);

        MockCountingHttpClient(int statusCode, String responseBody) {
            this.statusCode = statusCode;
            this.responseBody = responseBody;
        }

        void setResponse(int status, String body) {
            this.statusCode = status;
            this.responseBody = body;
        }

        int getRequestCount() {
            return count.get();
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseHandler) throws IOException {
            count.incrementAndGet();
            if (statusCode >= 400) {
                return new SimpleHttpResponse<>(statusCode, (T) responseBody);
            }
            return new SimpleHttpResponse<>(statusCode, (T) responseBody);
        }

        @Override public Optional<java.net.CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.of(Duration.ofSeconds(10)); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public Optional<java.net.ProxySelector> proxy() { return Optional.empty(); }
        @Override public javax.net.ssl.SSLContext sslContext() { return null; }
        @Override public javax.net.ssl.SSLParameters sslParameters() { return null; }
        @Override public Optional<java.net.Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public Optional<java.util.concurrent.Executor> executor() { return Optional.empty(); }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseHandler) { throw new UnsupportedOperationException(); }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseHandler, HttpResponse.PushPromiseHandler<T> pushPromiseHandler) { throw new UnsupportedOperationException(); }
    }

    private static class MockGeoHttpClient extends HttpClient {
        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseHandler) {
            String geoJson = "[{\"lat\":\"12.3051\",\"lon\":\"76.6551\",\"display_name\":\"Mysuru, Karnataka, India\",\"category\":\"place\",\"type\":\"city\"}]";
            return new SimpleHttpResponse<>(200, (T) geoJson);
        }
        @Override public Optional<java.net.CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.of(Duration.ofSeconds(10)); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public Optional<java.net.ProxySelector> proxy() { return Optional.empty(); }
        @Override public javax.net.ssl.SSLContext sslContext() { return null; }
        @Override public javax.net.ssl.SSLParameters sslParameters() { return null; }
        @Override public Optional<java.net.Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public Optional<java.util.concurrent.Executor> executor() { return Optional.empty(); }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseHandler) { throw new UnsupportedOperationException(); }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseHandler, HttpResponse.PushPromiseHandler<T> pushPromiseHandler) { throw new UnsupportedOperationException(); }
    }

    private static class MockAiClient extends HttpClient {
        private final String replyJson;

        MockAiClient(String replyJson) {
            this.replyJson = replyJson;
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseHandler) {
            return new SimpleHttpResponse<>(200, (T) replyJson);
        }
        @Override public Optional<java.net.CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.of(Duration.ofSeconds(10)); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public Optional<java.net.ProxySelector> proxy() { return Optional.empty(); }
        @Override public javax.net.ssl.SSLContext sslContext() { return null; }
        @Override public javax.net.ssl.SSLParameters sslParameters() { return null; }
        @Override public Optional<java.net.Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public Optional<java.util.concurrent.Executor> executor() { return Optional.empty(); }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseHandler) { throw new UnsupportedOperationException(); }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseHandler, HttpResponse.PushPromiseHandler<T> pushPromiseHandler) { throw new UnsupportedOperationException(); }
    }

    private static class SimpleHttpResponse<T> implements HttpResponse<T> {
        private final int code;
        private final T body;

        SimpleHttpResponse(int code, T body) {
            this.code = code;
            this.body = body;
        }

        @Override public int statusCode() { return code; }
        @Override public HttpRequest request() { return null; }
        @Override public Optional<HttpResponse<T>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (k, v) -> true); }
        @Override public T body() { return body; }
        @Override public Optional<javax.net.ssl.SSLSession> sslSession() { return Optional.empty(); }
        @Override public URI uri() { return URI.create("https://api.test.com"); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }
}
