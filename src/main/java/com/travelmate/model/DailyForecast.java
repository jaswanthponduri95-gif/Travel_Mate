package com.travelmate.model;

/**
 * Normalized daily forecast metrics.
 * Missing/unavailable measurements are left as null rather than defaulted to zero.
 */
public class DailyForecast {
    public String date; // YYYY-MM-DD
    public Integer weatherCode;
    public String condition;
    public String icon;
    public Double tempMax;
    public Double tempMin;
    public Double apparentTempMax;
    public Double apparentTempMin;
    public Double precipitationSum;
    public Integer precipitationProbability;
    public Double windSpeedMax;
    public Double uvIndexMax;
    public boolean alert;
}

