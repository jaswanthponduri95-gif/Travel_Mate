package com.travelmate.model;

/**
 * Normalized hourly forecast slice.
 * Missing/unavailable measurements are left as null rather than defaulted to zero.
 */
public class HourlyForecast {
    public String time; // ISO string e.g. 2026-11-04T12:00
    public Double temperature;
    public Integer precipitationProbability;
    public Double precipitation;
    public Integer weatherCode;
    public String condition;
    public String icon;
    public Double uvIndex;
}

