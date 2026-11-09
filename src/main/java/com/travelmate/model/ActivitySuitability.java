package com.travelmate.model;

/**
 * Activity suitability assessment produced by the weather heuristics engine.
 */
public class ActivitySuitability {
    public String activity;
    public SuitabilityLevel suitability;
    public String reason;
    public String icon;

    public ActivitySuitability() {
    }

    public ActivitySuitability(String activity, SuitabilityLevel suitability, String reason, String icon) {
        this.activity = activity;
        this.suitability = suitability;
        this.reason = reason;
        this.icon = icon;
    }
}

