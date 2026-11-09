package com.travelmate.servlet;

import com.travelmate.dao.TripDAO;
import com.travelmate.model.Trip;
import com.travelmate.util.JsonUtil;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.sql.SQLException;

/**
 * Trips and their itineraries.
 *   GET    /api/trips                  list (summaries)
 *   GET    /api/trips/{id}             full trip with days and stops
 *   POST   /api/trips                  create
 *   PUT    /api/trips/{id}             create or replace
 *   DELETE /api/trips/{id}             delete (cascades to days, stops, budget)
 *   POST   /api/trips/{id}/duplicate   copy as a new Draft (optional body {"id": newId})
 */
@WebServlet("/api/trips/*")
public class TripServlet extends ApiServlet {

    private final TripDAO trips = new TripDAO();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String[] parts = JsonUtil.pathParts(req);
        try {
            if (parts.length == 0) {
                JsonUtil.ok(resp, trips.findAll());
                return;
            }
            Trip trip = trips.findById(parts[0]);
            if (trip == null) JsonUtil.error(resp, 404, "Trip not found: " + parts[0]);
            else JsonUtil.ok(resp, trip);
        } catch (SQLException e) {
            throw db(e);
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String[] parts = JsonUtil.pathParts(req);
        try {
            if (parts.length == 2 && parts[1].equals("duplicate")) {
                Trip body = JsonUtil.read(req, Trip.class);
                Trip copy = trips.duplicate(parts[0], body == null ? null : body.id);
                if (copy == null) JsonUtil.error(resp, 404, "Trip not found: " + parts[0]);
                else JsonUtil.write(resp, 201, copy);
                return;
            }
            if (parts.length != 0) {
                JsonUtil.error(resp, 404, "Unknown endpoint");
                return;
            }
            Trip trip = JsonUtil.read(req, Trip.class);
            if (trip == null) {
                JsonUtil.error(resp, 400, "Request body must be a trip JSON object");
                return;
            }
            if (trip.id != null && trips.exists(trip.id)) {
                JsonUtil.error(resp, 409, "Trip already exists: " + trip.id);
                return;
            }
            trips.save(trip);
            JsonUtil.write(resp, 201, trips.findById(trip.id));
        } catch (SQLException e) {
            throw db(e);
        }
    }

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String[] parts = JsonUtil.pathParts(req);
        if (parts.length != 1) {
            JsonUtil.error(resp, 400, "Use PUT /api/trips/{id}");
            return;
        }
        Trip trip = JsonUtil.read(req, Trip.class);
        if (trip == null) {
            JsonUtil.error(resp, 400, "Request body must be a trip JSON object");
            return;
        }
        trip.id = parts[0];
        try {
            trips.save(trip);
            JsonUtil.ok(resp, trips.findById(trip.id));
        } catch (SQLException e) {
            throw db(e);
        }
    }

    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String[] parts = JsonUtil.pathParts(req);
        if (parts.length != 1) {
            JsonUtil.error(resp, 400, "Use DELETE /api/trips/{id}");
            return;
        }
        try {
            if (trips.delete(parts[0])) resp.setStatus(204);
            else JsonUtil.error(resp, 404, "Trip not found: " + parts[0]);
        } catch (SQLException e) {
            throw db(e);
        }
    }
}
