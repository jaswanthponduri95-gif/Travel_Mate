package com.travelmate;

import com.travelmate.model.*;
import com.travelmate.weather.WeatherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deterministic unit tests for WeatherService using mocked HTTP clients and responses.
 */
public class WeatherServiceTest {

    private static final String SAMPLE_OPEN_METEO_JSON = """
            {
              "latitude": 12.2958,
              "longitude": 76.6394,
              "generationtime_ms": 1.25,
              "timezone": "Asia/Kolkata",
              "elevation": 745.0,
              "current": {
                "time": "2026-11-04T12:00",
                "temperature_2m": 27.5,
                "relative_humidity_2m": 62,
                "apparent_temperature": 29.8,
                "precipitation": 0.0,
                "weather_code": 2,
                "wind_speed_10m": 14.5,
                "wind_direction_10m": 95.0,
                "uv_index": 6.8
              },
              "daily": {
                "time": ["2026-11-04", "2026-11-05", "2026-11-06"],
                "weather_code": [2, 65, 0],
                "temperature_2m_max": [29.0, 26.0, 30.0],
                "temperature_2m_min": [18.0, 19.0, 17.5],
                "apparent_temperature_max": [31.0, 27.0, 31.5],
                "apparent_temperature_min": [19.0, 20.0, 18.0],
                "precipitation_sum": [0.0, 12.5, 0.0],
                "precipitation_probability_max": [10, 85, 5],
                "wind_speed_10m_max": [14.0, 22.0, 11.0],
                "uv_index_max": [7.0, 4.5, 7.8]
              },
              "hourly": {
                "time": ["2026-11-04T12:00", "2026-11-04T13:00"],
                "temperature_2m": [27.5, 28.0],
                "precipitation_probability": [10, 15],
                "precipitation": [0.0, 0.0],
                "weather_code": [2, 2],
                "uv_index": [6.8, 6.2]
              }
            }
            """;

    private MockHttpClient mockHttp;
    private WeatherService service;

    @BeforeEach
    void setUp() {
        mockHttp = new MockHttpClient(200, SAMPLE_OPEN_METEO_JSON);
        service = new WeatherService(mockHttp, "https://api.test-weather.com/v1/forecast", 1800_000L, 100, null);
    }

    @Test
    void testValidWeatherResponse() throws IOException {
        WeatherData data = service.getWeather(12.2958, 76.6394, 3);
        assertNotNull(data);
        assertTrue(data.success);
        assertEquals(12.2958, data.latitude, 0.001);
        assertEquals(76.6394, data.longitude, 0.001);
        assertEquals("Asia/Kolkata", data.timezone);
        assertEquals("Open-Meteo", data.provider);
        assertEquals("Weather data by Open-Meteo.com (CC BY 4.0)", data.attribution);
        assertFalse(data.isStale);

        assertNotNull(data.current);
        assertEquals(27.5, data.current.temperature);
        assertEquals(29.8, data.current.apparentTemperature);
        assertEquals(62, data.current.relativeHumidity);
        assertEquals(0.0, data.current.precipitation);
        assertEquals(2, data.current.weatherCode);
        assertEquals("Partly Cloudy", data.current.condition);
        assertEquals("bi-cloud-sun", data.current.icon);
        assertEquals(14.5, data.current.windSpeed);
        assertEquals(95.0, data.current.windDirection);
        assertEquals(6.8, data.current.uvIndex);
    }

    @Test
    void testHourlyAndDailyForecastParsing() throws IOException {
        WeatherData data = service.getWeather(12.2958, 76.6394, 3);

        // Daily
        assertEquals(3, data.daily.size());
        DailyForecast day1 = data.daily.get(0);
        assertEquals("2026-11-04", day1.date);
        assertEquals(2, day1.weatherCode);
        assertEquals(29.0, day1.tempMax);
        assertEquals(18.0, day1.tempMin);
        assertEquals(10, day1.precipitationProbability);
        assertFalse(day1.alert);

        DailyForecast day2 = data.daily.get(1);
        assertEquals("2026-11-05", day2.date);
        assertEquals(65, day2.weatherCode); // Heavy rain
        assertEquals("Heavy Rain", day2.condition);
        assertEquals(12.5, day2.precipitationSum);
        assertEquals(85, day2.precipitationProbability);
        assertTrue(day2.alert, "Rain prob >= 65% or heavy rain code must set alert=true");

        // Hourly
        assertEquals(2, data.hourly.size());
        HourlyForecast hour1 = data.hourly.get(0);
        assertEquals("2026-11-04T12:00", hour1.time);
        assertEquals(27.5, hour1.temperature);
        assertEquals(10, hour1.precipitationProbability);
    }

    @Test
    void testGetCurrentWeather() throws IOException {
        CurrentWeather cur = service.getCurrentWeather(12.2958, 76.6394);
        assertNotNull(cur);
        assertEquals(27.5, cur.temperature);
        assertEquals("Partly Cloudy", cur.condition);
    }

    @Test
    void testInvalidLatitudeOutOfRange() {
        assertThrows(IllegalArgumentException.class, () -> service.getWeather(95.0, 76.6394, 3));
        assertThrows(IllegalArgumentException.class, () -> service.getWeather(-91.0, 76.6394, 3));
    }

    @Test
    void testInvalidLongitudeOutOfRange() {
        assertThrows(IllegalArgumentException.class, () -> service.getWeather(12.2958, 185.0, 3));
        assertThrows(IllegalArgumentException.class, () -> service.getWeather(12.2958, -181.0, 3));
    }

    @Test
    void testNaNAndInfiniteCoordinates() {
        assertThrows(IllegalArgumentException.class, () -> service.getWeather(Double.NaN, 76.6394, 3));
        assertThrows(IllegalArgumentException.class, () -> service.getWeather(12.2958, Double.POSITIVE_INFINITY, 3));
        assertThrows(IllegalArgumentException.class, () -> service.getWeather(Double.NEGATIVE_INFINITY, 76.6394, 3));
    }

    @Test
    void testMissingOptionalFieldsInJson() throws IOException {
        String minimalJson = """
                {
                  "latitude": 12.2958,
                  "longitude": 76.6394,
                  "current": {
                    "temperature_2m": 25.0
                  }
                }
                """;
        mockHttp.setResponse(200, minimalJson);
        WeatherData data = service.getWeather(12.2958, 76.6394, 1);
        assertNotNull(data);
        assertEquals(25.0, data.current.temperature);
        assertNull(data.current.apparentTemperature);
        assertNull(data.current.windSpeed);
        assertTrue(data.daily.isEmpty());
        assertTrue(data.hourly.isEmpty());
    }

    @Test
    void testMalformedJsonHandling() {
        mockHttp.setResponse(200, "{ not valid json syntax ]");
        IOException ex = assertThrows(IOException.class, () -> service.getWeather(12.2958, 76.6394, 3));
        assertTrue(ex.getMessage().contains("Malformed JSON"));
    }

    @Test
    void testInvalidJsonRootNotObject() {
        mockHttp.setResponse(200, "[\"not\", \"an\", \"object\"]");
        IOException ex = assertThrows(IOException.class, () -> service.getWeather(12.2958, 76.6394, 3));
        assertTrue(ex.getMessage().contains("Invalid JSON root"));
    }

    @Test
    void testHttp400BadRequest() {
        mockHttp.setResponse(400, "{\"error\": true, \"reason\": \"Latitude must be in range [-90, 90]\"}");
        IOException ex = assertThrows(IOException.class, () -> service.getWeather(12.2958, 76.6394, 3));
        assertTrue(ex.getMessage().contains("HTTP 400"));
    }

    @Test
    void testHttp403Forbidden() {
        mockHttp.setResponse(403, "Forbidden");
        IOException ex = assertThrows(IOException.class, () -> service.getWeather(12.2958, 76.6394, 3));
        assertTrue(ex.getMessage().contains("HTTP 403"));
    }

    @Test
    void testHttp429RateLimitExceeded() {
        mockHttp.setResponse(429, "Too Many Requests");
        IOException ex = assertThrows(IOException.class, () -> service.getWeather(12.2958, 76.6394, 3));
        assertTrue(ex.getMessage().contains("rate limit") || ex.getMessage().contains("429"));
    }

    @Test
    void testHttp500ServerError() {
        mockHttp.setResponse(500, "Internal Server Error");
        IOException ex = assertThrows(IOException.class, () -> service.getWeather(12.2958, 76.6394, 3));
        assertTrue(ex.getMessage().contains("temporarily unavailable") || ex.getMessage().contains("500"));
    }

    @Test
    void testHttp503ServiceUnavailable() {
        mockHttp.setResponse(503, "Service Unavailable");
        IOException ex = assertThrows(IOException.class, () -> service.getWeather(12.2958, 76.6394, 3));
        assertTrue(ex.getMessage().contains("temporarily unavailable") || ex.getMessage().contains("503"));
    }

    @Test
    void testEmptyResponseBody() {
        mockHttp.setResponse(200, "   ");
        IOException ex = assertThrows(IOException.class, () -> service.getWeather(12.2958, 76.6394, 3));
        assertTrue(ex.getMessage().contains("Empty response"));
    }

    @Test
    void testRequestTimeout() {
        mockHttp.setThrowException(new HttpTimeoutException("Connection timed out"));
        IOException ex = assertThrows(IOException.class, () -> service.getWeather(12.2958, 76.6394, 3));
        assertTrue(ex.getMessage().contains("timed out") || ex instanceof HttpTimeoutException);
    }

    @Test
    void testInterruptedRequestHandling() {
        mockHttp.setThrowException(new InterruptedException("Interrupted during send"));
        IOException ex = assertThrows(IOException.class, () -> service.getWeather(12.2958, 76.6394, 3));
        assertTrue(ex.getMessage().contains("interrupted"));
    }

    @Test
    void testCacheHitAvoidsSecondHttpCall() throws IOException {
        service.getWeather(12.2958, 76.6394, 3);
        assertEquals(1, mockHttp.getRequestCount());

        // Second call with same coordinates and days
        WeatherData data2 = service.getWeather(12.2958, 76.6394, 3);
        assertNotNull(data2);
        assertEquals(1, mockHttp.getRequestCount(), "Cache hit must avoid second external request");
    }

    @Test
    void testCacheKeyNormalizationCoordinates() throws IOException {
        // Points differing only in tiny 5th/6th decimals (~10 meters) share cache key
        service.getWeather(12.29581, 76.63941, 3);
        assertEquals(1, mockHttp.getRequestCount());

        service.getWeather(12.29584, 76.63944, 3);
        assertEquals(1, mockHttp.getRequestCount(), "Normalized coordinates must hit the cache");
    }

    @Test
    void testCacheExpiration() throws Exception {
        // Short 50ms TTL service
        WeatherService shortTtlService = new WeatherService(mockHttp, "https://api.test-weather.com/v1/forecast", 50L, 100, null);
        shortTtlService.getWeather(12.2958, 76.6394, 3);
        assertEquals(1, mockHttp.getRequestCount());

        Thread.sleep(80); // Wait for expiration

        shortTtlService.getWeather(12.2958, 76.6394, 3);
        assertEquals(2, mockHttp.getRequestCount(), "Expired cache entry must trigger new HTTP call");
    }

    @Test
    void testCacheSizeLimitLruEviction() throws IOException {
        // Cache limit: 3 entries
        WeatherService smallCacheService = new WeatherService(mockHttp, "https://api.test-weather.com/v1/forecast", 1800_000L, 3, null);

        smallCacheService.getWeather(10.0, 70.0, 1);
        smallCacheService.getWeather(11.0, 71.0, 1);
        smallCacheService.getWeather(12.0, 72.0, 1);
        assertEquals(3, smallCacheService.getCacheSize());

        // 4th request must evict eldest
        smallCacheService.getWeather(13.0, 73.0, 1);
        assertTrue(smallCacheService.getCacheSize() <= 3, "Cache size must be bounded by max entries");
    }

    @Test
    void testConcurrentCacheAccess() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        int tasks = 30;
        CountDownLatch latch = new CountDownLatch(tasks);
        AtomicInteger failures = new AtomicInteger(0);

        for (int i = 0; i < tasks; i++) {
            final double lat = 12.0 + (i % 3);
            final double lng = 76.0 + (i % 3);
            pool.submit(() -> {
                try {
                    WeatherData d = service.getWeather(lat, lng, 3);
                    if (d == null || d.current == null) failures.incrementAndGet();
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        pool.shutdown();
        assertEquals(0, failures.get(), "Concurrent requests must execute cleanly without exceptions");
    }

    @Test
    void testStaleCacheReturnedOnProviderOutage() throws Exception {
        // First successful fetch
        service.getWeather(12.2958, 76.6394, 3);
        assertEquals(1, mockHttp.getRequestCount());

        // Simulate provider outage on subsequent calls
        mockHttp.setResponse(500, "Server Down");

        // Expire the cache entry manually by setting short TTL
        WeatherService shortService = new WeatherService(mockHttp, "https://api.test-weather.com/v1/forecast", 30L, 100, null);
        mockHttp.setResponse(200, SAMPLE_OPEN_METEO_JSON);
        shortService.getWeather(12.2958, 76.6394, 3);

        Thread.sleep(50); // Expire
        mockHttp.setResponse(503, "Service Unavailable");

        // Should return stale cache marked with isStale = true
        WeatherData staleData = shortService.getWeather(12.2958, 76.6394, 3);
        assertNotNull(staleData);
        assertTrue(staleData.isStale, "Stale cache returned during provider outage must be marked isStale=true");
        assertNotNull(staleData.current);
    }

    @Test
    void testFailedResponsesNeverCachedAsFresh() {
        mockHttp.setResponse(500, "Internal Server Error");
        assertThrows(IOException.class, () -> service.getWeather(12.2958, 76.6394, 3));
        assertEquals(0, service.getCacheSize(), "Failed responses must never be added to cache");
    }

    @Test
    void testWeatherCodeMappingAllCategories() {
        assertEquals("Clear Sky", WeatherService.mapWeatherCode(0)[0]);
        assertEquals("bi-sun", WeatherService.mapWeatherCode(0)[1]);

        assertEquals("Partly Cloudy", WeatherService.mapWeatherCode(2)[0]);
        assertEquals("bi-cloud-sun", WeatherService.mapWeatherCode(2)[1]);

        assertEquals("Overcast", WeatherService.mapWeatherCode(3)[0]);
        assertEquals("bi-clouds", WeatherService.mapWeatherCode(3)[1]);

        assertEquals("Light Drizzle", WeatherService.mapWeatherCode(51)[0]);
        assertEquals("Heavy Rain", WeatherService.mapWeatherCode(65)[0]);
        assertEquals("Thunderstorm", WeatherService.mapWeatherCode(95)[0]);
        assertEquals("bi-cloud-lightning-rain-fill", WeatherService.mapWeatherCode(95)[1]);

        assertEquals("Unknown", WeatherService.mapWeatherCode(null)[0]);
    }

    // ---------------------------------------------------------------- mock helper

    private static class MockHttpClient extends HttpClient {
        private int statusCode;
        private String responseBody;
        private Exception throwException;
        private final AtomicInteger requestCount = new AtomicInteger(0);

        MockHttpClient(int statusCode, String responseBody) {
            this.statusCode = statusCode;
            this.responseBody = responseBody;
        }

        void setResponse(int status, String body) {
            this.statusCode = status;
            this.responseBody = body;
            this.throwException = null;
        }

        void setThrowException(Exception ex) {
            this.throwException = ex;
        }

        int getRequestCount() {
            return requestCount.get();
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseHandler)
                throws IOException, InterruptedException {
            requestCount.incrementAndGet();
            if (throwException instanceof IOException ioe) throw ioe;
            if (throwException instanceof InterruptedException ie) throw ie;
            if (throwException instanceof RuntimeException re) throw re;

            return new MockHttpResponse<>(statusCode, (T) responseBody);
        }

        @Override
        public Optional<java.net.CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override
        public Optional<Duration> connectTimeout() { return Optional.of(Duration.ofSeconds(10)); }
        @Override
        public Redirect followRedirects() { return Redirect.NEVER; }
        @Override
        public Optional<java.net.ProxySelector> proxy() { return Optional.empty(); }
        @Override
        public javax.net.ssl.SSLContext sslContext() { return null; }
        @Override
        public javax.net.ssl.SSLParameters sslParameters() { return null; }
        @Override
        public Optional<java.net.Authenticator> authenticator() { return Optional.empty(); }
        @Override
        public Version version() { return Version.HTTP_1_1; }
        @Override
        public Optional<Executor> executor() { return Optional.empty(); }
        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseHandler) {
            throw new UnsupportedOperationException("Async not used");
        }
        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseHandler, HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            throw new UnsupportedOperationException("Async not used");
        }
    }

    private static class MockHttpResponse<T> implements HttpResponse<T> {
        private final int code;
        private final T body;

        MockHttpResponse(int code, T body) {
            this.code = code;
            this.body = body;
        }

        @Override public int statusCode() { return code; }
        @Override public HttpRequest request() { return null; }
        @Override public Optional<HttpResponse<T>> previousResponse() { return Optional.empty(); }
        @Override public java.net.http.HttpHeaders headers() { return java.net.http.HttpHeaders.of(java.util.Map.of(), (k, v) -> true); }
        @Override public T body() { return body; }
        @Override public Optional<javax.net.ssl.SSLSession> sslSession() { return Optional.empty(); }
        @Override public URI uri() { return URI.create("https://api.test-weather.com/v1/forecast"); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }
}
