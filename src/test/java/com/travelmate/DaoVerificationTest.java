package com.travelmate;

import com.travelmate.dao.AiSettingsDAO;
import com.travelmate.dao.BudgetDAO;
import com.travelmate.dao.TripDAO;
import com.travelmate.dao.UserDAO;
import com.travelmate.db.DBConnection;
import com.travelmate.db.DatabaseInitializer;
import com.travelmate.model.Accommodation;
import com.travelmate.model.AiSettings;
import com.travelmate.model.Budget;
import com.travelmate.model.BudgetItem;
import com.travelmate.model.ItineraryDay;
import com.travelmate.model.ItineraryStop;
import com.travelmate.model.Transit;
import com.travelmate.model.Trip;
import com.travelmate.model.User;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 2 DAO verification covering complete CRUD, calculation logic,
 * authentication edge cases, and AI settings persistence.
 */
class DaoVerificationTest {

    @BeforeAll
    static void setUp() {
        DBConnection.configure("jdbc:h2:mem:tm_dao_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        assertTrue(DatabaseInitializer.initialize(), "Initialization failed: " + DatabaseInitializer.lastError());
    }

    @Test
    void testTripDaoCrudAndEdgeCases() throws Exception {
        TripDAO dao = new TripDAO();
        String tripId = "trip-test-kerala";

        // 1. Create and Save
        Trip trip = new Trip();
        trip.id = tripId;
        trip.title = "Kerala Backwaters Tour";
        trip.destination = "Alleppey";
        trip.origin = "Kochi";
        trip.startDate = "2026-12-01";
        trip.endDate = "2026-12-03";
        trip.daysCount = 2;
        trip.travelers = 3;
        trip.budgetTotal = 25000.50;
        trip.budgetCategory = "Moderate";
        trip.interests = List.of("Nature", "Food", "Relaxation");
        trip.status = "Draft";

        Accommodation acc = new Accommodation();
        acc.name = "Lakefront Houseboat";
        acc.type = "Houseboat";
        acc.address = "Finishing Point, Alleppey";
        acc.pricePerNight = 8500.00;
        acc.nights = 2;
        trip.accommodation = acc;

        List<ItineraryDay> days = new ArrayList<>();
        ItineraryDay day1 = new ItineraryDay();
        day1.dayNumber = 1;
        day1.title = "Cruise Arrival";
        day1.date = "2026-12-01";
        day1.summary = "Board houseboat and evening cruise";

        ItineraryStop stop1 = new ItineraryStop();
        stop1.id = "stop-k1";
        stop1.name = "Houseboat Dock";
        stop1.category = "Activity";
        stop1.time = "12:00 PM";
        stop1.duration = "1 hr";
        stop1.lat = 9.4981;
        stop1.lng = 76.3388;
        stop1.cost = 0.0;
        stop1.address = "Alleppey Dock, Kerala";

        Transit transit1 = new Transit();
        transit1.mode = "Ferry";
        transit1.duration = "30 mins";
        transit1.distance = "5 km";
        transit1.cost = 250.00;
        stop1.transitToNext = transit1;

        day1.stops.add(stop1);
        days.add(day1);
        trip.days = days;

        dao.save(trip);

        // 2. Retrieve and Validate
        assertTrue(dao.exists(tripId));
        Trip retrieved = dao.findById(tripId);
        assertNotNull(retrieved);
        assertEquals("Kerala Backwaters Tour", retrieved.title);
        assertEquals("Alleppey", retrieved.destination);
        assertEquals(25000.50, retrieved.budgetTotal);
        assertNotNull(retrieved.accommodation);
        assertEquals("Lakefront Houseboat", retrieved.accommodation.name);
        assertEquals(1, retrieved.days.size());
        assertEquals("stop-k1", retrieved.days.get(0).stops.get(0).id);
        assertNotNull(retrieved.days.get(0).stops.get(0).transitToNext);
        assertEquals("Ferry", retrieved.days.get(0).stops.get(0).transitToNext.mode);
        assertEquals(250.00, retrieved.days.get(0).stops.get(0).transitToNext.cost);

        // 3. Update
        retrieved.title = "Kerala Backwaters & Beach Tour";
        retrieved.status = "Confirmed";
        dao.save(retrieved);

        Trip updated = dao.findById(tripId);
        assertEquals("Kerala Backwaters & Beach Tour", updated.title);
        assertEquals("Confirmed", updated.status);

        // 4. Duplicate
        String duplicateId = "trip-test-kerala-copy";
        Trip duplicated = dao.duplicate(tripId, duplicateId);
        assertNotNull(duplicated);
        assertEquals(duplicateId, duplicated.id);
        assertTrue(duplicated.title.contains("(Copy)"));
        assertEquals("Draft", duplicated.status);
        assertEquals(1, duplicated.days.size());

        // 5. Non-existent ID handling
        assertNull(dao.findById("non-existent-trip-id-999"));
        assertFalse(dao.exists("non-existent-trip-id-999"));

        // 6. Delete
        assertTrue(dao.delete(tripId));
        assertFalse(dao.exists(tripId));
        assertNull(dao.findById(tripId));
        assertFalse(dao.delete(tripId)); // second delete returns false

        assertTrue(dao.delete(duplicateId));
    }

    @Test
    void testBudgetDaoCrudAndCalculations() throws Exception {
        TripDAO tripDAO = new TripDAO();
        BudgetDAO budgetDAO = new BudgetDAO();

        String tripId = "trip-budget-calc-test";
        Trip t = new Trip();
        t.id = tripId;
        t.title = "Budget Calculation Test Trip";
        t.budgetTotal = 10000.00;
        tripDAO.save(t);

        // Initial budget state
        Budget initial = budgetDAO.findByTrip(tripId);
        assertNotNull(initial);
        assertEquals(10000.00, initial.totalLimit);
        assertEquals(0.00, initial.estimatedTotal);
        assertEquals(0.00, initial.actualSpend);
        assertTrue(initial.items.isEmpty());

        // Set explicit budget limit
        budgetDAO.setLimit(tripId, 15000.75);
        Budget withLimit = budgetDAO.findByTrip(tripId);
        assertEquals(15000.75, withLimit.totalLimit);

        // Add Estimated item
        BudgetItem item1 = new BudgetItem();
        item1.id = "item-bgt-1";
        item1.name = "Train Tickets";
        item1.category = "Transport";
        item1.amount = 1200.50;
        item1.status = "Estimated";
        item1.day = "Day 1";
        budgetDAO.addItem(tripId, item1);

        // Add Actual item
        BudgetItem item2 = new BudgetItem();
        item2.id = "item-bgt-2";
        item2.name = "Dinner at Royal Cafe";
        item2.category = "Food & Dining";
        item2.amount = 850.25;
        item2.status = "Actual";
        item2.day = "Day 1";
        budgetDAO.addItem(tripId, item2);

        Budget loaded = budgetDAO.findByTrip(tripId);
        assertEquals(2, loaded.items.size());
        assertEquals(2050.75, loaded.estimatedTotal, 0.001);
        assertEquals(850.25, loaded.actualSpend, 0.001);

        // Copy items to another trip
        String targetTripId = "trip-budget-copy-target";
        Trip target = new Trip();
        target.id = targetTripId;
        target.title = "Target Trip";
        tripDAO.save(target);

        budgetDAO.copyItems(tripId, targetTripId);
        Budget copiedBudget = budgetDAO.findByTrip(targetTripId);
        assertEquals(15000.75, copiedBudget.totalLimit);
        assertEquals(2, copiedBudget.items.size());
        assertEquals(2050.75, copiedBudget.estimatedTotal, 0.001);

        // Delete item
        assertTrue(budgetDAO.deleteItem(tripId, "item-bgt-1"));
        Budget afterDelete = budgetDAO.findByTrip(tripId);
        assertEquals(1, afterDelete.items.size());
        assertEquals(850.25, afterDelete.estimatedTotal, 0.001);
        assertEquals(850.25, afterDelete.actualSpend, 0.001);

        // Cleanup
        tripDAO.delete(tripId);
        tripDAO.delete(targetTripId);
        assertNull(budgetDAO.findByTrip(tripId));
    }

    @Test
    void testUserDaoRegistrationAndAuthentication() throws Exception {
        UserDAO userDAO = new UserDAO();

        String email = "priya.sharma@travelmate.in";
        String password = "SecurePassword#2026";
        String name = "Priya Sharma";

        // Register user
        User created = userDAO.create(name, email, password);
        assertNotNull(created);
        assertTrue(created.id > 0);
        assertEquals(name, created.fullName);
        assertEquals(email, created.email);

        // Case and whitespace insensitivity in email
        assertTrue(userDAO.emailExists(email));
        assertTrue(userDAO.emailExists("  PRIYA.SHARMA@TRAVELMATE.IN  "));
        assertFalse(userDAO.emailExists("unknown.user@travelmate.in"));

        // Authentication with valid password
        User authSuccess = userDAO.authenticate("  Priya.Sharma@travelmate.in ", password);
        assertNotNull(authSuccess);
        assertEquals(created.id, authSuccess.id);
        assertEquals(name, authSuccess.fullName);

        // Authentication with wrong password
        assertNull(userDAO.authenticate(email, "WrongPassword!"));

        // Authentication with non-existent email
        assertNull(userDAO.authenticate("nonexistent@domain.com", password));

        // Duplicate email creation should fail with SQLException
        assertThrows(SQLException.class, () -> userDAO.create("Another Priya", email, "differentPassword"));
    }

    @Test
    void testAiSettingsDaoOperations() throws Exception {
        AiSettingsDAO aiDAO = new AiSettingsDAO();

        // 1. Initial load
        AiSettings initial = aiDAO.load();
        assertNotNull(initial);

        // 2. Save custom settings
        AiSettings custom = new AiSettings();
        custom.enabled = true;
        custom.baseUrl = "https://api.openai.com/v1";
        custom.apiKey = "tm-test-key-xyz123";
        custom.model = "gpt-4o";
        custom.temperature = 0.5;
        custom.maxTokens = 2048;
        custom.timeoutSeconds = 60;
        custom.jsonMode = true;
        custom.extraHeaders = "X-Custom-Header: TestValue";

        aiDAO.save(custom);

        // 3. Verify saved settings
        AiSettings retrieved = aiDAO.load();
        assertTrue(retrieved.enabled);
        assertEquals("https://api.openai.com/v1", retrieved.baseUrl);
        assertEquals("tm-test-key-xyz123", retrieved.apiKey);
        assertEquals("gpt-4o", retrieved.model);
        assertEquals(0.5, retrieved.temperature);
        assertEquals(2048, retrieved.maxTokens);
        assertEquals(60, retrieved.timeoutSeconds);
        assertTrue(retrieved.jsonMode);
        assertEquals("X-Custom-Header: TestValue", retrieved.extraHeaders);

        // 4. Reset settings
        aiDAO.reset();
        AiSettings afterReset = aiDAO.load();
        assertNotEquals("tm-test-key-xyz123", afterReset.apiKey);
    }
}

