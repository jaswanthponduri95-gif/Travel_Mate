package com.travelmate.servlet;

import com.travelmate.db.DatabaseInitializer;
import com.travelmate.util.JsonUtil;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Map;

/** POST /api/demo/reset — restores the demo trips and budget ("Reset Demo" on My Trips). */
@WebServlet("/api/demo/reset")
public class DemoDataServlet extends ApiServlet {

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            DatabaseInitializer.seedDemoData();
        } catch (SQLException e) {
            throw db(e);
        }
        JsonUtil.ok(resp, Map.of("reset", true));
    }
}
