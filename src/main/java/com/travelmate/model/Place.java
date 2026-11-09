package com.travelmate.model;

/** A real place looked up on OpenStreetMap. */
public class Place {
    public boolean success = true;
    public String name;
    public String displayName;
    /** TravelMate category (Heritage, Food, Nature, ...). */
    public String category;
    public String categoryIcon;
    /** Raw OpenStreetMap class/type, e.g. "historic/castle". */
    public String osmType;
    public String address;
    public Double lat;
    public Double lng;
    /** e.g. "osm:way/656323590". */
    public String placeRef;
    public String osmKind;
    public String osmId;
    /** [minLat, maxLat, minLng, maxLng] bounding box. */
    public double[] boundingBox;
    public String openingHours;
    public String website;
    public String phone;
}
