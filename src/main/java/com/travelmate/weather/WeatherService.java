package com.travelmate.weather;

import com.google.gson.*;
import com.travelmate.geo.GeoService;
import com.travelmate.model.*;
import com.travelmate.util.EnvLoader;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * Backend Weather Service providing real-time conditions, multi-day forecasts,
 * and travel advice backed by Open-Meteo.
 *
 * <p>
 * Attribution & Terms: Weather forecast data is provided by Open-Meteo.com
 * under the
 * Creative Commons Attribution 4.0 International license (CC BY 4.0).
 * Usage is free for open-source/non-commercial projects under 10,000 daily API
 * calls.
 * </p>
 *
 * Includes bounded thread-safe caching, stale-cache fallback during provider
 * outages,
 * coordinate validation, and testable HTTP client injection.
 */
public class WeatherService {

    public static final String ATTRIBUTION = "Weather data provided by Open-Meteo.com (CC BY 4.0)";

    public static final String DEFAULT_BASE_URL = firstNonBlank(
            System.getProperty("tm.weather.url"),
            EnvLoader.get("TM_WEATHER_URL"),
            "https://api.open-meteo.com/v1/forecast");

    public static final String USER_AGENT = "TravelMate/1.0 (student travel planner; Java HttpClient)";
    public static final long DEFAULT_CACHE_TTL_MS = 30 * 60 * 1000L; // 30 minutes
    public static final int DEFAULT_MAX_CACHE_ENTRIES = 500;

    private static final WeatherService DEFAULT_INSTANCE = new WeatherService();

    public static WeatherService getDefault() {
        return DEFAULT_INSTANCE;
    }

    private final HttpClient http;
    private final String baseUrl;
    private final long cacheTtlMs;
    private final int maxCacheEntries;
    private final WeatherHeuristicsEngine heuristicsEngine;

    private static class CacheEntry {
        final WeatherData data;
        final long createdAt;

        CacheEntry(WeatherData data) {
            this.data = data;
            this.createdAt = System.currentTimeMillis();
        }

        boolean isExpired(long ttlMs) {
            return (System.currentTimeMillis() - createdAt) > ttlMs;
        }
    }

    private final Map<String, CacheEntry> cache;

    public WeatherService() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                DEFAULT_BASE_URL, DEFAULT_CACHE_TTL_MS, DEFAULT_MAX_CACHE_ENTRIES, new WeatherHeuristicsEngine());
    }

    public WeatherService(HttpClient http) {
        this(http, DEFAULT_BASE_URL, DEFAULT_CACHE_TTL_MS, DEFAULT_MAX_CACHE_ENTRIES, new WeatherHeuristicsEngine());
    }

    public WeatherService(HttpClient http, String baseUrl, long cacheTtlMs, int maxCacheEntries,
            WeatherHeuristicsEngine heuristicsEngine) {
        this.http = http != null ? http : HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.baseUrl = (baseUrl != null && !baseUrl.isBlank()) ? baseUrl.replaceAll("/+$", "") : DEFAULT_BASE_URL;
        this.cacheTtlMs = Math.max(1L, cacheTtlMs);
        this.maxCacheEntries = Math.max(1, maxCacheEntries);
        this.heuristicsEngine = heuristicsEngine != null ? heuristicsEngine : new WeatherHeuristicsEngine();

        final int limit = this.maxCacheEntries;
        this.cache = Collections.synchronizedMap(new LinkedHashMap<String, CacheEntry>(128, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                return size() > limit;
            }
        });
    }

    public WeatherHeuristicsEngine getHeuristicsEngine() {
        return heuristicsEngine;
    }

    // ---------------------------------------------------------------- public APIs

    /**
     * Retrieves full weather data (current + daily + hourly forecast) for
     * coordinates and forecast days.
     */
    public WeatherData getWeather(double lat, double lng, int days) throws IOException {
        validateCoordinates(lat, lng);
        int clampedDays = Math.max(1, Math.min(days, 16));
        String cacheKey = normalizeCacheKey(lat, lng, clampedDays);

        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && !cached.isExpired(cacheTtlMs)) {
            return cloneWeatherData(cached.data, false);
        }

        try {
            String url = buildForecastUrl(lat, lng, clampedDays);
            String rawJson = executeGet(url);
            WeatherData parsed = parseOpenMeteoJson(rawJson, lat, lng);
            parsed.isStale = false;

            cache.put(cacheKey, new CacheEntry(parsed));
            return cloneWeatherData(parsed, false);
        } catch (IOException e) {
            // Provider failure: check if we have an expired entry to serve as stale
            // fallback
            if (cached != null) {
                WeatherData staleCopy = cloneWeatherData(cached.data, true);
                staleCopy.isStale = true;
                return staleCopy;
            }
            throw e;
        }
    }

    /**
     * Retrieves only current weather observations.
     */
    public CurrentWeather getCurrentWeather(double lat, double lng) throws IOException {
        WeatherData data = getWeather(lat, lng, 1);
        if (data.current != null) {
            return data.current;
        }
        throw new IOException("Current weather observations unavailable from provider");
    }

    /**
     * Retrieves smart travel advice for coordinates and a target date.
     */
    public WeatherAdvice getWeatherAdvice(double lat, double lng, String targetDate) throws IOException {
        validateCoordinates(lat, lng);
        WeatherData data = getWeather(lat, lng, 16);

        DailyForecast matched = null;
        if (targetDate != null && !targetDate.isBlank() && data.daily != null) {
            String trimmedDate = targetDate.trim();
            for (DailyForecast df : data.daily) {
                if (trimmedDate.equalsIgnoreCase(df.date)) {
                    matched = df;
                    break;
                }
            }
        }

        // Never substitute today's weather for a requested future trip date.
        // A null match is intentionally rendered as unavailable by the heuristics
        // engine.
        if ((targetDate == null || targetDate.isBlank()) && data.daily != null && !data.daily.isEmpty()) {
            matched = data.daily.get(0);
        }

        WeatherAdvice advice = heuristicsEngine.evaluate(matched, lat, lng);
        if (data.isStale) {
            advice.recommendations.add(0, new RecommendationItem(
                    "Cached Advisory",
                    "Live weather provider currently unreachable. Advisory generated from cached forecast retrieved on "
                            + new Date(data.retrievedAt).toString(),
                    "System", "WARNING", "bi-exclamation-triangle"));
        }
        return advice;
    }

    // ---------------------------------------------------------------- HTTP & JSON

    private String buildForecastUrl(double lat, double lng, int days) {
        return String.format(Locale.ROOT,
                "%s?latitude=%.4f&longitude=%.4f"
                        + "&current=temperature_2m,relative_humidity_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m,wind_direction_10m"
                        + "&daily=weather_code,temperature_2m_max,temperature_2m_min,apparent_temperature_max,apparent_temperature_min,precipitation_sum,precipitation_probability_max,wind_speed_10m_max,uv_index_max"
                        + "&hourly=temperature_2m,precipitation_probability,precipitation,weather_code,uv_index"
                        + "&timezone=auto&forecast_days=%d",
                baseUrl, lat, lng, days);
    }

    private String executeGet(String url) throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("Weather request was interrupted before execution");
        }

        HttpResponse<String> resp;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .GET().build();
            resp = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Weather request interrupted", e);
        }

        int status = resp.statusCode();
        if (status == 400) {
            throw new IOException("Bad request to weather service: HTTP 400");
        }
        if (status == 403) {
            throw new IOException("Access forbidden by weather service: HTTP 403");
        }
        if (status == 429) {
            throw new IOException("Weather service rate limit exceeded (HTTP 429)");
        }
        if (status == 500 || status == 502 || status == 503) {
            throw new IOException("Weather service temporarily unavailable: HTTP " + status);
        }
        if (status / 100 != 2) {
            throw new IOException("Weather service request failed: HTTP " + status);
        }

        String body = resp.body();
        if (body == null || body.isBlank()) {
            throw new IOException("Empty response received from weather service");
        }

        return body;
    }

    public WeatherData parseOpenMeteoJson(String rawJson, double lat, double lng) throws IOException {
        JsonObject root;
        try {
            JsonElement el = JsonParser.parseString(rawJson);
            if (!el.isJsonObject()) {
                throw new IOException("Invalid JSON root received from weather service");
            }
            root = el.getAsJsonObject();
        } catch (JsonSyntaxException e) {
            throw new IOException("Malformed JSON received from weather service: " + e.getMessage(), e);
        }

        WeatherData data = new WeatherData();
        data.latitude = optDouble(root, "latitude", lat);
        data.longitude = optDouble(root, "longitude", lng);
        data.timezone = optString(root, "timezone", "UTC");
        data.elevation = optDouble(root, "elevation", null);
        data.provider = "Open-Meteo";
        data.retrievedAt = System.currentTimeMillis();

        // 1. Parse Current Weather
        if (root.has("current") && root.get("current").isJsonObject()) {
            JsonObject cur = root.getAsJsonObject("current");
            CurrentWeather cw = new CurrentWeather();
            cw.time = optString(cur, "time", null);
            cw.temperature = optDouble(cur, "temperature_2m", null);
            cw.apparentTemperature = optDouble(cur, "apparent_temperature", null);
            cw.relativeHumidity = optInt(cur, "relative_humidity_2m", null);
            cw.precipitation = optDouble(cur, "precipitation", null);
            cw.weatherCode = optInt(cur, "weather_code", null);
            cw.windSpeed = optDouble(cur, "wind_speed_10m", null);
            cw.windDirection = optDouble(cur, "wind_direction_10m", null);
            cw.uvIndex = optDouble(cur, "uv_index", null);

            String[] conditionAndIcon = mapWeatherCode(cw.weatherCode);
            cw.condition = conditionAndIcon[0];
            cw.icon = conditionAndIcon[1];
            data.current = cw;
        }

        // 2. Parse Daily Forecast
        if (root.has("daily") && root.get("daily").isJsonObject()) {
            JsonObject dObj = root.getAsJsonObject("daily");
            JsonArray times = dObj.has("time") && dObj.get("time").isJsonArray() ? dObj.getAsJsonArray("time") : null;
            if (times != null) {
                JsonArray codes = optArray(dObj, "weather_code");
                JsonArray tMax = optArray(dObj, "temperature_2m_max");
                JsonArray tMin = optArray(dObj, "temperature_2m_min");
                JsonArray appMax = optArray(dObj, "apparent_temperature_max");
                JsonArray appMin = optArray(dObj, "apparent_temperature_min");
                JsonArray precSum = optArray(dObj, "precipitation_sum");
                JsonArray precProb = optArray(dObj, "precipitation_probability_max");
                JsonArray windMax = optArray(dObj, "wind_speed_10m_max");
                JsonArray uvMax = optArray(dObj, "uv_index_max");

                for (int i = 0; i < times.size(); i++) {
                    DailyForecast df = new DailyForecast();
                    df.date = times.get(i).getAsString();
                    df.weatherCode = arrayInt(codes, i);
                    df.tempMax = arrayDouble(tMax, i);
                    df.tempMin = arrayDouble(tMin, i);
                    df.apparentTempMax = arrayDouble(appMax, i);
                    df.apparentTempMin = arrayDouble(appMin, i);
                    df.precipitationSum = arrayDouble(precSum, i);
                    df.precipitationProbability = arrayInt(precProb, i);
                    df.windSpeedMax = arrayDouble(windMax, i);
                    df.uvIndexMax = arrayDouble(uvMax, i);

                    String[] cond = mapWeatherCode(df.weatherCode);
                    df.condition = cond[0];
                    df.icon = cond[1];

                    // Set alert flag if rain probability > 60% or thunderstorm or heavy rain
                    df.alert = (df.precipitationProbability != null && df.precipitationProbability >= 65)
                            || (df.precipitationSum != null && df.precipitationSum >= 8.0)
                            || heuristicsEngine.isSevereWeather(df);

                    data.daily.add(df);
                }
            }
        }

        // 3. Parse Hourly Forecast
        if (root.has("hourly") && root.get("hourly").isJsonObject()) {
            JsonObject hObj = root.getAsJsonObject("hourly");
            JsonArray times = hObj.has("time") && hObj.get("time").isJsonArray() ? hObj.getAsJsonArray("time") : null;
            if (times != null) {
                JsonArray temps = optArray(hObj, "temperature_2m");
                JsonArray precProb = optArray(hObj, "precipitation_probability");
                JsonArray prec = optArray(hObj, "precipitation");
                JsonArray codes = optArray(hObj, "weather_code");
                JsonArray uv = optArray(hObj, "uv_index");

                int maxHourly = Math.min(times.size(), 48); // Bounded to 48 hours
                for (int i = 0; i < maxHourly; i++) {
                    HourlyForecast hf = new HourlyForecast();
                    hf.time = times.get(i).getAsString();
                    hf.temperature = arrayDouble(temps, i);
                    hf.precipitationProbability = arrayInt(precProb, i);
                    hf.precipitation = arrayDouble(prec, i);
                    hf.weatherCode = arrayInt(codes, i);
                    hf.uvIndex = arrayDouble(uv, i);

                    String[] cond = mapWeatherCode(hf.weatherCode);
                    hf.condition = cond[0];
                    hf.icon = cond[1];
                    data.hourly.add(hf);
                }
            }
        }

        return data;
    }

    /**
     * Maps WMO weather interpretation codes to clear human descriptions and
     * Bootstrap icons.
     */
    public static String[] mapWeatherCode(Integer code) {
        if (code == null)
            return new String[] { "Unknown", "bi-cloud" };
        return switch (code) {
            case 0 -> new String[] { "Clear Sky", "bi-sun" };
            case 1 -> new String[] { "Mainly Clear", "bi-sun" };
            case 2 -> new String[] { "Partly Cloudy", "bi-cloud-sun" };
            case 3 -> new String[] { "Overcast", "bi-clouds" };
            case 45 -> new String[] { "Foggy", "bi-cloud-fog" };
            case 48 -> new String[] { "Depositing Rime Fog", "bi-cloud-fog" };
            case 51 -> new String[] { "Light Drizzle", "bi-cloud-drizzle" };
            case 53 -> new String[] { "Moderate Drizzle", "bi-cloud-drizzle" };
            case 55 -> new String[] { "Dense Drizzle", "bi-cloud-drizzle" };
            case 56, 57 -> new String[] { "Freezing Drizzle", "bi-cloud-sleet" };
            case 61 -> new String[] { "Slight Rain", "bi-cloud-rain" };
            case 63 -> new String[] { "Moderate Rain", "bi-cloud-rain-heavy" };
            case 65 -> new String[] { "Heavy Rain", "bi-cloud-rain-heavy" };
            case 66, 67 -> new String[] { "Freezing Rain", "bi-cloud-sleet" };
            case 71 -> new String[] { "Slight Snowfall", "bi-cloud-snow" };
            case 73 -> new String[] { "Moderate Snowfall", "bi-cloud-snow" };
            case 75 -> new String[] { "Heavy Snowfall", "bi-cloud-snow" };
            case 77 -> new String[] { "Snow Grains", "bi-cloud-snow" };
            case 80 -> new String[] { "Slight Rain Showers", "bi-cloud-rain" };
            case 81 -> new String[] { "Moderate Rain Showers", "bi-cloud-rain-heavy" };
            case 82 -> new String[] { "Violent Rain Showers", "bi-cloud-lightning-rain" };
            case 85, 86 -> new String[] { "Snow Showers", "bi-cloud-snow" };
            case 95 -> new String[] { "Thunderstorm", "bi-cloud-lightning-rain-fill" };
            case 96, 99 -> new String[] { "Thunderstorm with Hail", "bi-cloud-lightning-rain-fill" };
            default -> new String[] { "Cloudy", "bi-cloud" };
        };
    }

    // ---------------------------------------------------------------- helpers &
    // cache

    public void clearCacheForTest() {
        cache.clear();
    }

    public int getCacheSize() {
        return cache.size();
    }

    private static void validateCoordinates(double lat, double lng) {
        if (!GeoService.isValidCoordinate(lat, lng)) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "Invalid coordinate values: lat=%.6f, lng=%.6f. Latitude must be between -90 and 90, longitude between -180 and 180.",
                    lat, lng));
        }
    }

    public static String normalizeCacheKey(double lat, double lng, int days) {
        return String.format(Locale.ROOT, "%.3f,%.3f:%d", lat, lng, days);
    }

    private static WeatherData cloneWeatherData(WeatherData src, boolean markStale) {
        WeatherData copy = new WeatherData();
        copy.success = src.success;
        copy.latitude = src.latitude;
        copy.longitude = src.longitude;
        copy.timezone = src.timezone;
        copy.elevation = src.elevation;
        copy.provider = src.provider;
        copy.retrievedAt = src.retrievedAt;
        copy.isStale = markStale || src.isStale;
        copy.current = src.current;
        copy.daily = new ArrayList<>(src.daily);
        copy.hourly = new ArrayList<>(src.hourly);
        return copy;
    }

    private static Double optDouble(JsonObject o, String key, Double fallback) {
        if (!o.has(key) || o.get(key).isJsonNull())
            return fallback;
        try {
            return o.get(key).getAsDouble();
        } catch (Exception e) {
            return fallback;
        }
    }

    private static Integer optInt(JsonObject o, String key, Integer fallback) {
        if (!o.has(key) || o.get(key).isJsonNull())
            return fallback;
        try {
            return o.get(key).getAsInt();
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String optString(JsonObject o, String key, String fallback) {
        if (!o.has(key) || o.get(key).isJsonNull())
            return fallback;
        try {
            return o.get(key).getAsString();
        } catch (Exception e) {
            return fallback;
        }
    }

    private static JsonArray optArray(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : null;
    }

    private static Double arrayDouble(JsonArray arr, int idx) {
        if (arr == null || idx >= arr.size() || arr.get(idx).isJsonNull())
            return null;
        try {
            return arr.get(idx).getAsDouble();
        } catch (Exception e) {
            return null;
        }
    }

    private static Integer arrayInt(JsonArray arr, int idx) {
        if (arr == null || idx >= arr.size() || arr.get(idx).isJsonNull())
            return null;
        try {
            return arr.get(idx).getAsInt();
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstNonBlank(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank())
                return c.trim();
        }
        return "";
    }
}
