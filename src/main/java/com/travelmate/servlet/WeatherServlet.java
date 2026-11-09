package com.travelmate.servlet;

import com.travelmate.geo.GeoService;
import com.travelmate.model.CurrentWeather;
import com.travelmate.model.WeatherAdvice;
import com.travelmate.model.WeatherData;
import com.travelmate.util.JsonUtil;
import com.travelmate.weather.WeatherService;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * REST API for current weather, multi-day forecasts, and travel heuristics advice.
 *
 * Supported endpoints:
 *   GET /api/weather/current?lat=..&lng=..
 *   GET /api/weather/forecast?lat=..&lng=..[&days=..]
 *   GET /api/weather/advice?lat=..&lng=..[&date=..]
 */
@WebServlet("/api/weather/*")
public class WeatherServlet extends HttpServlet {

    private final WeatherService weatherService;

    public WeatherServlet() {
        this(WeatherService.getDefault());
    }

    public WeatherServlet(WeatherService weatherService) {
        this.weatherService = weatherService != null ? weatherService : WeatherService.getDefault();
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String[] parts = JsonUtil.pathParts(req);
        String action = parts.length >= 1 ? parts[0].toLowerCase() : "";

        try {
            switch (action) {
                case "current" -> handleCurrent(req, resp);
                case "forecast" -> handleForecast(req, resp);
                case "advice" -> handleAdvice(req, resp);
                default -> JsonUtil.error(resp, HttpServletResponse.SC_NOT_FOUND,
                        "Unknown weather endpoint. Supported actions: /current, /forecast, /advice");
            }
        } catch (IllegalArgumentException e) {
            JsonUtil.error(resp, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        } catch (IOException e) {
            String msg = e.getMessage() == null ? "Weather service request failed" : e.getMessage();
            int status = msg.toLowerCase().contains("rate limit") || msg.toLowerCase().contains("429")
                    ? 429 : HttpServletResponse.SC_BAD_GATEWAY;
            JsonUtil.error(resp, status, msg);
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        JsonUtil.error(resp, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "Method Not Allowed");
    }

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        JsonUtil.error(resp, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "Method Not Allowed");
    }

    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        JsonUtil.error(resp, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "Method Not Allowed");
    }

    // ---------------------------------------------------------------- handlers

    private void handleCurrent(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        double[] coords = parseCoordinates(req);
        CurrentWeather current = weatherService.getCurrentWeather(coords[0], coords[1]);
        JsonUtil.ok(resp, current);
    }

    private void handleForecast(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        double[] coords = parseCoordinates(req);
        int days = parseDays(req.getParameter("days"), 5);
        WeatherData data = weatherService.getWeather(coords[0], coords[1], days);
        JsonUtil.ok(resp, data);
    }

    private void handleAdvice(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        double[] coords = parseCoordinates(req);
        String date = req.getParameter("date");
        if (date != null && !date.isBlank()) {
            date = date.trim();
            if (!date.matches("^\\d{4}-\\d{2}-\\d{2}$")) {
                throw new IllegalArgumentException("Invalid date format: " + date + ". Expected YYYY-MM-DD.");
            }
        }
        WeatherAdvice advice = weatherService.getWeatherAdvice(coords[0], coords[1], date);
        JsonUtil.ok(resp, advice);
    }

    // ---------------------------------------------------------------- helpers

    private static double[] parseCoordinates(HttpServletRequest req) {
        String latStr = req.getParameter("lat");
        String lngStr = req.getParameter("lng");

        if (latStr == null || lngStr == null || latStr.isBlank() || lngStr.isBlank()) {
            throw new IllegalArgumentException("Both lat and lng query parameters are required");
        }

        double lat;
        double lng;
        try {
            lat = Double.parseDouble(latStr.trim());
            lng = Double.parseDouble(lngStr.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Coordinates must be valid numeric values");
        }

        if (!GeoService.isValidCoordinate(lat, lng)) {
            throw new IllegalArgumentException(
                    "Invalid coordinate values: lat must be between -90 and 90, lng between -180 and 180 (NaN and infinity rejected)");
        }

        return new double[]{lat, lng};
    }

    private static int parseDays(String param, int fallback) {
        if (param == null || param.isBlank()) return fallback;
        try {
            int d = Integer.parseInt(param.trim());
            if (d < 1 || d > 16) {
                throw new IllegalArgumentException("Forecast days must be between 1 and 16");
            }
            return d;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Forecast days parameter must be a valid integer");
        }
    }
}

