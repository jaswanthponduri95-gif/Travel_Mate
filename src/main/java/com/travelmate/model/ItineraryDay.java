package com.travelmate.model;

import java.util.ArrayList;
import java.util.List;

public class ItineraryDay {
    public int dayNumber;
    public String title;
    public String date;
    public String summary;
    public DailyForecast weather;
    public List<ItineraryStop> stops = new ArrayList<>();
}
