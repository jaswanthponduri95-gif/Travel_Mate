package com.travelmate.db;

import com.travelmate.util.EnvLoader;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Central JDBC connection factory.
 *
 * Settings come from .env / environment variables / system properties / db.properties.
 */
public final class DBConnection {

    private static String url;
    private static String user;
    private static String password;

    private DBConnection() {
    }

    static {
        EnvLoader.load();
        Properties props = new Properties();
        try (InputStream in = DBConnection.class.getClassLoader().getResourceAsStream("db.properties")) {
            if (in != null) {
                props.load(in);
            }
        } catch (Exception e) {
            System.err.println("[TravelMate] Could not read db.properties: " + e.getMessage());
        }
        configure(
                setting(props, "url", "jdbc:mysql://localhost:3306/tripmate?createDatabaseIfNotExist=true"
                        + "&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8"),
                setting(props, "user", "root"),
                setting(props, "password", ""));
    }

    private static String setting(Properties props, String key, String fallback) {
        String sys = System.getProperty("tm.db." + key);
        if (sys != null && !sys.isBlank()) return sys;
        String env = System.getenv("TM_DB_" + key.toUpperCase());
        if (env != null && !env.isBlank()) return env;
        String dotEnv = EnvLoader.get("TM_DB_" + key.toUpperCase());
        if (dotEnv != null && !dotEnv.isBlank()) return dotEnv;
        return props.getProperty("db." + key, fallback);
    }

    /** Overrides the connection settings (used by tests). */
    public static synchronized void configure(String jdbcUrl, String dbUser, String dbPassword) {
        url = jdbcUrl;
        user = dbUser;
        password = dbPassword;
        loadDriver(jdbcUrl);
    }

    /*
     * Inside Tomcat, JDBC drivers bundled in WEB-INF/lib are not always picked up by
     * DriverManager's service loader, which shows up as "No suitable driver found".
     * Loading the driver class explicitly avoids that.
     */
    private static void loadDriver(String jdbcUrl) {
        String driver = null;
        if (jdbcUrl.startsWith("jdbc:mysql:")) driver = "com.mysql.cj.jdbc.Driver";
        else if (jdbcUrl.startsWith("jdbc:h2:")) driver = "org.h2.Driver";
        if (driver == null) return;
        try {
            Class.forName(driver);
        } catch (ClassNotFoundException e) {
            System.err.println("[TravelMate] JDBC driver " + driver + " is not on the classpath.");
        }
    }

    public static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    /** URL with any password parameter stripped, safe for logs and the health endpoint. */
    public static String describeUrl() {
        return url.replaceAll("(?i)(password=)[^&;]*", "$1***");
    }
}
