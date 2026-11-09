package com.travelmate.dao;

import com.travelmate.db.DBConnection;
import com.travelmate.model.Budget;
import com.travelmate.model.BudgetItem;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** JDBC access for a trip's budget limit and expense items. */
public class BudgetDAO {

    /** Budget for a trip, or null when the trip does not exist. Newest items come first. */
    public Budget findByTrip(String tripId) throws SQLException {
        try (Connection con = DBConnection.getConnection()) {
            Budget b = new Budget();
            b.tripId = tripId;
            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT budget_total, budget_limit FROM trips WHERE id = ?")) {
                ps.setString(1, tripId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    double limit = rs.getDouble("budget_limit");
                    b.totalLimit = rs.wasNull() ? rs.getDouble("budget_total") : limit;
                }
            }
            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT * FROM budget_items WHERE trip_id = ? ORDER BY row_id DESC")) {
                ps.setString(1, tripId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        BudgetItem item = new BudgetItem();
                        item.id = rs.getString("item_id");
                        item.name = rs.getString("name");
                        item.category = rs.getString("category");
                        item.amount = rs.getDouble("amount");
                        item.status = rs.getString("status");
                        item.day = rs.getString("day_label");
                        b.items.add(item);
                        b.estimatedTotal += item.amount;
                        if ("Actual".equalsIgnoreCase(item.status)) b.actualSpend += item.amount;
                    }
                }
            }
            return b;
        }
    }

    public void addItem(String tripId, BudgetItem item) throws SQLException {
        if (item.id == null || item.id.isBlank()) item.id = "bgt-" + System.nanoTime();
        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "INSERT INTO budget_items (item_id, trip_id, name, category, amount, status, day_label) "
                             + "VALUES (?,?,?,?,?,?,?)")) {
            ps.setString(1, item.id);
            ps.setString(2, tripId);
            ps.setString(3, item.name);
            ps.setString(4, item.category);
            ps.setDouble(5, item.amount);
            ps.setString(6, item.status == null ? "Estimated" : item.status);
            ps.setString(7, item.day);
            ps.executeUpdate();
        }
    }

    public boolean deleteItem(String tripId, String itemId) throws SQLException {
        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "DELETE FROM budget_items WHERE trip_id = ? AND item_id = ?")) {
            ps.setString(1, tripId);
            ps.setString(2, itemId);
            return ps.executeUpdate() > 0;
        }
    }

    public void setLimit(String tripId, double limit) throws SQLException {
        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("UPDATE trips SET budget_limit = ? WHERE id = ?")) {
            ps.setDouble(1, limit);
            ps.setString(2, tripId);
            ps.executeUpdate();
        }
    }

    /** Copies all expense items (and the limit) of one trip onto another. */
    public void copyItems(String fromTripId, String toTripId) throws SQLException {
        try (Connection con = DBConnection.getConnection()) {
            try (PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO budget_items (item_id, trip_id, name, category, amount, status, day_label) "
                            + "SELECT item_id, ?, name, category, amount, status, day_label FROM budget_items "
                            + "WHERE trip_id = ? ORDER BY row_id")) {
                ps.setString(1, toTripId);
                ps.setString(2, fromTripId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = con.prepareStatement(
                    "UPDATE trips SET budget_limit = (SELECT budget_limit FROM trips WHERE id = ?) WHERE id = ?")) {
                ps.setString(1, fromTripId);
                ps.setString(2, toTripId);
                ps.executeUpdate();
            }
        }
    }
}
