package com.travelmate.dao;

import com.travelmate.db.DBConnection;
import com.travelmate.model.Accommodation;
import com.travelmate.model.ItineraryDay;
import com.travelmate.model.ItineraryStop;
import com.travelmate.model.Transit;
import com.travelmate.model.Trip;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** JDBC access for trips, itinerary days and itinerary stops. */
public class TripDAO {

    private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);

    private static final String SUMMARY_SQL =
            "SELECT t.*, (SELECT COUNT(*) FROM itinerary_stops s JOIN itinerary_days d ON s.day_id = d.id "
                    + "WHERE d.trip_id = t.id) AS stops_count FROM trips t";

    public List<Trip> findAll() throws SQLException {
        List<Trip> trips = new ArrayList<>();
        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(SUMMARY_SQL + " ORDER BY t.updated_at DESC, t.id")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) trips.add(mapTrip(rs));
            }
        }
        return trips;
    }

    /** Full trip including days and stops, or null when it does not exist. */
    public Trip findById(String id) throws SQLException {
        try (Connection con = DBConnection.getConnection()) {
            Trip trip;
            try (PreparedStatement ps = con.prepareStatement(SUMMARY_SQL + " WHERE t.id = ?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    trip = mapTrip(rs);
                }
            }
            trip.days = loadDays(con, id);
            return trip;
        }
    }

    public boolean exists(String id) throws SQLException {
        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT 1 FROM trips WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public int count() throws SQLException {
        try (Connection con = DBConnection.getConnection();
             Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM trips")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /**
     * Inserts or fully replaces a trip. When trip.days is non-null its days and stops
     * replace the stored ones; when it is null the itinerary is left untouched.
     */
    public void save(Trip trip) throws SQLException {
        if (trip.id == null || trip.id.isBlank()) trip.id = "trip-" + UUID.randomUUID();
        if (trip.title == null || trip.title.isBlank()) trip.title = "Untitled Trip";
        if (trip.status == null || trip.status.isBlank()) trip.status = "Draft";
        if (trip.days != null) trip.daysCount = Math.max(trip.days.size(), 1);

        try (Connection con = DBConnection.getConnection()) {
            con.setAutoCommit(false);
            try {
                boolean exists;
                try (PreparedStatement ps = con.prepareStatement("SELECT 1 FROM trips WHERE id = ?")) {
                    ps.setString(1, trip.id);
                    try (ResultSet rs = ps.executeQuery()) {
                        exists = rs.next();
                    }
                }
                String sql = exists
                        ? "UPDATE trips SET title=?, destination=?, origin=?, start_date=?, end_date=?, days_count=?, "
                        + "travelers=?, budget_total=?, budget_category=?, interests=?, cover_image=?, status=?, "
                        + "acc_name=?, acc_type=?, acc_address=?, acc_price_per_night=?, acc_nights=?, "
                        + "weather_alt_accepted=?, ai_planned=?, updated_at=? WHERE id=?"
                        : "INSERT INTO trips (title, destination, origin, start_date, end_date, days_count, travelers, "
                        + "budget_total, budget_category, interests, cover_image, status, acc_name, acc_type, "
                        + "acc_address, acc_price_per_night, acc_nights, weather_alt_accepted, ai_planned, updated_at, id) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
                try (PreparedStatement ps = con.prepareStatement(sql)) {
                    int i = 1;
                    ps.setString(i++, trip.title);
                    ps.setString(i++, trip.destination);
                    ps.setString(i++, trip.origin);
                    ps.setDate(i++, toSqlDate(trip.startDate));
                    ps.setDate(i++, toSqlDate(trip.endDate));
                    ps.setInt(i++, Math.max(trip.daysCount, 1));
                    ps.setInt(i++, Math.max(trip.travelers, 1));
                    ps.setDouble(i++, trip.budgetTotal);
                    ps.setString(i++, trip.budgetCategory);
                    ps.setString(i++, trip.interests == null ? null : String.join(",", trip.interests));
                    ps.setString(i++, trip.coverImage);
                    ps.setString(i++, trip.status);
                    Accommodation acc = trip.accommodation;
                    ps.setString(i++, acc == null ? null : acc.name);
                    ps.setString(i++, acc == null ? null : acc.type);
                    ps.setString(i++, acc == null ? null : acc.address);
                    if (acc == null) ps.setNull(i++, Types.DECIMAL); else ps.setDouble(i++, acc.pricePerNight);
                    if (acc == null) ps.setNull(i++, Types.INTEGER); else ps.setInt(i++, acc.nights);
                    ps.setBoolean(i++, trip.weatherAltAccepted);
                    ps.setBoolean(i++, trip.aiPlanned);
                    ps.setTimestamp(i++, Timestamp.valueOf(LocalDateTime.now()));
                    ps.setString(i, trip.id);
                    ps.executeUpdate();
                }
                if (trip.days != null) replaceDays(con, trip.id, trip.days);
                con.commit();
            } catch (SQLException e) {
                con.rollback();
                throw e;
            }
        }
    }

    public boolean delete(String id) throws SQLException {
        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("DELETE FROM trips WHERE id = ?")) {
            ps.setString(1, id);
            return ps.executeUpdate() > 0;
        }
    }

    /**
     * Copies a trip (with its itinerary and budget) as a new Draft under newId
     * (generated when null). Returns the copy, or null when the source does not exist.
     */
    public Trip duplicate(String id, String newId) throws SQLException {
        Trip source = findById(id);
        if (source == null) return null;
        if (newId == null || newId.isBlank() || exists(newId)) newId = "trip-" + System.currentTimeMillis();
        source.id = newId;
        source.title = source.title + " (Copy)";
        source.status = "Draft";
        save(source);
        new BudgetDAO().copyItems(id, newId);
        return findById(newId);
    }

    /** Back-dates a trip's last-updated time (used when loading demo data). */
    public void setUpdatedAt(String id, LocalDateTime when) throws SQLException {
        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("UPDATE trips SET updated_at = ? WHERE id = ?")) {
            ps.setTimestamp(1, Timestamp.valueOf(when));
            ps.setString(2, id);
            ps.executeUpdate();
        }
    }

    public void deleteAll() throws SQLException {
        try (Connection con = DBConnection.getConnection();
             Statement st = con.createStatement()) {
            st.executeUpdate("DELETE FROM trips");
        }
    }

    // ---------------------------------------------------------------- helpers

    private void replaceDays(Connection con, String tripId, List<ItineraryDay> days) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM itinerary_days WHERE trip_id = ?")) {
            ps.setString(1, tripId);
            ps.executeUpdate();
        }
        String daySql = "INSERT INTO itinerary_days (trip_id, day_number, title, day_date, summary) VALUES (?,?,?,?,?)";
        String stopSql = "INSERT INTO itinerary_stops (stop_id, day_id, sort_order, name, category, category_icon, "
                + "time_label, duration, lat, lng, cost, image, notes, transit_mode, transit_icon, transit_duration, "
                + "transit_distance, transit_cost, weather_impacted, weather_replaced, "
                + "address, place_ref, opening_hours, website, phone) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement dayPs = con.prepareStatement(daySql, Statement.RETURN_GENERATED_KEYS);
             PreparedStatement stopPs = con.prepareStatement(stopSql)) {
            int dayIndex = 0;
            for (ItineraryDay day : days) {
                dayIndex++;
                // Day numbers are always renumbered 1..n so they stay unique and contiguous
                dayPs.setString(1, tripId);
                dayPs.setInt(2, dayIndex);
                dayPs.setString(3, day.title);
                dayPs.setDate(4, toSqlDate(day.date));
                dayPs.setString(5, day.summary);
                dayPs.executeUpdate();
                int dayId;
                try (ResultSet keys = dayPs.getGeneratedKeys()) {
                    keys.next();
                    dayId = keys.getInt(1);
                }
                if (day.stops == null) continue;
                int order = 0;
                for (ItineraryStop s : day.stops) {
                    int i = 1;
                    stopPs.setString(i++, s.id == null || s.id.isBlank() ? "stop-" + UUID.randomUUID() : s.id);
                    stopPs.setInt(i++, dayId);
                    stopPs.setInt(i++, order++);
                    stopPs.setString(i++, s.name == null || s.name.isBlank() ? "Untitled stop" : s.name);
                    stopPs.setString(i++, s.category);
                    stopPs.setString(i++, s.categoryIcon);
                    stopPs.setString(i++, s.time);
                    stopPs.setString(i++, s.duration);
                    if (s.lat == null) stopPs.setNull(i++, Types.DOUBLE); else stopPs.setDouble(i++, s.lat);
                    if (s.lng == null) stopPs.setNull(i++, Types.DOUBLE); else stopPs.setDouble(i++, s.lng);
                    stopPs.setDouble(i++, s.cost);
                    stopPs.setString(i++, s.image);
                    stopPs.setString(i++, s.notes);
                    Transit t = s.transitToNext;
                    stopPs.setString(i++, t == null ? null : t.mode);
                    stopPs.setString(i++, t == null ? null : t.icon);
                    stopPs.setString(i++, t == null ? null : t.duration);
                    stopPs.setString(i++, t == null ? null : t.distance);
                    if (t == null) stopPs.setNull(i++, Types.DECIMAL); else stopPs.setDouble(i++, t.cost);
                    stopPs.setBoolean(i++, Boolean.TRUE.equals(s.isWeatherImpacted));
                    stopPs.setBoolean(i++, Boolean.TRUE.equals(s.isWeatherReplaced));
                    stopPs.setString(i++, truncate(s.address, 500));
                    stopPs.setString(i++, truncate(s.placeRef, 80));
                    stopPs.setString(i++, truncate(s.openingHours, 300));
                    stopPs.setString(i++, truncate(s.website, 500));
                    stopPs.setString(i, truncate(s.phone, 60));
                    stopPs.addBatch();
                }
                stopPs.executeBatch();
            }
        }
    }

    private List<ItineraryDay> loadDays(Connection con, String tripId) throws SQLException {
        List<ItineraryDay> days = new ArrayList<>();
        List<Integer> dayIds = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT * FROM itinerary_days WHERE trip_id = ? ORDER BY day_number")) {
            ps.setString(1, tripId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ItineraryDay d = new ItineraryDay();
                    d.dayNumber = rs.getInt("day_number");
                    d.title = rs.getString("title");
                    d.date = fromSqlDate(rs.getDate("day_date"));
                    d.summary = rs.getString("summary");
                    days.add(d);
                    dayIds.add(rs.getInt("id"));
                }
            }
        }
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT * FROM itinerary_stops WHERE day_id = ? ORDER BY sort_order, row_id")) {
            for (int idx = 0; idx < days.size(); idx++) {
                ps.setInt(1, dayIds.get(idx));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) days.get(idx).stops.add(mapStop(rs));
                }
            }
        }
        return days;
    }

    private ItineraryStop mapStop(ResultSet rs) throws SQLException {
        ItineraryStop s = new ItineraryStop();
        s.id = rs.getString("stop_id");
        s.name = rs.getString("name");
        s.category = rs.getString("category");
        s.categoryIcon = rs.getString("category_icon");
        s.time = rs.getString("time_label");
        s.duration = rs.getString("duration");
        double lat = rs.getDouble("lat");
        s.lat = rs.wasNull() ? null : lat;
        double lng = rs.getDouble("lng");
        s.lng = rs.wasNull() ? null : lng;
        s.cost = rs.getDouble("cost");
        s.image = rs.getString("image");
        s.notes = rs.getString("notes");
        String mode = rs.getString("transit_mode");
        if (mode != null) {
            Transit t = new Transit();
            t.mode = mode;
            t.icon = rs.getString("transit_icon");
            t.duration = rs.getString("transit_duration");
            t.distance = rs.getString("transit_distance");
            t.cost = rs.getDouble("transit_cost");
            s.transitToNext = t;
        }
        if (rs.getBoolean("weather_impacted")) s.isWeatherImpacted = true;
        if (rs.getBoolean("weather_replaced")) s.isWeatherReplaced = true;
        s.address = rs.getString("address");
        s.placeRef = rs.getString("place_ref");
        s.openingHours = rs.getString("opening_hours");
        s.website = rs.getString("website");
        s.phone = rs.getString("phone");
        return s;
    }

    /** OpenStreetMap values can be long; keep them within the column sizes. */
    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private Trip mapTrip(ResultSet rs) throws SQLException {
        Trip t = new Trip();
        t.id = rs.getString("id");
        t.title = rs.getString("title");
        t.destination = rs.getString("destination");
        t.origin = rs.getString("origin");
        t.startDate = fromSqlDate(rs.getDate("start_date"));
        t.endDate = fromSqlDate(rs.getDate("end_date"));
        t.daysCount = rs.getInt("days_count");
        t.travelers = rs.getInt("travelers");
        t.budgetTotal = rs.getDouble("budget_total");
        t.budget = t.budgetTotal;
        t.budgetCategory = rs.getString("budget_category");
        String interests = rs.getString("interests");
        t.interests = interests == null || interests.isBlank()
                ? new ArrayList<>() : new ArrayList<>(Arrays.asList(interests.split(",")));
        t.coverImage = rs.getString("cover_image");
        t.image = t.coverImage;
        t.status = rs.getString("status");
        String accName = rs.getString("acc_name");
        if (accName != null) {
            Accommodation a = new Accommodation();
            a.name = accName;
            a.type = rs.getString("acc_type");
            a.address = rs.getString("acc_address");
            a.pricePerNight = rs.getDouble("acc_price_per_night");
            a.nights = rs.getInt("acc_nights");
            t.accommodation = a;
        }
        t.weatherAltAccepted = rs.getBoolean("weather_alt_accepted");
        t.aiPlanned = rs.getBoolean("ai_planned");
        t.stopsCount = rs.getInt("stops_count");
        t.dates = formatDateRange(t.startDate, t.endDate);
        t.duration = t.daysCount + (t.daysCount == 1 ? " Day" : " Days")
                + (t.daysCount > 1 ? ", " + (t.daysCount - 1) + (t.daysCount == 2 ? " Night" : " Nights") : "");
        Timestamp updated = rs.getTimestamp("updated_at");
        t.lastUpdated = updated == null ? "" : relativeTime(updated.toLocalDateTime());
        return t;
    }

    static Date toSqlDate(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return Date.valueOf(LocalDate.parse(iso.length() > 10 ? iso.substring(0, 10) : iso));
        } catch (Exception e) {
            return null;
        }
    }

    private static String fromSqlDate(Date d) {
        return d == null ? null : d.toLocalDate().toString();
    }

    private static String formatDateRange(String start, String end) {
        if (start == null) return "Dates flexible";
        LocalDate s = LocalDate.parse(start);
        if (end == null) return s.format(MONTH_DAY) + ", " + s.getYear();
        LocalDate e = LocalDate.parse(end);
        return s.format(MONTH_DAY) + " – " + e.format(MONTH_DAY) + ", " + e.getYear();
    }

    private static String relativeTime(LocalDateTime when) {
        LocalDateTime now = LocalDateTime.now();
        long minutes = Duration.between(when, now).toMinutes();
        if (minutes < 1) return "Just now";
        if (minutes < 60) return minutes + (minutes == 1 ? " min ago" : " mins ago");
        if (when.toLocalDate().equals(now.toLocalDate())) return "Today at " + when.format(TIME);
        long days = Duration.between(when.toLocalDate().atStartOfDay(), now.toLocalDate().atStartOfDay()).toDays();
        if (days == 1) return "Yesterday";
        if (days < 30) return days + " days ago";
        long months = days / 30;
        return months + (months == 1 ? " month ago" : " months ago");
    }
}
