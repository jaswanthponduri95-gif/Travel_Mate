package com.travelmate;

import com.travelmate.db.DBConnection;
import com.travelmate.db.DatabaseInitializer;
import com.travelmate.model.User;
import com.travelmate.util.AiAccess;
import com.travelmate.util.PasswordUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 2 verification of database connection lifecycle, driver management,
 * URL password masking, and password hashing security.
 */
class ConnectionAndSecurityTest {

    @BeforeAll
    static void setUp() {
        DBConnection.configure("jdbc:h2:mem:tm_conn_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        assertTrue(DatabaseInitializer.initialize());
    }

    @Test
    void testConnectionLifecycleUnderRepeatedRequests() throws SQLException {
        // Open and close 50 connections sequentially to verify resource handling and closing
        for (int i = 0; i < 50; i++) {
            try (Connection con = DBConnection.getConnection()) {
                assertNotNull(con);
                assertFalse(con.isClosed());
                assertTrue(con.isValid(2));
            }
        }
    }

    @Test
    void testDescribeUrlMasksCredentials() {
        DBConnection.configure("jdbc:mysql://localhost:3306/travelmate?password=UltraSecretPassword123&user=admin", "admin", "UltraSecretPassword123");
        String described = DBConnection.describeUrl();
        assertFalse(described.contains("UltraSecretPassword123"), "URL description must never expose cleartext passwords");
        assertTrue(described.contains("password=***"));

        DBConnection.configure("jdbc:h2:mem:tm_clean;MODE=MySQL", "sa", "");
        assertEquals("jdbc:h2:mem:tm_clean;MODE=MySQL", DBConnection.describeUrl());
    }

    @Test
    void testPasswordHashingSecurity() {
        String rawPassword = "TravelMateSecretPassword@2026";
        String hashed = PasswordUtil.hash(rawPassword);

        assertNotNull(hashed);
        assertTrue(hashed.startsWith("pbkdf2$65536$"));

        // Verification success
        assertTrue(PasswordUtil.verify(rawPassword, hashed));

        // Tampered password failure
        assertFalse(PasswordUtil.verify("WrongPassword", hashed));
        assertFalse(PasswordUtil.verify("", hashed));
        assertFalse(PasswordUtil.verify(null, hashed));
        assertFalse(PasswordUtil.verify(rawPassword, null));
        assertFalse(PasswordUtil.verify(rawPassword, "invalid_hash_format"));

        // Salts must be unique: two hashes of the same password must differ
        String hashedSecond = PasswordUtil.hash(rawPassword);
        assertNotEquals(hashed, hashedSecond);
    }

    @Test
    void testAiAccessAuthorization() {
        String property = "tm.ai.allowedEmails";
        String previous = System.getProperty(property);
        try {
            // Keep this test independent of machine-specific environment variables.
            System.setProperty(property, "your.email@example.com, friend1@example.com");
            AiAccess.reload();

            User authorizedUser = new User(1, "Primary Owner", "your.email@example.com");
            assertTrue(AiAccess.isAllowed(authorizedUser));

            User caseCheck = new User(1, "Primary Owner", "  YOUR.EMAIL@EXAMPLE.COM  ");
            assertTrue(AiAccess.isAllowed(caseCheck));

            User approvedFriend = new User(2, "Approved Friend", "friend1@example.com");
            assertTrue(AiAccess.isAllowed(approvedFriend));

            User regularUser = new User(3, "Regular User", "unauthorized.user@domain.com");
            assertFalse(AiAccess.isAllowed(regularUser));

            assertFalse(AiAccess.isAllowed(null));
            assertFalse(AiAccess.isAllowed(new User(4, "No Email", null)));
        } finally {
            if (previous == null) System.clearProperty(property);
            else System.setProperty(property, previous);
            AiAccess.reload();
        }
    }
}
