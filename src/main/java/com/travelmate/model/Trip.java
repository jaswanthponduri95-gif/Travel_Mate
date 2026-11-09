package com.travelmate.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A trip. Field names match the JSON used by the frontend (js/data.js), so Gson
 * maps between them directly. The list view (GET /api/trips) fills only the
 * summary fields; the detail view (GET /api/trips/{id}) also fills days.
 */
public class Trip {
    public String id;
    public String title;
    public String destination;
    public String origin;
    public String startDate;
    public String endDate;
    public int daysCount;
    public int travelers;
    public double budgetTotal;
    public String budgetCategory;
    public List<String> interests = new ArrayList<>();
    public String coverImage;
    public String status;
    public Accommodation accommodation;
    public boolean weatherAltAccepted;
    /** True once the AI tour plan has been generated and saved for this trip (it is not re-run on every open). */
    public boolean aiPlanned;
    public List<ItineraryDay> days;

    // Derived, read-only summary fields for the "My Trips" dashboard
    public String image;
    public String dates;
    public String duration;
    public int stopsCount;
    public String lastUpdated;
    public double budget;
}
