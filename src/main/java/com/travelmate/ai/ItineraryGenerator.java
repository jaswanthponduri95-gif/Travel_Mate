package com.travelmate.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.travelmate.geo.GeoService;
import com.travelmate.model.AiSettings;
import com.travelmate.model.DailyForecast;
import com.travelmate.model.Place;
import com.travelmate.model.ItineraryDay;
import com.travelmate.model.ItineraryStop;
import com.travelmate.model.Transit;
import com.travelmate.model.WeatherData;
import com.travelmate.weather.WeatherService;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Asks the configured model for a day-by-day itinerary and normalises its JSON
 * reply.
 */
public class ItineraryGenerator {

    /** What the planner form sends (POST /api/ai/itinerary). */
    public static class Request {
        public String origin;
        public String destination;
        public String startDate;
        public int days;
        public int travelers;
        public double budget;
        public String budgetCategory;
        public String pace;
        public String accommodation;
        public List<String> interests;
        /** Optional [lat, lng] of the destination, used to sanity-check coordinates. */
        public double[] center;
    }

    public static class Result {
        public String title;
        public List<ItineraryDay> days;
        /** How many stops were matched to a real OpenStreetMap place. */
        public int placesVerified;
        public double[] center;
        public String model;
        public long elapsedMs;
    }

    private static final Map<String, String> ICONS = Map.of(
            "food", "bi-cup-hot", "heritage", "bi-bank", "nature", "bi-tree", "shopping", "bi-bag",
            "culture", "bi-palette", "adventure", "bi-lightning", "religious", "bi-brightness-high",
            "nightlife", "bi-moon-stars", "beach", "bi-water", "museum", "bi-building");

    private static final String SYSTEM_PROMPT = """
            You are TravelMate, an expert Indian travel planner. You design realistic, \
            geographically sensible day-by-day itineraries with real places, sensible opening \
            hours and honest INR cost estimates. Reply with ONE JSON object only — no markdown, \
            no commentary.""";

    private static final Random JITTER = new Random();
    private static final int MAX_GEOCODE_LOOKUPS = 6;
    private static final long GEOCODE_TIME_BUDGET_MS = 9_000;

    private final AiSettings settings;
    private final java.net.http.HttpClient httpClient;
    private final GeoService geoService;
    private final WeatherService weatherService;

    public ItineraryGenerator(AiSettings settings) {
        this(settings, null, null, null);
    }

    public ItineraryGenerator(AiSettings settings, java.net.http.HttpClient httpClient) {
        this(settings, httpClient, null, null);
    }

    public ItineraryGenerator(AiSettings settings, java.net.http.HttpClient httpClient, GeoService geoService) {
        this(settings, httpClient, geoService, null);
    }

    public ItineraryGenerator(AiSettings settings, java.net.http.HttpClient httpClient, GeoService geoService,
            WeatherService weatherService) {
        this.settings = settings;
        this.httpClient = httpClient;
        this.geoService = geoService != null ? geoService : GeoService.getDefault();
        this.weatherService = weatherService != null ? weatherService : WeatherService.getDefault();
    }

    public Result generate(Request req) throws AiClient.AiException {
        if (req == null || req.destination == null || req.destination.isBlank()) {
            throw new AiClient.AiException("Destination is required to generate an itinerary.");
        }
        int days = Math.max(1, Math.min(req.days, 14));
        long start = System.currentTimeMillis();
        String reply = new AiClient(settings, httpClient).chat(SYSTEM_PROMPT, userPrompt(req, days));

        JsonObject json = extractJson(reply);
        double[] center = resolveCenter(req);
        Result result = new Result();
        result.title = str(json, "title", req.destination + " Tailored Journey");
        result.days = parseDays(json, days, center);
        result.placesVerified = attachMapDetails(result.days, req.destination, center);
        enrichWeatherImpacts(result.days, req.startDate, center);
        result.center = center;
        result.model = settings.model;
        result.elapsedMs = System.currentTimeMillis() - start;
        return result;
    }

    private static String userPrompt(Request r, int days) {
        String stopsPerDay = switch (r.pace == null ? "" : r.pace) {
            case "Relaxed" -> "2";
            case "Packed" -> "4 to 5";
            default -> "3";
        };
        return """
                Plan a %d-day trip.
                - From: %s
                - Destination: %s
                - Start date: %s
                - Travelers: %d
                - Total budget: INR %.0f (%s)
                - Interests: %s
                - Pace: %s (%s stops per day)
                - Preferred stay: %s

                Return exactly this JSON shape with exactly %d entries in "days":
                {
                  "title": "short catchy trip title",
                  "days": [
                    {
                      "title": "theme of the day",
                      "stops": [
                        {
                          "name": "real place or restaurant name",
                          "category": "one of Heritage, Food, Nature, Shopping, Culture, Adventure, Religious, Museum, Beach, Nightlife",
                          "time": "09:30 AM",
                          "duration": "2 hrs",
                          "cost": 200,
                          "lat": 12.3051,
                          "lng": 76.6551,
                          "notes": "one or two practical tips",
                          "transitToNext": { "mode": "Auto Rickshaw", "duration": "10 mins", "distance": "2 km", "cost": 50 }
                        }
                      ]
                    }
                  ]
                }
                Rules: "cost" is total INR for all travelers; use accurate coordinates; order stops by time; \
                omit "transitToNext" on the last stop; include one meal stop per day; keep notes under 12 words.
                """
                .formatted(days, orDash(r.origin), orDash(r.destination), orDash(r.startDate),
                        Math.max(r.travelers, 1), r.budget, orDash(r.budgetCategory),
                        r.interests == null || r.interests.isEmpty() ? "general sightseeing"
                                : String.join(", ", r.interests),
                        orDash(r.pace), stopsPerDay, orDash(r.accommodation), days);
    }

    /**
     * Finds the JSON object in the reply, tolerating ```json fences and text around
     * it.
     */
    public static JsonObject extractJson(String reply) throws AiClient.AiException {
        if (reply == null || reply.isBlank()) {
            throw new AiClient.AiException("The model returned an empty reply.");
        }
        String s = reply.trim();
        // 1. Check for markdown code fence first
        if (s.contains("```")) {
            int fenceStart = s.indexOf("```");
            int contentStart = s.indexOf('\n', fenceStart);
            if (contentStart > 0) {
                int fenceEnd = s.indexOf("```", contentStart);
                if (fenceEnd > contentStart) {
                    String inside = s.substring(contentStart + 1, fenceEnd).trim();
                    try {
                        JsonElement el = JsonParser.parseString(inside);
                        if (el.isJsonObject())
                            return el.getAsJsonObject();
                    } catch (RuntimeException ignored) {
                        // fall through to bracket search
                    }
                }
            }
        }

        // 2. Search between first '{' and last '}'
        int first = s.indexOf('{');
        int last = s.lastIndexOf('}');
        if (first >= 0 && last > first) {
            try {
                JsonElement el = JsonParser.parseString(s.substring(first, last + 1));
                if (el.isJsonObject())
                    return el.getAsJsonObject();
            } catch (RuntimeException ignored) {
                // fall through
            }
        }

        if (first < 0 || last <= first) {
            throw new AiClient.AiException("The model did not return JSON. Reply began: " + preview(s));
        }
        throw new AiClient.AiException("The model returned malformed JSON (it may have been cut off — "
                + "try a higher Max tokens). Reply began: " + preview(s));
    }

    private static List<ItineraryDay> parseDays(JsonObject json, int wanted, double[] center)
            throws AiClient.AiException {
        JsonArray arr = json.has("days") && json.get("days").isJsonArray() ? json.getAsJsonArray("days") : null;
        if (arr == null || arr.isEmpty())
            throw new AiClient.AiException("The model's JSON had no \"days\" list.");

        List<ItineraryDay> days = new ArrayList<>();
        long stamp = System.currentTimeMillis();
        for (int i = 0; i < wanted; i++) {
            ItineraryDay day = new ItineraryDay();
            day.dayNumber = i + 1;
            JsonObject d = i < arr.size() && arr.get(i).isJsonObject() ? arr.get(i).getAsJsonObject()
                    : new JsonObject();
            day.title = str(d, "title", "Day " + (i + 1) + " Exploration");
            int n = 0;
            for (JsonObject s : stopObjects(d)) {
                ItineraryStop stop = parseStop(s, "stop-ai-" + stamp + "-" + i + "-" + n, center);
                if (stop == null)
                    continue;
                n++;
                day.stops.add(stop);
            }
            finishDay(day, "AI-generated plan");
            days.add(day);
        }
        if (days.stream().allMatch(d -> d.stops.isEmpty())) {
            throw new AiClient.AiException("The model's itinerary contained no usable stops.");
        }
        return days;
    }

    private static List<JsonObject> stopObjects(JsonObject day) {
        List<JsonObject> list = new ArrayList<>();
        if (day.has("stops") && day.get("stops").isJsonArray()) {
            for (JsonElement el : day.getAsJsonArray("stops"))
                if (el.isJsonObject())
                    list.add(el.getAsJsonObject());
        }
        return list;
    }

    /** One stop from the model's JSON, or null when it has no name. */
    private static ItineraryStop parseStop(JsonObject s, String id, double[] center) {
        String name = str(s, "name", null);
        if (name == null)
            return null;
        ItineraryStop stop = new ItineraryStop();
        stop.id = id;
        stop.name = name;
        stop.category = str(s, "category", "Sightseeing");
        stop.categoryIcon = ICONS.getOrDefault(stop.category.toLowerCase(Locale.ROOT), "bi-geo-alt");
        stop.time = str(s, "time", "");
        stop.duration = str(s, "duration", "1.5 hrs");
        stop.cost = Math.max(0, num(s, "cost", 0));
        stop.notes = str(s, "notes", "");
        Double lat = GeoService.parseCoordinate(str(s, "lat", null));
        Double lng = GeoService.parseCoordinate(str(s, "lng", null));
        boolean valid = GeoService.isValidCoordinate(lat, lng);
        boolean hasValidCenter = center != null && center.length == 2
                && GeoService.isValidCoordinate(center[0], center[1]);
        // Coordinates far from the destination are almost certainly invented
        if (valid && hasValidCenter
                && (Math.abs(lat - center[0]) > 1.5 || Math.abs(lng - center[1]) > 1.5))
            valid = false;
        if (valid) {
            stop.lat = lat;
            stop.lng = lng;
        } else if (hasValidCenter) {
            stop.lat = center[0] + (JITTER.nextDouble() - 0.5) * 0.04;
            stop.lng = center[1] + (JITTER.nextDouble() - 0.5) * 0.04;
        } else {
            stop.lat = null;
            stop.lng = null;
        }
        stop.transitToNext = parseTransit(s);
        return stop;
    }

    private static Transit parseTransit(JsonObject s) {
        if (!s.has("transitToNext") || !s.get("transitToNext").isJsonObject())
            return null;
        JsonObject t = s.getAsJsonObject("transitToNext");
        Transit transit = new Transit();
        transit.mode = str(t, "mode", "Cab");
        transit.icon = transitIcon(transit.mode);
        transit.duration = str(t, "duration", "");
        transit.distance = str(t, "distance", "");
        transit.cost = Math.max(0, num(t, "cost", 0));
        return transit;
    }

    private static void finishDay(ItineraryDay day, String label) {
        if (!day.stops.isEmpty())
            day.stops.get(day.stops.size() - 1).transitToNext = null;
        day.summary = day.stops.size() + " stops • " + label;
    }

    // ---------------------------------------------------------------- real map
    // details

    /** Destination centre: the one sent by the page, else looked up on the map. */
    private double[] resolveCenter(Request req) {
        if (req.center != null && req.center.length == 2
                && GeoService.isValidCoordinate(req.center[0], req.center[1])) {
            return req.center;
        }
        if (req.destination == null || req.destination.isBlank())
            return null;
        try {
            List<Place> hits = geoService.search(req.destination, null, 0, false, 1);
            if (!hits.isEmpty() && hits.get(0) != null
                    && GeoService.isValidCoordinate(hits.get(0).lat, hits.get(0).lng)) {
                return new double[] { hits.get(0).lat, hits.get(0).lng };
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Replaces the model's guessed coordinates with the real place from
     * OpenStreetMap and
     * fills in address, opening hours and website. Skips stops already linked to a
     * map place.
     * Bounded in count and time because the public map service allows one lookup
     * per second.
     */
    private int attachMapDetails(List<ItineraryDay> days, String destination, double[] center) {
        long deadline = System.currentTimeMillis() + GEOCODE_TIME_BUDGET_MS;
        int lookups = 0;
        int verified = 0;
        Map<String, Place> localCache = new java.util.HashMap<>();

        for (ItineraryDay day : days) {
            for (ItineraryStop stop : day.stops) {
                if (stop.placeRef != null) {
                    continue;
                }
                String nameKey = stop.name == null ? "" : stop.name.trim().toLowerCase(java.util.Locale.ROOT);
                if (nameKey.isBlank()) {
                    continue;
                }

                Place p;
                if (localCache.containsKey(nameKey)) {
                    p = localCache.get(nameKey);
                } else {
                    if (lookups >= MAX_GEOCODE_LOOKUPS || System.currentTimeMillis() > deadline) {
                        return sanitizeAndCountVerified(days, center, verified);
                    }
                    lookups++;
                    p = geoService.geocode(stop.name, destination, center);
                    localCache.put(nameKey, p);
                }

                if (p == null) {
                    continue;
                }

                // Preserve valid user-provided coordinates on non-AI-generated stops
                boolean isUserStop = stop.id != null && !stop.id.startsWith("stop-ai-") &&
                        GeoService.isValidCoordinate(stop.lat, stop.lng);
                if (!isUserStop && p != null && GeoService.isValidCoordinate(p.lat, p.lng)) {
                    stop.lat = p.lat;
                    stop.lng = p.lng;
                }
                stop.address = p.address;
                stop.placeRef = p.placeRef;
                if (p.openingHours != null)
                    stop.openingHours = p.openingHours;
                if (p.website != null)
                    stop.website = p.website;
                if (p.phone != null)
                    stop.phone = p.phone;
                verified++;
            }
        }
        return sanitizeAndCountVerified(days, center, verified);
    }

    private static int sanitizeAndCountVerified(List<ItineraryDay> days, double[] center, int verified) {
        boolean hasValidCenter = center != null && center.length == 2
                && GeoService.isValidCoordinate(center[0], center[1]);
        for (ItineraryDay day : days) {
            for (ItineraryStop stop : day.stops) {
                if (!GeoService.isValidCoordinate(stop.lat, stop.lng)) {
                    if (hasValidCenter) {
                        stop.lat = center[0];
                        stop.lng = center[1];
                    } else {
                        stop.lat = null;
                        stop.lng = null;
                    }
                }
            }
        }
        return verified;
    }

    private void enrichWeatherImpacts(List<ItineraryDay> days, String startDate, double[] center) {
        if (days == null || days.isEmpty() || center == null || center.length != 2
                || !GeoService.isValidCoordinate(center[0], center[1]) || weatherService == null) {
            return;
        }

        try {
            int forecastDays = Math.max(1, Math.min(days.size(), 16));
            WeatherData weatherData = weatherService.getWeather(center[0], center[1], forecastDays);
            if (weatherData == null || weatherData.daily == null || weatherData.daily.isEmpty()) {
                return;
            }

            java.time.LocalDate baseDate = null;
            if (startDate != null && !startDate.isBlank()) {
                try {
                    baseDate = java.time.LocalDate.parse(startDate.trim());
                } catch (Exception ignored) {
                    baseDate = null;
                }
            }

            for (int i = 0; i < days.size(); i++) {
                ItineraryDay day = days.get(i);
                if ((day.date == null || day.date.isBlank()) && baseDate != null) {
                    day.date = baseDate.plusDays(i).toString();
                }

                DailyForecast matched = null;

                // Match by day.date if present
                if (day.date != null && !day.date.isBlank()) {
                    String dStr = day.date.trim();
                    for (DailyForecast df : weatherData.daily) {
                        if (df.date != null && dStr.equalsIgnoreCase(df.date.trim())) {
                            matched = df;
                            break;
                        }
                    }
                }

                // If not matched by date string, match by offset index ONLY if day has no
                // calendar date
                if (matched == null && (day.date == null || day.date.isBlank()) && i < weatherData.daily.size()) {
                    matched = weatherData.daily.get(i);
                }

                if (matched != null) {
                    day.weather = matched;
                    for (ItineraryStop stop : day.stops) {
                        if (weatherService.getHeuristicsEngine().isStopWeatherImpacted(matched, stop.category)) {
                            stop.isWeatherImpacted = true;
                        } else if (!Boolean.TRUE.equals(stop.isWeatherReplaced)) {
                            stop.isWeatherImpacted = false;
                        }
                    }
                } else {
                    day.weather = null;
                    for (ItineraryStop stop : day.stops) {
                        if (!Boolean.TRUE.equals(stop.isWeatherReplaced)) {
                            stop.isWeatherImpacted = false;
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Gracefully continue itinerary generation when weather provider is unreachable
            // or fails
        }
    }

    // ---------------------------------------------------------------- tour plan
    // for an existing trip

    /**
     * POST /api/ai/tour-plan: the trip's current days plus how much the AI may
     * change.
     */
    public static class TourRequest extends Request {
        /**
         * "organize" = only schedule the given stops; "fill" = also suggest extra stops
         * and meals.
         */
        public String mode;
        public List<ItineraryDay> plan;
    }

    public static class TourResult {
        public List<ItineraryDay> days;
        public int added;
        public int placesVerified;
        public String model;
        public long elapsedMs;
    }

    public TourResult planTour(TourRequest req) throws AiClient.AiException {
        if (req == null || req.plan == null || req.plan.isEmpty())
            throw new AiClient.AiException("No days to plan.");
        if (req.plan.size() > 14)
            throw new AiClient.AiException("Can only plan up to 14 days at a time.");
        boolean fill = !"organize".equals(req.mode);
        long start = System.currentTimeMillis();
        double[] center = resolveCenter(req);

        String reply = new AiClient(settings, httpClient).chat(SYSTEM_PROMPT, tourPrompt(req, fill));
        JsonObject json = extractJson(reply);
        JsonArray arr = json.has("days") && json.get("days").isJsonArray() ? json.getAsJsonArray("days")
                : new JsonArray();

        TourResult result = new TourResult();
        result.days = new ArrayList<>();
        long stamp = System.currentTimeMillis();
        for (ItineraryDay original : req.plan) {
            JsonObject d = findDay(arr, original.dayNumber);
            ItineraryDay day = new ItineraryDay();
            day.dayNumber = original.dayNumber;
            day.date = original.date;
            day.title = d == null ? original.title : str(d, "title", original.title);
            List<ItineraryStop> remaining = new ArrayList<>(original.stops == null ? List.of() : original.stops);
            if (d != null) {
                int n = 0;
                for (JsonObject s : stopObjects(d)) {
                    String id = str(s, "id", null);
                    ItineraryStop mine = id == null ? null
                            : remaining.stream().filter(x -> id.equals(x.id)).findFirst().orElse(null);
                    if (mine != null) {
                        // The user's own stop: keep its place data, take the AI's schedule and tips
                        remaining.remove(mine);
                        mine.time = str(s, "time", mine.time);
                        mine.duration = str(s, "duration", mine.duration);
                        String tip = str(s, "notes", null);
                        if (tip != null && (mine.notes == null || mine.notes.isBlank()))
                            mine.notes = tip;
                        if (mine.cost <= 0)
                            mine.cost = Math.max(0, num(s, "cost", 0));
                        mine.transitToNext = parseTransit(s);
                        day.stops.add(mine);
                    } else if (fill && id == null) {
                        ItineraryStop added = parseStop(s, "stop-ai-" + stamp + "-" + day.dayNumber + "-" + (n++),
                                center);
                        if (added != null) {
                            day.stops.add(added);
                            result.added++;
                        }
                    }
                }
            }
            // Never lose a stop the model forgot to return
            day.stops.addAll(remaining);
            finishDay(day, "AI tour plan");
            result.days.add(day);
        }
        result.placesVerified = attachMapDetails(result.days, req.destination, center);
        enrichWeatherImpacts(result.days, req.startDate, center);
        result.model = settings.model;
        result.elapsedMs = System.currentTimeMillis() - start;
        return result;
    }

    private static JsonObject findDay(JsonArray arr, int dayNumber) {
        for (JsonElement el : arr) {
            if (el.isJsonObject() && (int) num(el.getAsJsonObject(), "dayNumber", -1) == dayNumber)
                return el.getAsJsonObject();
        }
        return null;
    }

    private static String tourPrompt(TourRequest r, boolean fill) {
        JsonArray days = new JsonArray();
        for (ItineraryDay d : r.plan) {
            JsonObject day = new JsonObject();
            day.addProperty("dayNumber", d.dayNumber);
            day.addProperty("title", d.title);
            JsonArray stops = new JsonArray();
            for (ItineraryStop s : d.stops == null ? List.<ItineraryStop>of() : d.stops) {
                JsonObject o = new JsonObject();
                o.addProperty("id", s.id);
                o.addProperty("name", s.name);
                o.addProperty("category", s.category);
                if (s.lat != null)
                    o.addProperty("lat", s.lat);
                if (s.lng != null)
                    o.addProperty("lng", s.lng);
                if (s.address != null)
                    o.addProperty("address", s.address);
                if (s.openingHours != null)
                    o.addProperty("openingHours", s.openingHours);
                stops.add(o);
            }
            day.add("stops", stops);
            days.add(day);
        }
        String stopsPerDay = switch (r.pace == null ? "" : r.pace) {
            case "Relaxed" -> "2 to 3";
            case "Packed" -> "5 to 6";
            default -> "3 to 5";
        };
        return """
                Create a timed tour plan for my trip to %s (%d travelers, total budget INR %.0f, interests: %s).
                These are the places I picked on the map, grouped by day:
                %s

                For EACH day above, return the stops in the best visiting order with realistic times \
                (respect openingHours when given), durations, short practical tips and the transit to the next stop.
                Rules:
                - Keep every one of my stops exactly once, in its own day, with its "id" copied unchanged.
                - %s
                - "cost" is the total in INR for all travelers.
                Return JSON only, in this shape:
                {"days":[{"dayNumber":1,"title":"theme of the day","stops":[{"id":"my stop id, or omit for a new stop",\
                "name":"...","category":"Heritage|Food|Nature|Shopping|Culture|Religious|Museum","time":"09:30 AM",\
                "duration":"2 hrs","cost":200,"lat":0,"lng":0,"notes":"...","transitToNext":{"mode":"Auto Rickshaw",\
                "duration":"10 mins","distance":"2 km","cost":50}}]}]}
                """.formatted(orDash(r.destination), Math.max(r.travelers, 1), r.budget,
                r.interests == null || r.interests.isEmpty() ? "general sightseeing" : String.join(", ", r.interests),
                days.toString(),
                fill ? "You may add new stops (without \"id\") so each day has " + stopsPerDay
                        + " stops including a lunch stop; new stops must be real, named places near the others."
                        : "Do NOT add any new stops; only schedule mine.");
    }

    private static String transitIcon(String mode) {
        String m = mode.toLowerCase(Locale.ROOT);
        if (m.contains("walk"))
            return "bi-person-walking";
        if (m.contains("auto") || m.contains("taxi"))
            return "bi-taxi-front";
        if (m.contains("bus"))
            return "bi-bus-front";
        if (m.contains("train") || m.contains("metro"))
            return "bi-train-front";
        if (m.contains("boat") || m.contains("ferry"))
            return "bi-water";
        return "bi-car-front";
    }

    private static String str(JsonObject o, String key, String fallback) {
        JsonElement el = o.get(key);
        if (el == null || el.isJsonNull() || !el.isJsonPrimitive())
            return fallback;
        String s = el.getAsString().trim();
        return s.isEmpty() ? fallback : s;
    }

    private static double num(JsonObject o, String key, double fallback) {
        JsonElement el = o.get(key);
        if (el == null || el.isJsonNull() || !el.isJsonPrimitive())
            return fallback;
        try {
            // Models sometimes write "₹1,200" or "200 INR"
            String s = el.getAsString().replaceAll("[^0-9.\\-]", "");
            return s.isEmpty() ? fallback : Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String orDash(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }

    private static String preview(String s) {
        s = s.replaceAll("\\s+", " ");
        return "\"" + (s.length() > 160 ? s.substring(0, 160) + "…" : s) + "\"";
    }
}
