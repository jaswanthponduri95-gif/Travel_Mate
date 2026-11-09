package com.travelmate.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads configuration from .env files and environment variables.
 *
 * Checks in order of priority:
 * 1. Process environment variables (System.getenv)
 * 2. JVM System properties (System.getProperty)
 * 3. .env file loaded from disk (root workspace, current dir, or parent dirs)
 *
 * Supports automatic parsing of standard cloud DATABASE_URL / MYSQL_URL
 * (e.g. mysql://user:password@host:port/database) into JDBC credentials.
 */
public final class EnvLoader {

    private static final Map<String, String> ENV_VARS = new ConcurrentHashMap<>();
    private static volatile boolean loaded = false;

    private EnvLoader() {
    }

    /**
     * Finds and loads the .env file once into memory.
     */
    public static synchronized void load() {
        if (loaded) return;
        loaded = true;

        File envFile = findEnvFile();
        if (envFile != null && envFile.exists() && envFile.isFile()) {
            readEnvFile(envFile);
        }

        // Parse cloud standard DATABASE_URL / MYSQL_URL if present
        parseDatabaseUrl();
    }

    private static File findEnvFile() {
        String[] candidatePaths = {
            ".env",
            "TravelMate/.env",
            "../.env",
            System.getProperty("user.dir") + File.separator + ".env",
            System.getProperty("user.dir") + File.separator + "TravelMate" + File.separator + ".env",
            System.getProperty("catalina.base") != null ? System.getProperty("catalina.base") + File.separator + ".env" : null,
            System.getProperty("catalina.home") != null ? System.getProperty("catalina.home") + File.separator + ".env" : null
        };

        for (String path : candidatePaths) {
            if (path == null) continue;
            File f = new File(path);
            if (f.exists() && f.isFile() && f.canRead()) {
                System.out.println("[TravelMate] Loaded configuration from " + f.getAbsolutePath());
                return f;
            }
        }
        return null;
    }

    private static void readEnvFile(File file) {
        try (BufferedReader reader = new BufferedReader(new FileReader(file, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int eqIndex = line.indexOf('=');
                if (eqIndex <= 0) {
                    continue;
                }
                String key = line.substring(0, eqIndex).trim();
                String value = line.substring(eqIndex + 1).trim();

                // Strip surrounding single or double quotes
                if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
                    if (value.length() >= 2) {
                        value = value.substring(1, value.length() - 1);
                    }
                }

                ENV_VARS.put(key, value);
            }
        } catch (IOException e) {
            System.err.println("[TravelMate] Could not read .env file: " + e.getMessage());
        }
    }

    /**
     * Parses cloud database URL if provided via DATABASE_URL or MYSQL_URL.
     * Supports formats:
     * - mysql://user:password@host:port/database
     * - mysql2://user:password@host:port/database
     * - jdbc:mysql://...
     */
    private static void parseDatabaseUrl() {
        String dbUrl = get("DATABASE_URL");
        if (dbUrl == null || dbUrl.isBlank()) {
            dbUrl = get("MYSQL_URL");
        }
        if (dbUrl == null || dbUrl.isBlank()) {
            return;
        }

        try {
            if (dbUrl.startsWith("jdbc:")) {
                setIfAbsent("TM_DB_URL", dbUrl);
                return;
            }

            if (dbUrl.startsWith("mysql://") || dbUrl.startsWith("mysql2://")) {
                URI uri = new URI(dbUrl.replace("mysql2://", "mysql://"));
                String host = uri.getHost();
                int port = uri.getPort() > 0 ? uri.getPort() : 3306;
                String path = uri.getPath();
                String database = (path != null && path.length() > 1) ? path.substring(1) : "tripmate";

                String jdbc = "jdbc:mysql://" + host + ":" + port + "/" + database
                        + "?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8";

                setIfAbsent("TM_DB_URL", jdbc);

                String userInfo = uri.getUserInfo();
                if (userInfo != null && !userInfo.isEmpty()) {
                    String[] parts = userInfo.split(":", 2);
                    if (parts.length > 0 && !parts[0].isEmpty()) {
                        setIfAbsent("TM_DB_USER", parts[0]);
                    }
                    if (parts.length > 1) {
                        setIfAbsent("TM_DB_PASSWORD", parts[1]);
                    }
                }
                System.out.println("[TravelMate] Configured database from DATABASE_URL/MYSQL_URL: " + host + ":" + port + "/" + database);
            }
        } catch (Exception e) {
            System.err.println("[TravelMate] Failed to parse DATABASE_URL: " + e.getMessage());
        }
    }

    private static void setIfAbsent(String key, String value) {
        if (ENV_VARS.get(key) == null) {
            ENV_VARS.put(key, value);
        }
    }

    /**
     * Gets a configuration value with resolution priority:
     * 1. System.getenv
     * 2. System.getProperty
     * 3. .env file value
     */
    public static String get(String key) {
        return get(key, null);
    }

    public static String get(String key, String defaultValue) {
        load();
        String val = System.getenv(key);
        if (val != null && !val.isBlank()) return val;

        val = System.getProperty(key);
        if (val != null && !val.isBlank()) return val;

        val = ENV_VARS.get(key);
        if (val != null && !val.isBlank()) return val;

        return defaultValue;
    }

    /** Returns unmodifiable view of loaded .env variables. */
    public static Map<String, String> getAll() {
        load();
        return Collections.unmodifiableMap(new HashMap<>(ENV_VARS));
    }
}
