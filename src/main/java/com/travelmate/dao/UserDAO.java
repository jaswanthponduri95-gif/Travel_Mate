package com.travelmate.dao;

import com.travelmate.db.DBConnection;
import com.travelmate.model.User;
import com.travelmate.util.PasswordUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** JDBC access for user accounts. Passwords are stored only as PBKDF2 hashes. */
public class UserDAO {

    public boolean emailExists(String email) throws SQLException {
        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT 1 FROM users WHERE email = ?")) {
            ps.setString(1, normalize(email));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public User create(String fullName, String email, String password) throws SQLException {
        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "INSERT INTO users (full_name, email, password_hash) VALUES (?,?,?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, fullName.trim());
            ps.setString(2, normalize(email));
            ps.setString(3, PasswordUtil.hash(password));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return new User(keys.getInt(1), fullName.trim(), normalize(email));
            }
        }
    }

    /** The matching user, or null when the email is unknown or the password is wrong. */
    public User authenticate(String email, String password) throws SQLException {
        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT id, full_name, email, password_hash FROM users WHERE email = ?")) {
            ps.setString(1, normalize(email));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                if (!PasswordUtil.verify(password, rs.getString("password_hash"))) return null;
                return new User(rs.getInt("id"), rs.getString("full_name"), rs.getString("email"));
            }
        }
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }
}
