package com.travelmate.servlet;

import com.travelmate.dao.BudgetDAO;
import com.travelmate.model.Budget;
import com.travelmate.model.BudgetItem;
import com.travelmate.util.JsonUtil;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.sql.SQLException;

/**
 * Trip budgets. Every call returns the updated budget.
 *   GET    /api/budget/{tripId}
 *   PUT    /api/budget/{tripId}                  body {"totalLimit": 20000}
 *   POST   /api/budget/{tripId}/items            add an expense item
 *   DELETE /api/budget/{tripId}/items/{itemId}
 */
@WebServlet("/api/budget/*")
public class BudgetServlet extends ApiServlet {

    private final BudgetDAO budgets = new BudgetDAO();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String[] parts = JsonUtil.pathParts(req);
        if (parts.length != 1) {
            JsonUtil.error(resp, 400, "Use GET /api/budget/{tripId}");
            return;
        }
        respond(resp, parts[0]);
    }

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String[] parts = JsonUtil.pathParts(req);
        Budget body = JsonUtil.read(req, Budget.class);
        if (parts.length != 1 || body == null || body.totalLimit < 0) {
            JsonUtil.error(resp, 400, "Use PUT /api/budget/{tripId} with {\"totalLimit\": number}");
            return;
        }
        try {
            budgets.setLimit(parts[0], body.totalLimit);
        } catch (SQLException e) {
            throw db(e);
        }
        respond(resp, parts[0]);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String[] parts = JsonUtil.pathParts(req);
        if (parts.length != 2 || !parts[1].equals("items")) {
            JsonUtil.error(resp, 400, "Use POST /api/budget/{tripId}/items");
            return;
        }
        BudgetItem item = JsonUtil.read(req, BudgetItem.class);
        if (item == null || item.name == null || item.name.isBlank() || item.category == null || item.amount < 0) {
            JsonUtil.error(resp, 400, "Expense needs a name, a category and a non-negative amount");
            return;
        }
        try {
            if (budgets.findByTrip(parts[0]) == null) {
                JsonUtil.error(resp, 404, "Trip not found: " + parts[0]);
                return;
            }
            budgets.addItem(parts[0], item);
        } catch (SQLException e) {
            throw db(e);
        }
        respond(resp, parts[0]);
    }

    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String[] parts = JsonUtil.pathParts(req);
        if (parts.length != 3 || !parts[1].equals("items")) {
            JsonUtil.error(resp, 400, "Use DELETE /api/budget/{tripId}/items/{itemId}");
            return;
        }
        try {
            budgets.deleteItem(parts[0], parts[2]);
        } catch (SQLException e) {
            throw db(e);
        }
        respond(resp, parts[0]);
    }

    private void respond(HttpServletResponse resp, String tripId) throws IOException {
        try {
            Budget b = budgets.findByTrip(tripId);
            if (b == null) JsonUtil.error(resp, 404, "Trip not found: " + tripId);
            else JsonUtil.ok(resp, b);
        } catch (SQLException e) {
            throw db(e);
        }
    }
}
