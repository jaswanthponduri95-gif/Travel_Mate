package com.travelmate;

import com.google.gson.JsonObject;
import com.travelmate.model.*;
import com.travelmate.servlet.WeatherServlet;
import com.travelmate.util.JsonUtil;
import com.travelmate.weather.WeatherService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deterministic unit tests for WeatherServlet HTTP endpoints.
 */
public class WeatherServletTest {

    private static final String SAMPLE_WEATHER_JSON = """
            {
              "latitude": 12.2958,
              "longitude": 76.6394,
              "timezone": "Asia/Kolkata",
              "current": {
                "time": "2026-11-04T12:00",
                "temperature_2m": 28.0,
                "apparent_temperature": 30.5,
                "relative_humidity_2m": 60,
                "precipitation": 0.0,
                "weather_code": 0,
                "wind_speed_10m": 12.0
              },
              "daily": {
                "time": ["2026-11-04", "2026-11-05"],
                "weather_code": [0, 95],
                "temperature_2m_max": [29.0, 27.0],
                "temperature_2m_min": [18.0, 19.0],
                "apparent_temperature_max": [31.0, 28.0],
                "apparent_temperature_min": [19.0, 20.0],
                "precipitation_sum": [0.0, 15.0],
                "precipitation_probability_max": [5, 90],
                "wind_speed_10m_max": [12.0, 28.0],
                "uv_index_max": [7.5, 4.0]
              }
            }
            """;

    private WeatherService weatherService;
    private WeatherServlet servlet;

    @BeforeEach
    void setUp() {
        MockWeatherHttpClient mockHttp = new MockWeatherHttpClient(200, SAMPLE_WEATHER_JSON);
        weatherService = new WeatherService(mockHttp, "https://api.test-weather.com/v1/forecast", 1800_000L, 100, null);
        servlet = new WeatherServlet(weatherService);
    }

    @Test
    void testCurrentEndpointValidCoordinates() throws IOException {
        Map<String, String> params = Map.of("lat", "12.2958", "lng", "76.6394");
        MockServletResponse resp = invokeServlet(servlet, "GET", "current", params);

        assertEquals(200, resp.status);
        JsonObject json = JsonUtil.GSON.fromJson(resp.content, JsonObject.class);
        assertEquals(28.0, json.get("temperature").getAsDouble(), 0.1);
        assertEquals("Clear Sky", json.get("condition").getAsString());
        assertEquals("bi-sun", json.get("icon").getAsString());
    }

    @Test
    void testForecastEndpointValidCoordinatesAndDays() throws IOException {
        Map<String, String> params = Map.of("lat", "12.2958", "lng", "76.6394", "days", "5");
        MockServletResponse resp = invokeServlet(servlet, "GET", "forecast", params);

        assertEquals(200, resp.status);
        JsonObject json = JsonUtil.GSON.fromJson(resp.content, JsonObject.class);
        assertTrue(json.get("success").getAsBoolean());
        assertTrue(json.has("daily"));
        assertEquals(2, json.getAsJsonArray("daily").size());
    }

    @Test
    void testAdviceEndpointValidCoordinatesAndDate() throws IOException {
        Map<String, String> params = Map.of("lat", "12.2958", "lng", "76.6394", "date", "2026-11-04");
        MockServletResponse resp = invokeServlet(servlet, "GET", "advice", params);

        assertEquals(200, resp.status);
        JsonObject json = JsonUtil.GSON.fromJson(resp.content, JsonObject.class);
        assertTrue(json.get("success").getAsBoolean());
        assertEquals("2026-11-04", json.get("targetDate").getAsString());
        assertTrue(json.has("impactScore"));
        assertTrue(json.has("activities"));
        assertTrue(json.has("recommendations"));
    }

    @Test
    void testMissingCoordinatesReturns400() throws IOException {
        MockServletResponse resp = invokeServlet(servlet, "GET", "current", Map.of("lat", "12.2958"));
        assertEquals(400, resp.status);
        assertTrue(resp.content.contains("Both lat and lng"));

        MockServletResponse resp2 = invokeServlet(servlet, "GET", "forecast", Collections.emptyMap());
        assertEquals(400, resp2.status);
    }

    @Test
    void testNonNumericCoordinatesReturns400() throws IOException {
        Map<String, String> params = Map.of("lat", "not_a_num", "lng", "76.6394");
        MockServletResponse resp = invokeServlet(servlet, "GET", "current", params);
        assertEquals(400, resp.status);
        assertTrue(resp.content.contains("valid numeric values"));
    }

    @Test
    void testNaNOrInfiniteCoordinatesReturns400() throws IOException {
        Map<String, String> params = Map.of("lat", "NaN", "lng", "76.6394");
        MockServletResponse resp = invokeServlet(servlet, "GET", "current", params);
        assertEquals(400, resp.status);

        Map<String, String> params2 = Map.of("lat", "12.0", "lng", "Infinity");
        MockServletResponse resp2 = invokeServlet(servlet, "GET", "current", params2);
        assertEquals(400, resp2.status);
    }

    @Test
    void testInvalidForecastDaysNegativeOrExceeds16Returns400() throws IOException {
        Map<String, String> params = Map.of("lat", "12.0", "lng", "76.0", "days", "20");
        MockServletResponse resp = invokeServlet(servlet, "GET", "forecast", params);
        assertEquals(400, resp.status);
        assertTrue(resp.content.contains("between 1 and 16"));

        Map<String, String> params2 = Map.of("lat", "12.0", "lng", "76.0", "days", "0");
        MockServletResponse resp2 = invokeServlet(servlet, "GET", "forecast", params2);
        assertEquals(400, resp2.status);
    }

    @Test
    void testInvalidDateFormatReturns400() throws IOException {
        Map<String, String> params = Map.of("lat", "12.0", "lng", "76.0", "date", "14/11/2026");
        MockServletResponse resp = invokeServlet(servlet, "GET", "advice", params);
        assertEquals(400, resp.status);
        assertTrue(resp.content.contains("Expected YYYY-MM-DD"));
    }

    @Test
    void testUnsupportedMethodPostReturns405() throws IOException {
        MockServletResponse resp = invokeServlet(servlet, "POST", "current", Map.of("lat", "12.0", "lng", "76.0"));
        assertEquals(405, resp.status);
        assertTrue(resp.content.contains("Method Not Allowed"));
    }

    @Test
    void testUnsupportedMethodPutReturns405() throws IOException {
        MockServletResponse resp = invokeServlet(servlet, "PUT", "current", Map.of("lat", "12.0", "lng", "76.0"));
        assertEquals(405, resp.status);
    }

    @Test
    void testUnsupportedMethodDeleteReturns405() throws IOException {
        MockServletResponse resp = invokeServlet(servlet, "DELETE", "current", Map.of("lat", "12.0", "lng", "76.0"));
        assertEquals(405, resp.status);
    }

    @Test
    void testUnknownEndpointActionReturns404() throws IOException {
        MockServletResponse resp = invokeServlet(servlet, "GET", "unknown_action", Map.of("lat", "12.0", "lng", "76.0"));
        assertEquals(404, resp.status);
        assertTrue(resp.content.contains("Unknown weather endpoint"));
    }

    @Test
    void testProviderRateLimitPropagates429() throws IOException {
        MockWeatherHttpClient mockHttp = new MockWeatherHttpClient(429, "Too Many Requests");
        WeatherService rateLimitedService = new WeatherService(mockHttp, "https://api.test-weather.com/v1/forecast", 1800_000L, 100, null);
        WeatherServlet rateLimitedServlet = new WeatherServlet(rateLimitedService);

        MockServletResponse resp = invokeServlet(rateLimitedServlet, "GET", "current", Map.of("lat", "12.0", "lng", "76.0"));
        assertEquals(429, resp.status);
    }

    @Test
    void testProviderFailureReturns502() throws IOException {
        MockWeatherHttpClient mockHttp = new MockWeatherHttpClient(500, "Internal Server Error");
        WeatherService failingService = new WeatherService(mockHttp, "https://api.test-weather.com/v1/forecast", 1800_000L, 100, null);
        WeatherServlet failingServlet = new WeatherServlet(failingService);

        MockServletResponse resp = invokeServlet(failingServlet, "GET", "current", Map.of("lat", "12.0", "lng", "76.0"));
        assertEquals(502, resp.status);
    }

    // ---------------------------------------------------------------- servlet invoke helper

    private static MockServletResponse invokeServlet(WeatherServlet servlet, String method, String action, Map<String, String> params)
            throws IOException {
        StringWriter out = new StringWriter();
        int[] status = new int[]{200};

        HttpServletRequest req = (HttpServletRequest) Proxy.newProxyInstance(
                WeatherServletTest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class},
                (proxy, m, args) -> {
                    String name = m.getName();
                    if ("getMethod".equals(name)) return method;
                    if ("getPathInfo".equals(name)) return "/" + action;
                    if ("getParameter".equals(name)) return params.get((String) args[0]);
                    if ("setCharacterEncoding".equals(name)) return null;
                    if ("getCharacterEncoding".equals(name)) return "UTF-8";
                    return null;
                });

        HttpServletResponse resp = (HttpServletResponse) Proxy.newProxyInstance(
                WeatherServletTest.class.getClassLoader(),
                new Class<?>[]{HttpServletResponse.class},
                (proxy, m, args) -> {
                    String name = m.getName();
                    if ("setStatus".equals(name)) {
                        status[0] = (int) args[0];
                        return null;
                    }
                    if ("getWriter".equals(name)) return new java.io.PrintWriter(out);
                    if ("setHeader".equals(name) || "addHeader".equals(name) || "setContentType".equals(name) || "setCharacterEncoding".equals(name)) return null;
                    return null;
                });

        try {
            servlet.service(req, resp);
        } catch (jakarta.servlet.ServletException e) {
            throw new IOException(e);
        }

        MockServletResponse response = new MockServletResponse();
        response.status = status[0];
        response.content = out.toString();
        return response;
    }

    private static class MockServletResponse {
        int status;
        String content;
    }

    private static class MockWeatherHttpClient extends HttpClient {
        private final int statusCode;
        private final String body;

        MockWeatherHttpClient(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseHandler) {
            return new MockWeatherHttpResponse<>(statusCode, (T) body);
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

    private static class MockWeatherHttpResponse<T> implements HttpResponse<T> {
        private final int code;
        private final T body;

        MockWeatherHttpResponse(int code, T body) {
            this.code = code;
            this.body = body;
        }

        @Override public int statusCode() { return code; }
        @Override public HttpRequest request() { return null; }
        @Override public Optional<HttpResponse<T>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (k, v) -> true); }
        @Override public T body() { return body; }
        @Override public Optional<javax.net.ssl.SSLSession> sslSession() { return Optional.empty(); }
        @Override public URI uri() { return URI.create("https://api.test-weather.com/v1/forecast"); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }
}
