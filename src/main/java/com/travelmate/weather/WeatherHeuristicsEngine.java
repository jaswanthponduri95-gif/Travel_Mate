package com.travelmate.weather;

import com.travelmate.model.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic rule-based engine that evaluates weather metrics and produces
 * travel suitability advice, activity assessments, safety alerts, and impact scores.
 *
 * Designed to be independently testable without network access or AI dependencies.
 */
public class WeatherHeuristicsEngine {

    public static final int SCORE_OPTIMAL_THRESHOLD = 80;
    public static final int SCORE_GOOD_THRESHOLD = 60;
    public static final int SCORE_CHALLENGING_THRESHOLD = 40;

    /**
     * Evaluates a daily forecast and generates comprehensive travel advice.
     */
    public WeatherAdvice evaluate(DailyForecast f, Double lat, Double lng) {
        WeatherAdvice advice = new WeatherAdvice();
        advice.success = true;
        advice.latitude = lat;
        advice.longitude = lng;
        advice.forecast = f;

        if (f == null) {
            advice.primaryCondition = "Unknown Condition";
            advice.impactScore = 70;
            advice.impactRating = "Good";
            advice.severeWarning = false;
            populateDefaultActivities(advice);
            advice.recommendations.add(new RecommendationItem(
                    "Weather Unavailable",
                    "No forecast details available for this date. Check local conditions on arrival.",
                    "General", "INFO", "bi-info-circle"));
            return advice;
        }

        advice.targetDate = f.date;
        advice.primaryCondition = classifyCondition(f);
        advice.severeWarning = isSevereWeather(f);

        // 1. Calculate deterministic impact score
        advice.impactScore = calculateImpactScore(f);
        advice.impactRating = determineRating(advice.impactScore, advice.severeWarning);

        // 2. Evaluate activity suitability
        advice.activities = evaluateActivities(f, advice.severeWarning);

        // 3. Generate practical rule-based recommendations
        advice.recommendations = generateRecommendations(f, advice.primaryCondition, advice.severeWarning);

        return advice;
    }

    /**
     * Classifies primary weather condition using actual measurements and WMO weather codes.
     */
    public String classifyCondition(DailyForecast f) {
        if (f == null) return "Unknown";
        int code = f.weatherCode != null ? f.weatherCode : -1;

        if (isThunderstormCode(code)) {
            return "Thunderstorm";
        }
        if (isHeavyRainCode(code) || (f.precipitationSum != null && f.precipitationSum >= 10.0)
                || (f.precipitationProbability != null && f.precipitationProbability >= 80 && f.precipitationSum != null && f.precipitationSum >= 5.0)) {
            return "Heavy Rain";
        }
        if (isLightRainCode(code) || (f.precipitationSum != null && f.precipitationSum > 0.5)
                || (f.precipitationProbability != null && f.precipitationProbability >= 40)) {
            return "Light Rain";
        }
        if (f.tempMax != null && f.tempMax >= 38.0) {
            return "Extreme Heat";
        }
        if (f.windSpeedMax != null && f.windSpeedMax >= 40.0) {
            return "Strong Wind";
        }
        if (code == 2 || code == 3) {
            return "Cloudy";
        }
        if (code == 0 || code == 1) {
            if (f.tempMax != null && f.tempMax >= 18.0 && f.tempMax <= 30.0) {
                return "Clear & Pleasant";
            }
            return "Clear";
        }
        if (code == 45 || code == 48) {
            return "Foggy";
        }
        if (code >= 71 && code <= 77) {
            return "Snowfall";
        }
        return f.condition != null ? f.condition : "Partly Cloudy";
    }

    /**
     * Deterministic impact score (0 to 100). Higher score indicates better outdoor travel conditions.
     */
    public int calculateImpactScore(DailyForecast f) {
        if (f == null) return 70;
        int score = 100;

        int code = f.weatherCode != null ? f.weatherCode : -1;

        // Severe weather deduction
        if (isThunderstormCode(code)) {
            score -= 50;
        } else if (isHeavyRainCode(code) || (f.precipitationSum != null && f.precipitationSum >= 10.0)) {
            score -= 35;
        } else if (isLightRainCode(code) || (f.precipitationSum != null && f.precipitationSum > 1.0)) {
            score -= 15;
        } else if (f.precipitationProbability != null && f.precipitationProbability >= 50) {
            score -= 10;
        }

        // Temperature extremes
        if (f.tempMax != null) {
            if (f.tempMax >= 40.0) {
                score -= 30;
            } else if (f.tempMax >= 36.0) {
                score -= 20;
            } else if (f.tempMax >= 33.0) {
                score -= 10;
            }
        }
        if (f.tempMin != null) {
            if (f.tempMin < 0.0) {
                score -= 30;
            } else if (f.tempMin < 5.0) {
                score -= 15;
            }
        }

        // Wind speed
        if (f.windSpeedMax != null) {
            if (f.windSpeedMax >= 50.0) {
                score -= 25;
            } else if (f.windSpeedMax >= 35.0) {
                score -= 15;
            } else if (f.windSpeedMax >= 25.0) {
                score -= 5;
            }
        }

        // UV Index
        if (f.uvIndexMax != null && f.uvIndexMax >= 9.0) {
            score -= 10;
        }

        // Severe weather safety cap
        if (isThunderstormCode(code)) {
            score = Math.min(score, 45);
        }

        return Math.max(0, Math.min(score, 100));
    }

    public boolean isSevereWeather(DailyForecast f) {
        if (f == null) return false;
        int code = f.weatherCode != null ? f.weatherCode : -1;
        if (isThunderstormCode(code)) return true;
        if (f.windSpeedMax != null && f.windSpeedMax >= 55.0) return true;
        if (f.precipitationSum != null && f.precipitationSum >= 30.0) return true;
        if (f.tempMax != null && f.tempMax >= 43.0) return true;
        return false;
    }

    private String determineRating(int score, boolean severeWarning) {
        if (severeWarning || score < SCORE_CHALLENGING_THRESHOLD) {
            return "Severe";
        }
        if (score < SCORE_GOOD_THRESHOLD) {
            return "Challenging";
        }
        if (score < SCORE_OPTIMAL_THRESHOLD) {
            return "Good";
        }
        return "Optimal";
    }

    /**
     * Evaluates suitability for key travel activities based on available weather conditions.
     */
    public List<ActivitySuitability> evaluateActivities(DailyForecast f, boolean severe) {
        List<ActivitySuitability> list = new ArrayList<>();
        int code = f != null && f.weatherCode != null ? f.weatherCode : -1;
        double rainSum = f != null && f.precipitationSum != null ? f.precipitationSum : 0.0;
        int rainProb = f != null && f.precipitationProbability != null ? f.precipitationProbability : 0;
        double tempMax = f != null && f.tempMax != null ? f.tempMax : 25.0;
        double wind = f != null && f.windSpeedMax != null ? f.windSpeedMax : 10.0;

        boolean isRainy = isHeavyRainCode(code) || rainSum >= 6.0 || rainProb >= 70;
        boolean isLightRain = isLightRainCode(code) || (rainSum > 0.5 && rainSum < 6.0) || (rainProb >= 35 && rainProb < 70);
        boolean isExtremeHeat = tempMax >= 38.0;
        boolean isHot = tempMax >= 34.0;
        boolean isWindy = wind >= 38.0;

        // 1. Sightseeing
        if (severe || isThunderstormCode(code)) {
            list.add(new ActivitySuitability("Sightseeing", SuitabilityLevel.NOT_RECOMMENDED,
                    "Thunderstorms or severe weather make outdoor monuments unsafe.", "bi-camera"));
        } else if (isRainy) {
            list.add(new ActivitySuitability("Sightseeing", SuitabilityLevel.SUITABLE_WITH_PRECAUTIONS,
                    "Rain showers expected; prefer monuments with covered colonnades and carry rain gear.", "bi-camera"));
        } else if (isHot) {
            list.add(new ActivitySuitability("Sightseeing", SuitabilityLevel.SUITABLE_WITH_PRECAUTIONS,
                    "High midday temperatures; visit outdoor monuments in early morning or late afternoon.", "bi-camera"));
        } else {
            list.add(new ActivitySuitability("Sightseeing", SuitabilityLevel.SUITABLE,
                    "Favorable weather for open-air heritage and landmark sightseeing.", "bi-camera"));
        }

        // 2. Outdoor Activities (Gardens, Parks, Promenades)
        if (severe || isThunderstormCode(code) || isRainy) {
            list.add(new ActivitySuitability("Outdoor Activities", SuitabilityLevel.NOT_RECOMMENDED,
                    "Heavy rain or lightning risk makes open parks and gardens impractical.", "bi-tree"));
        } else if (isLightRain) {
            list.add(new ActivitySuitability("Outdoor Activities", SuitabilityLevel.SUITABLE_WITH_PRECAUTIONS,
                    "Occasional drizzle possible; carry an umbrella or lightweight raincoat.", "bi-tree"));
        } else if (isHot) {
            list.add(new ActivitySuitability("Outdoor Activities", SuitabilityLevel.SUITABLE_WITH_PRECAUTIONS,
                    "Midday heat peak; seek shaded arboretums or evening promenades.", "bi-tree"));
        } else {
            list.add(new ActivitySuitability("Outdoor Activities", SuitabilityLevel.SUITABLE,
                    "Pleasant outdoor conditions for garden strolls and botanical parks.", "bi-tree"));
        }

        // 3. Beach Activities
        if (severe || isThunderstormCode(code) || isWindy) {
            list.add(new ActivitySuitability("Beach Activities", SuitabilityLevel.NOT_RECOMMENDED,
                    "High swell, rough surf, or lightning hazard along open coastlines.", "bi-water"));
        } else if (isRainy) {
            list.add(new ActivitySuitability("Beach Activities", SuitabilityLevel.NOT_RECOMMENDED,
                    "Rain and choppy waters make beach visits uncomfortable and unsafe.", "bi-water"));
        } else if (isHot) {
            list.add(new ActivitySuitability("Beach Activities", SuitabilityLevel.SUITABLE_WITH_PRECAUTIONS,
                    "High UV index; use sun protection and stay hydrated.", "bi-water"));
        } else {
            list.add(new ActivitySuitability("Beach Activities", SuitabilityLevel.SUITABLE,
                    "Calm coastal conditions suitable for beach walks and relaxing.", "bi-water"));
        }

        // 4. Hiking / Trekking
        if (severe || isThunderstormCode(code) || isRainy || isWindy || isExtremeHeat) {
            list.add(new ActivitySuitability("Hiking & Trekking", SuitabilityLevel.NOT_RECOMMENDED,
                    isExtremeHeat ? "Extreme heat presents severe dehydration and heat exhaustion risk on open trails."
                            : "Slippery trails, poor visibility, and wind or lightning exposure.", "bi-signpost-2"));
        } else if (isHot) {
            list.add(new ActivitySuitability("Hiking & Trekking", SuitabilityLevel.SUITABLE_WITH_PRECAUTIONS,
                    "Strenuous trails carry dehydration risk; start before 7:00 AM.", "bi-signpost-2"));
        } else {
            list.add(new ActivitySuitability("Hiking & Trekking", SuitabilityLevel.SUITABLE,
                    "Firm trail conditions and good visibility for nature treks.", "bi-signpost-2"));
        }

        // 5. City Exploration
        if (severe || isThunderstormCode(code)) {
            list.add(new ActivitySuitability("City Exploration", SuitabilityLevel.SUITABLE_WITH_PRECAUTIONS,
                    "Use cab/transit between destinations; avoid prolonged outdoor walks.", "bi-compass"));
        } else if (isRainy) {
            list.add(new ActivitySuitability("City Exploration", SuitabilityLevel.SUITABLE_WITH_PRECAUTIONS,
                    "Paved streets allow exploration with umbrellas; combine with indoor markets.", "bi-compass"));
        } else {
            list.add(new ActivitySuitability("City Exploration", SuitabilityLevel.SUITABLE,
                    "Comfortable urban conditions for walking tours and bazaars.", "bi-compass"));
        }

        // 6. Indoor Activities (Museums, Art Galleries, Food, Shopping)
        if (isRainy || isThunderstormCode(code) || severe) {
            list.add(new ActivitySuitability("Indoor Activities", SuitabilityLevel.SUITABLE,
                    "Highly recommended alternative: sheltered museums, galleries, and palace interiors unaffected by rain.", "bi-building"));
        } else {
            list.add(new ActivitySuitability("Indoor Activities", SuitabilityLevel.SUITABLE,
                    "Always suitable: museums, cultural exhibits, cafes, and shopping pavilions.", "bi-building"));
        }

        return list;
    }

    /**
     * Generates concrete, actionable advice items tailored to specific forecast flags.
     */
    public List<RecommendationItem> generateRecommendations(DailyForecast f, String condition, boolean severe) {
        List<RecommendationItem> recs = new ArrayList<>();
        if (f == null) return recs;

        int code = f.weatherCode != null ? f.weatherCode : -1;
        double rainSum = f.precipitationSum != null ? f.precipitationSum : 0.0;
        int rainProb = f.precipitationProbability != null ? f.precipitationProbability : 0;
        double tempMax = f.tempMax != null ? f.tempMax : 25.0;
        double wind = f.windSpeedMax != null ? f.windSpeedMax : 10.0;
        double uv = f.uvIndexMax != null ? f.uvIndexMax : 5.0;

        // Safety Warnings (Highest Priority)
        if (isThunderstormCode(code)) {
            recs.add(new RecommendationItem(
                    "Thunderstorm Safety Alert",
                    "Thunderstorms forecast. Avoid open ground, tall trees, exposed viewpoints, and water bodies. Seek shelter indoors immediately when thunder sounds.",
                    "Safety", "CRITICAL", "bi-cloud-lightning-rain-fill"));
        }
        if (wind >= 50.0) {
            recs.add(new RecommendationItem(
                    "High Wind Advisory",
                    String.format(Locale.ROOT, "Gusty winds reaching %.0f km/h forecast. Exercise caution on elevated balconies, ghat roads, and water ferry crossings.", wind),
                    "Safety", "WARNING", "bi-wind"));
        }

        // Precipitation / Clothing Advice
        if (isHeavyRainCode(code) || rainSum >= 8.0 || rainProb >= 75) {
            recs.add(new RecommendationItem(
                    "Heavy Rainfall Preparation",
                    String.format(Locale.ROOT, "Significant rain expected (%.1f mm, %d%% probability). Carry sturdy waterproof umbrellas and protective covers for electronics.", rainSum, rainProb),
                    "Clothing", "WARNING", "bi-umbrella-fill"));
            recs.add(new RecommendationItem(
                    "Smart Indoor Alternative",
                    "Prioritize sheltered cultural destinations, covered art galleries, museums, and indoor markets over open gardens or fountains.",
                    "Alternative", "INFO", "bi-building-check"));
        } else if (isLightRainCode(code) || rainSum > 1.0 || rainProb >= 40) {
            recs.add(new RecommendationItem(
                    "Rain Protection Recommended",
                    String.format(Locale.ROOT, "%d%% probability of intermittent showers. A compact travel umbrella or lightweight windbreaker is recommended.", rainProb),
                    "Clothing", "INFO", "bi-umbrella"));
        }

        // Heat & Sun Advice
        if (tempMax >= 38.0) {
            recs.add(new RecommendationItem(
                    "Extreme Heat Precautions",
                    String.format(Locale.ROOT, "Temperatures expected to peak at %.1f°C. Schedule outdoor walking before 10:30 AM or after 4:30 PM. Carry minimum 2L water per person.", tempMax),
                    "Timing", "WARNING", "bi-thermometer-sun"));
        } else if (tempMax >= 33.0) {
            recs.add(new RecommendationItem(
                    "Warm Midday Window",
                    String.format(Locale.ROOT, "Afternoon highs near %.1f°C. Plan indoor museum visits or lunch during 12:00 PM – 3:00 PM.", tempMax),
                    "Timing", "INFO", "bi-sun"));
        }

        // UV Index Advice
        if (uv >= 8.0) {
            recs.add(new RecommendationItem(
                    "High UV Index Advisory",
                    String.format(Locale.ROOT, "Peak UV index reaches %.1f. Apply broad-spectrum sunscreen (SPF 30+) and wear sunglasses or a wide-brim hat.", uv),
                    "Clothing", "INFO", "bi-brightness-high-fill"));
        }

        // Favorable Conditions
        if (!severe && (code == 0 || code == 1 || code == 2) && rainSum < 0.5 && rainProb < 20 && tempMax <= 30.0 && tempMax >= 18.0) {
            recs.add(new RecommendationItem(
                    "Prime Sightseeing Weather",
                    "Clear skies and mild temperatures provide ideal conditions for full-day photography, walking tours, and open-air heritage exploration.",
                    "General", "INFO", "bi-award"));
        }

        return recs;
    }

    /**
     * Determines whether an itinerary stop is weather-impacted by rain or severe conditions.
     */
    public boolean isStopWeatherImpacted(DailyForecast forecast, String stopCategory) {
        if (forecast == null || stopCategory == null) return false;
        String cat = stopCategory.toLowerCase(Locale.ROOT);

        // Indoor categories are weather-proof
        if (cat.contains("museum") || cat.contains("food") || cat.contains("stay")
                || cat.contains("hotel") || cat.contains("shop") || cat.contains("indoor")
                || cat.contains("restaurant") || cat.contains("cafe")) {
            return false;
        }

        // Outdoor categories are vulnerable to rain and severe weather
        boolean isOutdoor = cat.contains("nature") || cat.contains("outdoor") || cat.contains("park")
                || cat.contains("garden") || cat.contains("beach") || cat.contains("fountain")
                || cat.contains("promenade") || cat.contains("adventure") || cat.contains("sightseeing")
                || cat.contains("heritage");

        if (!isOutdoor) return false;

        int code = forecast.weatherCode != null ? forecast.weatherCode : -1;
        double rainSum = forecast.precipitationSum != null ? forecast.precipitationSum : 0.0;
        int rainProb = forecast.precipitationProbability != null ? forecast.precipitationProbability : 0;

        return isThunderstormCode(code) || isHeavyRainCode(code) || rainSum >= 5.0 || rainProb >= 65;
    }

    private void populateDefaultActivities(WeatherAdvice advice) {
        advice.activities.add(new ActivitySuitability("Sightseeing", SuitabilityLevel.SUITABLE, "Conditions unverified; verify locally.", "bi-camera"));
        advice.activities.add(new ActivitySuitability("Outdoor Activities", SuitabilityLevel.SUITABLE, "Conditions unverified; verify locally.", "bi-tree"));
        advice.activities.add(new ActivitySuitability("Beach Activities", SuitabilityLevel.SUITABLE, "Conditions unverified; verify locally.", "bi-water"));
        advice.activities.add(new ActivitySuitability("Hiking & Trekking", SuitabilityLevel.SUITABLE, "Conditions unverified; verify locally.", "bi-signpost-2"));
        advice.activities.add(new ActivitySuitability("City Exploration", SuitabilityLevel.SUITABLE, "Conditions unverified; verify locally.", "bi-compass"));
        advice.activities.add(new ActivitySuitability("Indoor Activities", SuitabilityLevel.SUITABLE, "Always suitable.", "bi-building"));
    }

    private static boolean isThunderstormCode(int code) {
        return code == 95 || code == 96 || code == 99;
    }

    private static boolean isHeavyRainCode(int code) {
        return code == 63 || code == 65 || code == 81 || code == 82;
    }

    private static boolean isLightRainCode(int code) {
        return (code >= 51 && code <= 57) || code == 61 || code == 80;
    }
}
