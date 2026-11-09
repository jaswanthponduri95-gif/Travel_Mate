package com.travelmate.servlet;

import com.travelmate.dao.UserDAO;
import com.travelmate.model.User;
import com.travelmate.util.AiAccess;
import com.travelmate.util.JsonUtil;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Map;

/**
 * Session-based sign in.
 *   POST /api/auth/register   {fullName, email, password}
 *   POST /api/auth/login      {email, password}
 *   POST /api/auth/logout
 *   GET  /api/auth/me         current user, or 401
 */
@WebServlet("/api/auth/*")
public class AuthServlet extends ApiServlet {

    private static final String SESSION_USER = "tmUser";
    private final UserDAO users = new UserDAO();

    /** Request body for register/login. */
    private static class Credentials {
        String fullName;
        String email;
        String password;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!"me".equals(action(req))) {
            JsonUtil.error(resp, 404, "Unknown endpoint");
            return;
        }
        // 200 with null when signed out, so every page load doesn't log a 401 in the console
        HttpSession session = req.getSession(false);
        User user = session == null ? null : (User) session.getAttribute(SESSION_USER);
        if (user != null) user.aiAccess = AiAccess.isAllowed(user);
        JsonUtil.ok(resp, user);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String action = action(req);
        if ("logout".equals(action)) {
            HttpSession session = req.getSession(false);
            if (session != null) session.invalidate();
            JsonUtil.ok(resp, Map.of("signedOut", true));
            return;
        }
        Credentials c = JsonUtil.read(req, Credentials.class);
        if (c == null || c.email == null || c.email.isBlank() || c.password == null) {
            JsonUtil.error(resp, 400, "Email and password are required");
            return;
        }
        try {
            User user;
            if ("register".equals(action)) {
                if (c.fullName == null || c.fullName.isBlank()) {
                    JsonUtil.error(resp, 400, "Full name is required");
                    return;
                }
                if (!c.email.trim().matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
                    JsonUtil.error(resp, 400, "Please enter a valid email address");
                    return;
                }
                if (c.password.length() < 6) {
                    JsonUtil.error(resp, 400, "Password must be at least 6 characters");
                    return;
                }
                if (users.emailExists(c.email)) {
                    JsonUtil.error(resp, 409, "An account with this email already exists");
                    return;
                }
                user = users.create(c.fullName, c.email, c.password);
            } else if ("login".equals(action)) {
                user = users.authenticate(c.email, c.password);
                if (user == null) {
                    JsonUtil.error(resp, 401, "Incorrect email or password");
                    return;
                }
            } else {
                JsonUtil.error(resp, 404, "Unknown endpoint");
                return;
            }
            user.aiAccess = AiAccess.isAllowed(user);
            // A fresh session id on sign-in prevents session fixation
            HttpSession old = req.getSession(false);
            if (old != null) old.invalidate();
            req.getSession(true).setAttribute(SESSION_USER, user);
            JsonUtil.ok(resp, user);
        } catch (SQLException e) {
            throw db(e);
        }
    }

    /** The signed-in user for this request, or null. */
    public static User currentUser(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        return session == null ? null : (User) session.getAttribute(SESSION_USER);
    }

    private static String action(HttpServletRequest req) {
        String[] parts = JsonUtil.pathParts(req);
        return parts.length == 1 ? parts[0] : "";
    }
}
