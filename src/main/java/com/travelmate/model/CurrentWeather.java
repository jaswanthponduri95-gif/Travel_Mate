package com.travelmate.model;

/**
 * Normalized current weather observations.
 * Missing/unavailable measurements are left as null rather than defaulted to zero.
 */
public class CurrentWeather {
    public Double temperature;
    public Double apparentTemperature;
    public Integer relativeHumidity;
    public Double precipitation;
    public Integer weatherCode;
    public String condition;
    public String icon;
    public Double windSpeed;
    public Double windDirection;
    public Double uvIndex;
    public String time;
}

