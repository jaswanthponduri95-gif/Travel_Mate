package com.travelmate.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Complete normalized weather bundle for a geographic location.
 */
public class WeatherData {
    public boolean success = true;
    public Double latitude;
    public Double longitude;
    public String timezone;
    public Double elevation;
    public CurrentWeather current;
    public List<DailyForecast> daily = new ArrayList<>();
    public List<HourlyForecast> hourly = new ArrayList<>();
    public String provider = "Open-Meteo";
    public String attribution = "Weather data by Open-Meteo.com (CC BY 4.0)";
    public long retrievedAt;
    public boolean isStale;
}

