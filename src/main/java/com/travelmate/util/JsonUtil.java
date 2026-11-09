package com.travelmate.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Shared JSON request/response helpers for the servlets. */
public final class JsonUtil {

    public static final Gson GSON = new GsonBuilder().create();

    private JsonUtil() {
    }

    /** Parses the request body, or returns null when the body is empty or not valid JSON. */
    public static <T> T read(HttpServletRequest req, Class<T> type) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = req.getReader()) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
        }
        if (sb.length() == 0) return null;
        try {
            return GSON.fromJson(sb.toString(), type);
        } catch (JsonParseException e) {
            return null;
        }
    }

    public static void write(HttpServletResponse resp, int status, Object body) throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Cache-Control", "no-store");
        resp.getWriter().write(GSON.toJson(body));
    }

    public static void ok(HttpServletResponse resp, Object body) throws IOException {
        write(resp, HttpServletResponse.SC_OK, body);
    }

    public static void error(HttpServletResponse resp, int status, String message) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        write(resp, status, body);
    }

    /** Path segments after the servlet mapping, e.g. "/abc/items/x" -> ["abc", "items", "x"]. */
    public static String[] pathParts(HttpServletRequest req) {
        String info = req.getPathInfo();
        if (info == null || info.equals("/")) return new String[0];
        return info.substring(1).split("/");
    }
}
