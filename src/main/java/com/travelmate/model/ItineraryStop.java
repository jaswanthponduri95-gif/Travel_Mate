package com.travelmate.model;

public class ItineraryStop {
    public String id;
    public String name;
    public String category;
    public String categoryIcon;
    public String time;
    public String duration;
    public Double lat;
    public Double lng;
    public double cost;
    public String image;
    public String notes;
    public Transit transitToNext;
    public Boolean isWeatherImpacted;
    public Boolean isWeatherReplaced;

    // Real place details (from OpenStreetMap when the stop was picked on the map or geocoded)
    public String address;
    public String placeRef;
    public String openingHours;
    public String website;
    public String phone;
}
