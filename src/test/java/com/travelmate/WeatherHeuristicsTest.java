package com.travelmate;

import com.travelmate.model.*;
import com.travelmate.weather.WeatherHeuristicsEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deterministic unit tests for the rule-based WeatherHeuristicsEngine.
 */
public class WeatherHeuristicsTest {

    private WeatherHeuristicsEngine engine;

    @BeforeEach
    void setUp() {
        engine = new WeatherHeuristicsEngine();
    }

    @Test
    void testPleasantWeatherClassificationAndSuitability() {
        DailyForecast df = new DailyForecast();
        df.date = "2026-11-14";
        df.weatherCode = 0; // Clear sky
        df.tempMax = 26.0;
        df.tempMin = 18.0;
        df.precipitationSum = 0.0;
        df.precipitationProbability = 5;
        df.windSpeedMax = 12.0;
        df.uvIndexMax = 5.0;

        WeatherAdvice advice = engine.evaluate(df, 12.2958, 76.6394);

        assertEquals("Clear & Pleasant", advice.primaryCondition);
        assertFalse(advice.severeWarning);
        assertTrue(advice.impactScore >= 90, "Pleasant weather should have high score: " + advice.impactScore);
        assertEquals("Optimal", advice.impactRating);

        // Sightseeing, Outdoor, Beach, Hiking should all be SUITABLE
        assertActivitySuitability(advice.activities, "Sightseeing", SuitabilityLevel.SUITABLE);
        assertActivitySuitability(advice.activities, "Outdoor Activities", SuitabilityLevel.SUITABLE);
        assertActivitySuitability(advice.activities, "Beach Activities", SuitabilityLevel.SUITABLE);
        assertActivitySuitability(advice.activities, "Hiking & Trekking", SuitabilityLevel.SUITABLE);
        assertActivitySuitability(advice.activities, "Indoor Activities", SuitabilityLevel.SUITABLE);
    }

    @Test
    void testCloudyConditions() {
        DailyForecast df = new DailyForecast();
        df.date = "2026-11-15";
        df.weatherCode = 3; // Overcast
        df.tempMax = 24.0;
        df.tempMin = 19.0;
        df.precipitationSum = 0.2;
        df.precipitationProbability = 15;
        df.windSpeedMax = 14.0;

        WeatherAdvice advice = engine.evaluate(df, 12.2958, 76.6394);

        assertEquals("Cloudy", advice.primaryCondition);
        assertFalse(advice.severeWarning);
        assertTrue(advice.impactScore >= 80, "Cloudy conditions without rain should score high");
        assertEquals("Optimal", advice.impactRating);
    }

    @Test
    void testLightRainConditions() {
        DailyForecast df = new DailyForecast();
        df.date = "2026-11-16";
        df.weatherCode = 51; // Light drizzle
        df.tempMax = 23.0;
        df.tempMin = 19.0;
        df.precipitationSum = 2.5;
        df.precipitationProbability = 50;
        df.windSpeedMax = 15.0;

        WeatherAdvice advice = engine.evaluate(df, 12.2958, 76.6394);

        assertEquals("Light Rain", advice.primaryCondition);
        assertFalse(advice.severeWarning);
        assertTrue(advice.impactScore >= 60 && advice.impactScore <= 85);

        assertActivitySuitability(advice.activities, "Outdoor Activities", SuitabilityLevel.SUITABLE_WITH_PRECAUTIONS);
        assertActivitySuitability(advice.activities, "Indoor Activities", SuitabilityLevel.SUITABLE);

        // Should have clothing recommendation
        assertTrue(advice.recommendations.stream().anyMatch(r -> r.category.equalsIgnoreCase("Clothing")));
    }

    @Test
    void testHeavyRainConditions() {
        DailyForecast df = new DailyForecast();
        df.date = "2026-11-17";
        df.weatherCode = 65; // Heavy rain
        df.tempMax = 22.0;
        df.tempMin = 18.0;
        df.precipitationSum = 18.0;
        df.precipitationProbability = 90;
        df.windSpeedMax = 25.0;

        WeatherAdvice advice = engine.evaluate(df, 12.2958, 76.6394);

        assertEquals("Heavy Rain", advice.primaryCondition);
        assertTrue(advice.impactScore < 65, "Heavy rain should lower the score significantly");

        assertActivitySuitability(advice.activities, "Outdoor Activities", SuitabilityLevel.NOT_RECOMMENDED);
        assertActivitySuitability(advice.activities, "Hiking & Trekking", SuitabilityLevel.NOT_RECOMMENDED);
        assertActivitySuitability(advice.activities, "Beach Activities", SuitabilityLevel.NOT_RECOMMENDED);
        assertActivitySuitability(advice.activities, "Indoor Activities", SuitabilityLevel.SUITABLE);

        // Smart alternative suggestion should be present
        assertTrue(advice.recommendations.stream().anyMatch(r -> r.category.equalsIgnoreCase("Alternative")));
    }

    @Test
    void testThunderstormSevereWarningPriority() {
        DailyForecast df = new DailyForecast();
        df.date = "2026-11-18";
        df.weatherCode = 95; // Thunderstorm
        df.tempMax = 27.0; // Mild temperature
        df.tempMin = 20.0;
        df.precipitationSum = 12.0;
        df.precipitationProbability = 85;
        df.windSpeedMax = 30.0;

        WeatherAdvice advice = engine.evaluate(df, 12.2958, 76.6394);

        assertEquals("Thunderstorm", advice.primaryCondition);
        assertTrue(advice.severeWarning, "Thunderstorm must trigger severeWarning = true");
        assertEquals("Severe", advice.impactRating, "Severe rating must be assigned regardless of mild temp");
        assertTrue(advice.impactScore <= 45, "Thunderstorm score must be capped <= 45: " + advice.impactScore);

        assertActivitySuitability(advice.activities, "Sightseeing", SuitabilityLevel.NOT_RECOMMENDED);
        assertActivitySuitability(advice.activities, "Outdoor Activities", SuitabilityLevel.NOT_RECOMMENDED);
        assertActivitySuitability(advice.activities, "Hiking & Trekking", SuitabilityLevel.NOT_RECOMMENDED);
        assertActivitySuitability(advice.activities, "Beach Activities", SuitabilityLevel.NOT_RECOMMENDED);
        assertActivitySuitability(advice.activities, "Indoor Activities", SuitabilityLevel.SUITABLE);

        // Critical safety recommendation
        assertTrue(advice.recommendations.stream().anyMatch(r -> "CRITICAL".equals(r.severity) && "Safety".equals(r.category)));
    }

    @Test
    void testExtremeHeatPrecautions() {
        DailyForecast df = new DailyForecast();
        df.date = "2026-05-15";
        df.weatherCode = 0; // Clear
        df.tempMax = 41.5; // Extreme heat
        df.tempMin = 28.0;
        df.precipitationSum = 0.0;
        df.precipitationProbability = 0;
        df.windSpeedMax = 10.0;

        WeatherAdvice advice = engine.evaluate(df, 28.6139, 77.2090);

        assertEquals("Extreme Heat", advice.primaryCondition);
        assertActivitySuitability(advice.activities, "Hiking & Trekking", SuitabilityLevel.NOT_RECOMMENDED);
        assertActivitySuitability(advice.activities, "Sightseeing", SuitabilityLevel.SUITABLE_WITH_PRECAUTIONS);
        assertActivitySuitability(advice.activities, "Indoor Activities", SuitabilityLevel.SUITABLE);

        // Timing recommendation for heat
        assertTrue(advice.recommendations.stream().anyMatch(r -> "Timing".equals(r.category) && "WARNING".equals(r.severity)));
    }

    @Test
    void testStrongWindsAdvisory() {
        DailyForecast df = new DailyForecast();
        df.date = "2026-11-20";
        df.weatherCode = 2; // Partly cloudy
        df.tempMax = 25.0;
        df.tempMin = 20.0;
        df.precipitationSum = 0.0;
        df.precipitationProbability = 10;
        df.windSpeedMax = 52.0; // Strong wind

        WeatherAdvice advice = engine.evaluate(df, 15.2993, 74.1240);

        assertEquals("Strong Wind", advice.primaryCondition);
        assertActivitySuitability(advice.activities, "Beach Activities", SuitabilityLevel.NOT_RECOMMENDED);
        assertActivitySuitability(advice.activities, "Hiking & Trekking", SuitabilityLevel.NOT_RECOMMENDED);
        assertTrue(advice.recommendations.stream().anyMatch(r -> "Safety".equals(r.category) && r.title.contains("Wind")));
    }

    @Test
    void testMissingPrecipitationProbabilityGracefulFallback() {
        DailyForecast df = new DailyForecast();
        df.date = "2026-11-21";
        df.weatherCode = 61; // Slight rain
        df.tempMax = 25.0;
        df.tempMin = 20.0;
        df.precipitationSum = 2.0;
        df.precipitationProbability = null; // Missing
        df.windSpeedMax = 12.0;

        WeatherAdvice advice = engine.evaluate(df, 12.2958, 76.6394);

        assertNotNull(advice);
        assertEquals("Light Rain", advice.primaryCondition);
        assertTrue(advice.impactScore > 0 && advice.impactScore <= 100);
    }

    @Test
    void testMissingTemperatureGracefulFallback() {
        DailyForecast df = new DailyForecast();
        df.date = "2026-11-22";
        df.weatherCode = 1;
        df.tempMax = null; // Missing
        df.tempMin = null; // Missing
        df.precipitationSum = 0.0;
        df.precipitationProbability = 10;
        df.windSpeedMax = 15.0;

        WeatherAdvice advice = engine.evaluate(df, 12.2958, 76.6394);

        assertNotNull(advice);
        assertFalse(advice.severeWarning);
        assertTrue(advice.impactScore >= 70);
    }

    @Test
    void testMultipleSimultaneousWeatherConditions() {
        DailyForecast df = new DailyForecast();
        df.date = "2026-11-23";
        df.weatherCode = 65; // Heavy rain
        df.tempMax = 37.0; // High heat
        df.tempMin = 24.0;
        df.precipitationSum = 25.0;
        df.precipitationProbability = 95;
        df.windSpeedMax = 42.0; // Strong wind

        WeatherAdvice advice = engine.evaluate(df, 12.2958, 76.6394);

        // Deductions stack logically
        assertTrue(advice.impactScore <= 35, "Multiple compounding severe factors must reduce score: " + advice.impactScore);
        assertEquals("Severe", advice.impactRating);
    }

    @Test
    void testSevereWeatherWarningPriorityOverPositiveScore() {
        DailyForecast df = new DailyForecast();
        df.date = "2026-11-24";
        df.weatherCode = 95; // Thunderstorm
        df.tempMax = 25.0; // Optimal temp
        df.tempMin = 18.0;
        df.precipitationSum = 2.0;
        df.precipitationProbability = 70;
        df.windSpeedMax = 15.0;

        WeatherAdvice advice = engine.evaluate(df, 12.2958, 76.6394);

        assertTrue(advice.severeWarning);
        assertEquals("Severe", advice.impactRating, "Severe warning must force 'Severe' rating");
    }

    @Test
    void testDeterministicScoringForIdenticalInputs() {
        DailyForecast df1 = new DailyForecast();
        df1.weatherCode = 2;
        df1.tempMax = 28.5;
        df1.tempMin = 18.0;
        df1.precipitationSum = 1.2;
        df1.precipitationProbability = 30;
        df1.windSpeedMax = 16.0;

        DailyForecast df2 = new DailyForecast();
        df2.weatherCode = 2;
        df2.tempMax = 28.5;
        df2.tempMin = 18.0;
        df2.precipitationSum = 1.2;
        df2.precipitationProbability = 30;
        df2.windSpeedMax = 16.0;

        int score1 = engine.calculateImpactScore(df1);
        int score2 = engine.calculateImpactScore(df2);

        assertEquals(score1, score2, "Scores must be strictly deterministic");
    }

    @Test
    void testActivitySuitabilityAllSixActivities() {
        DailyForecast df = new DailyForecast();
        df.weatherCode = 0;
        df.tempMax = 26.0;
        df.tempMin = 18.0;

        List<ActivitySuitability> activities = engine.evaluateActivities(df, false);
        assertEquals(6, activities.size());
        assertTrue(activities.stream().anyMatch(a -> "Sightseeing".equals(a.activity)));
        assertTrue(activities.stream().anyMatch(a -> "Outdoor Activities".equals(a.activity)));
        assertTrue(activities.stream().anyMatch(a -> "Beach Activities".equals(a.activity)));
        assertTrue(activities.stream().anyMatch(a -> "Hiking & Trekking".equals(a.activity)));
        assertTrue(activities.stream().anyMatch(a -> "City Exploration".equals(a.activity)));
        assertTrue(activities.stream().anyMatch(a -> "Indoor Activities".equals(a.activity)));
    }

    @Test
    void testIndoorActivitiesAlwaysSuitable() {
        // Even in thunderstorm
        DailyForecast df = new DailyForecast();
        df.weatherCode = 95;
        df.precipitationSum = 40.0;
        df.precipitationProbability = 100;

        List<ActivitySuitability> activities = engine.evaluateActivities(df, true);
        assertActivitySuitability(activities, "Indoor Activities", SuitabilityLevel.SUITABLE);
    }

    @Test
    void testStopVulnerabilityDetectionOutdoorVsIndoor() {
        DailyForecast rainDay = new DailyForecast();
        rainDay.weatherCode = 65; // Heavy rain
        rainDay.precipitationSum = 15.0;
        rainDay.precipitationProbability = 90;

        assertTrue(engine.isStopWeatherImpacted(rainDay, "Nature"), "Nature stops must be weather impacted during heavy rain");
        assertTrue(engine.isStopWeatherImpacted(rainDay, "Outdoor"), "Outdoor stops must be impacted");
        assertTrue(engine.isStopWeatherImpacted(rainDay, "Beach"), "Beach stops must be impacted");
        assertTrue(engine.isStopWeatherImpacted(rainDay, "Sightseeing"), "Sightseeing stops must be impacted");

        assertFalse(engine.isStopWeatherImpacted(rainDay, "Museum"), "Museums must NOT be impacted");
        assertFalse(engine.isStopWeatherImpacted(rainDay, "Food"), "Dining stops must NOT be impacted");
        assertFalse(engine.isStopWeatherImpacted(rainDay, "Shopping"), "Shopping stops must NOT be impacted");
        assertFalse(engine.isStopWeatherImpacted(rainDay, "Stay"), "Hotels must NOT be impacted");
    }

    @Test
    void testNullForecastHandling() {
        WeatherAdvice advice = engine.evaluate(null, 12.0, 76.0);
        assertNotNull(advice);
        assertEquals("Unknown Condition", advice.primaryCondition);
        assertEquals(6, advice.activities.size());
        assertFalse(advice.recommendations.isEmpty());
    }

    private void assertActivitySuitability(List<ActivitySuitability> list, String activityName, SuitabilityLevel expected) {
        ActivitySuitability match = list.stream()
                .filter(a -> a.activity.equalsIgnoreCase(activityName))
                .findFirst()
                .orElse(null);
        assertNotNull(match, "Activity not found: " + activityName);
        assertEquals(expected, match.suitability, "Suitability mismatch for activity: " + activityName);
    }
}

