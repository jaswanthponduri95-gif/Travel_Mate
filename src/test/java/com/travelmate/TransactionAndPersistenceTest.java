package com.travelmate;

import com.travelmate.dao.BudgetDAO;
import com.travelmate.dao.TripDAO;
import com.travelmate.dao.UserDAO;
import com.travelmate.db.DBConnection;
import com.travelmate.db.DatabaseInitializer;
import com.travelmate.model.BudgetItem;
import com.travelmate.model.ItineraryDay;
import com.travelmate.model.ItineraryStop;
import com.travelmate.model.Trip;
import com.travelmate.model.User;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 2 verification of transactions, foreign-key cascades, idempotency,
 * and database restart / file-based persistence.
 */
class TransactionAndPersistenceTest {

    @Test
    void testInitializationIdempotency() throws Exception {
        // Use an isolated in-memory DB for idempotency test
        String dbUrl = "jdbc:h2:mem:tm_idempotency_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        DBConnection.configure(dbUrl, "sa", "");

        // 1st run
        assertTrue(DatabaseInitializer.initialize());
        TripDAO tripDAO = new TripDAO();
        UserDAO userDAO = new UserDAO();
        int initialTripCount = tripDAO.count();
        assertTrue(initialTripCount > 0);
        assertTrue(userDAO.emailExists(DatabaseInitializer.DEMO_EMAIL));

        // 2nd run
        assertTrue(DatabaseInitializer.initialize());
        assertEquals(initialTripCount, tripDAO.count(), "Trip count must not change on re-initialization");
        assertTrue(userDAO.emailExists(DatabaseInitializer.DEMO_EMAIL));

        // 3rd run
        assertTrue(DatabaseInitializer.initialize());
        assertEquals(initialTripCount, tripDAO.count(), "Trip count must remain stable across repeated initializations");
        assertTrue(userDAO.emailExists(DatabaseInitializer.DEMO_EMAIL));
    }

    @Test
    void testForeignKeyCascadeAndSetNull() throws Exception {
        String dbUrl = "jdbc:h2:mem:tm_cascade_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        DBConnection.configure(dbUrl, "sa", "");
        assertTrue(DatabaseInitializer.initialize());

        TripDAO tripDAO = new TripDAO();
        BudgetDAO budgetDAO = new BudgetDAO();
        UserDAO userDAO = new UserDAO();

        // 1. Create a User
        User user = userDAO.create("Cascade User", "cascade.user@travelmate.in", "pass12345");

        // 2. Create a Trip with Days, Stops, and Budget
        String tripId = "trip-cascade-test";
        Trip trip = new Trip();
        trip.id = tripId;
        trip.title = "Cascade Verification Trip";

        ItineraryDay day = new ItineraryDay();
        day.dayNumber = 1;
        day.title = "Day 1";
        ItineraryStop stop = new ItineraryStop();
        stop.id = "stop-c1";
        stop.name = "Cascade Stop";
        day.stops.add(stop);
        trip.days = List.of(day);

        tripDAO.save(trip);

        // Associate user to trip via SQL
        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("UPDATE trips SET user_id = ? WHERE id = ?")) {
            ps.setInt(1, user.id);
            ps.setString(2, tripId);
            ps.executeUpdate();
        }

        // Add budget item
        BudgetItem item = new BudgetItem();
        item.id = "bgt-c1";
        item.name = "Cascade Item";
        item.category = "Activity";
        item.amount = 500;
        budgetDAO.addItem(tripId, item);

        // Verify items exist
        try (Connection con = DBConnection.getConnection(); Statement st = con.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM itinerary_days WHERE trip_id = '" + tripId + "'")) {
                rs.next();
                assertEquals(1, rs.getInt(1));
            }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM budget_items WHERE trip_id = '" + tripId + "'")) {
                rs.next();
                assertEquals(1, rs.getInt(1));
            }
        }

        // Test ON DELETE SET NULL on user deletion
        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("DELETE FROM users WHERE id = ?")) {
            ps.setInt(1, user.id);
            ps.executeUpdate();
        }

        // Trip still exists, but user_id is set to NULL
        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT user_id FROM trips WHERE id = ?")) {
            ps.setString(1, tripId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertNull(rs.getObject("user_id"));
            }
        }

        // Delete trip and verify CASCADE deletes days, stops, and budget_items
        assertTrue(tripDAO.delete(tripId));

        try (Connection con = DBConnection.getConnection(); Statement st = con.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM itinerary_days WHERE trip_id = '" + tripId + "'")) {
                rs.next();
                assertEquals(0, rs.getInt(1), "Itinerary days must be cascaded on trip delete");
            }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM itinerary_stops WHERE stop_id = 'stop-c1'")) {
                rs.next();
                assertEquals(0, rs.getInt(1), "Itinerary stops must be cascaded on day delete");
            }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM budget_items WHERE trip_id = '" + tripId + "'")) {
                rs.next();
                assertEquals(0, rs.getInt(1), "Budget items must be cascaded on trip delete");
            }
        }
    }

    @Test
    void testFileDatabaseRestartAndPersistence() throws Exception {
        // Persistent file database path with unique name per test run
        File dbDir = new File("target/test-persistence-data");
        dbDir.mkdirs();
        long runId = System.currentTimeMillis();
        String fileDbUrl = "jdbc:h2:file:./target/test-persistence-data/tm_persist_" + runId + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

        // Step 1: Configure and initialize the file-based database
        DBConnection.configure(fileDbUrl, "sa", "");
        assertTrue(DatabaseInitializer.initialize());

        TripDAO tripDAO = new TripDAO();
        BudgetDAO budgetDAO = new BudgetDAO();

        String persistentTripId = "trip-persist-001";
        Trip trip = new Trip();
        trip.id = persistentTripId;
        trip.title = "Persistent Trip Verification";
        trip.destination = "Goa";
        trip.budgetTotal = 18000;
        tripDAO.save(trip);

        BudgetItem item = new BudgetItem();
        item.id = "bgt-persist-001";
        item.name = "Beach Shack Dinner";
        item.category = "Food & Dining";
        item.amount = 1450.0;
        budgetDAO.addItem(persistentTripId, item);

        // Step 2: Simulate application shutdown and re-connection
        DBConnection.configure(fileDbUrl, "sa", "");
        assertTrue(DatabaseInitializer.initialize());

        // Step 3: Verify data survived the restart
        Trip retrieved = tripDAO.findById(persistentTripId);
        assertNotNull(retrieved, "Trip must persist across database re-initialization and restart");
        assertEquals("Persistent Trip Verification", retrieved.title);
        assertEquals("Goa", retrieved.destination);
        assertEquals(18000, retrieved.budgetTotal);

        var budget = budgetDAO.findByTrip(persistentTripId);
        assertNotNull(budget);
        assertEquals(1, budget.items.size());
        assertEquals("Beach Shack Dinner", budget.items.get(0).name);
        assertEquals(1450.0, budget.items.get(0).amount);

        // Cleanup
        tripDAO.delete(persistentTripId);
    }
}
