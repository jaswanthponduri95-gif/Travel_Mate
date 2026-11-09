package com.travelmate;

import com.travelmate.db.DBConnection;
import com.travelmate.db.DatabaseInitializer;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/** A database created by the first release (no place columns) is upgraded on start-up. */
class MigrationTest {

    @Test
    void addsPlaceColumnsToOldDatabase() throws Exception {
        DBConnection.configure("jdbc:h2:mem:tmold;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        try (Connection con = DBConnection.getConnection(); Statement st = con.createStatement()) {
            // The v1.0 itinerary_stops table, before address/place_ref/... existed
            st.execute("CREATE TABLE trips (id VARCHAR(64) PRIMARY KEY, title VARCHAR(200) NOT NULL)");
            st.execute("CREATE TABLE itinerary_days (id INT AUTO_INCREMENT PRIMARY KEY, trip_id VARCHAR(64) NOT NULL)");
            st.execute("CREATE TABLE itinerary_stops (row_id INT AUTO_INCREMENT PRIMARY KEY, stop_id VARCHAR(64) NOT NULL, "
                    + "day_id INT NOT NULL, name VARCHAR(200) NOT NULL)");
        }
        // Old tables lack columns the rest of the start-up needs, so initialize() itself may fail later;
        // the migration step must still have added the place columns.
        DatabaseInitializer.initialize();
        try (Connection con = DBConnection.getConnection(); Statement st = con.createStatement()) {
            assertDoesNotThrow(() -> st.executeQuery(
                    "SELECT address, place_ref, opening_hours, website, phone FROM itinerary_stops").close());
        }
    }
}
