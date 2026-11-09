package com.travelmate;

import com.travelmate.dao.BudgetDAO;
import com.travelmate.dao.TripDAO;
import com.travelmate.dao.UserDAO;
import com.travelmate.db.DBConnection;
import com.travelmate.db.DatabaseInitializer;
import com.travelmate.model.Budget;
import com.travelmate.model.BudgetItem;
import com.travelmate.model.ItineraryStop;
import com.travelmate.model.Trip;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the DAOs against an in-memory H2 database in MySQL compatibility mode. */
class DatabaseTest {

    private static final String MYSURU = "trip-mysuru-2026";

    @BeforeAll
    static void setUp() {
        DBConnection.configure("jdbc:h2:mem:tm;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        assertTrue(DatabaseInitializer.initialize(), "initialize failed: " + DatabaseInitializer.lastError());
    }

    @Test
    void seedsDemoTripsInListOrder() throws Exception {
        List<Trip> trips = new TripDAO().findAll();
        assertEquals(List.of(MYSURU, "trip-ooty-draft", "trip-jaipur-completed"),
                trips.stream().map(t -> t.id).toList());
        Trip mysuru = trips.get(0);
        assertEquals(10, mysuru.stopsCount);
        assertEquals("Nov 14 – Nov 16, 2026", mysuru.dates);
        assertEquals("3 days ago", trips.get(1).lastUpdated);
    }

    @Test
    void savesAndReloadsFullItinerary() throws Exception {
        TripDAO dao = new TripDAO();
        Trip trip = dao.findById(MYSURU);
        assertEquals(3, trip.days.size());
        ItineraryStop first = trip.days.get(0).stops.get(0);
        assertEquals("stop-101", first.id);
        assertEquals("Auto Rickshaw", first.transitToNext.mode);

        Trip copy = dao.duplicate(MYSURU, "trip-copy-test");
        assertEquals("trip-copy-test", copy.id);
        ItineraryStop added = new ItineraryStop();
        added.id = "stop-test";
        added.name = "Test Stop";
        added.lat = 12.3;
        added.lng = 76.6;
        added.cost = 50;
        added.address = "Sayyaji Rao Road, Mysuru";
        added.placeRef = "osm:way/656323590";
        added.openingHours = "Mo-Su 10:00-17:30";
        copy.days.get(1).stops.add(added);
        copy.accommodation.name = "Test Hotel";
        dao.save(copy);

        Trip reloaded = dao.findById(copy.id);
        assertEquals("Draft", reloaded.status);
        assertEquals("Test Hotel", reloaded.accommodation.name);
        assertEquals("stop-test", reloaded.days.get(1).stops.get(reloaded.days.get(1).stops.size() - 1).id);
        ItineraryStop saved = reloaded.days.get(1).stops.get(reloaded.days.get(1).stops.size() - 1);
        assertEquals("Sayyaji Rao Road, Mysuru", saved.address);
        assertEquals("osm:way/656323590", saved.placeRef);
        assertEquals("Mo-Su 10:00-17:30", saved.openingHours);
        assertEquals(11, reloaded.stopsCount);
        // The original is untouched and the budget was copied
        assertEquals(10, dao.findById(MYSURU).stopsCount);
        assertEquals(11, new BudgetDAO().findByTrip(copy.id).items.size());

        assertTrue(dao.delete(copy.id));
        assertNull(dao.findById(copy.id));
        assertNull(new BudgetDAO().findByTrip(copy.id));
    }

    @Test
    void budgetTotalsFollowItems() throws Exception {
        BudgetDAO dao = new BudgetDAO();
        Budget before = dao.findByTrip(MYSURU);
        assertEquals(20000, before.totalLimit);
        // Totals are computed from the items (the old hard-coded 15,300 did not match them)
        assertEquals(14160, before.estimatedTotal);
        assertEquals(1850, before.actualSpend);
        assertEquals("bgt-1", before.items.get(0).id);

        BudgetItem item = new BudgetItem();
        item.id = "bgt-test";
        item.name = "Snacks";
        item.category = "Food & Dining";
        item.amount = 150;
        item.status = "Actual";
        dao.addItem(MYSURU, item);
        Budget after = dao.findByTrip(MYSURU);
        assertEquals("bgt-test", after.items.get(0).id);
        assertEquals(14310, after.estimatedTotal);
        assertEquals(2000, after.actualSpend);

        assertTrue(dao.deleteItem(MYSURU, "bgt-test"));
        assertEquals(14160, dao.findByTrip(MYSURU).estimatedTotal);
    }

    @Test
    void registersAndAuthenticatesUsers() throws Exception {
        UserDAO dao = new UserDAO();
        assertNotNull(dao.authenticate(DatabaseInitializer.DEMO_EMAIL, DatabaseInitializer.DEMO_PASSWORD));
        assertNull(dao.authenticate(DatabaseInitializer.DEMO_EMAIL, "wrong-password"));

        dao.create("Asha Rao", "Asha@Example.com", "secret123");
        assertTrue(dao.emailExists("asha@example.com"));
        assertEquals("Asha Rao", dao.authenticate(" asha@example.com ", "secret123").fullName);
    }
}
