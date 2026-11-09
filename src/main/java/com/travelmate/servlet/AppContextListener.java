package com.travelmate.servlet;

import com.travelmate.db.DatabaseInitializer;
import com.travelmate.util.EnvLoader;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;

import java.sql.Driver;
import java.sql.DriverManager;
import java.util.Enumeration;

/** Sets up the database when the web app starts and releases JDBC drivers when it stops. */
@WebListener
public class AppContextListener implements ServletContextListener {

    @Override
    public void contextInitialized(ServletContextEvent event) {
        EnvLoader.load();
        DatabaseInitializer.initialize();
    }

    @Override
    public void contextDestroyed(ServletContextEvent event) {
        // Avoids Tomcat's "web application registered the JDBC driver but failed to unregister it" leak
        ClassLoader webappLoader = Thread.currentThread().getContextClassLoader();
        Enumeration<Driver> drivers = DriverManager.getDrivers();
        while (drivers.hasMoreElements()) {
            Driver driver = drivers.nextElement();
            if (driver.getClass().getClassLoader() != webappLoader) continue;
            try {
                DriverManager.deregisterDriver(driver);
            } catch (Exception ignored) {
                // best effort during shutdown
            }
        }
        try {
            Class.forName("com.mysql.cj.jdbc.AbandonedConnectionCleanupThread")
                    .getMethod("checkedShutdown").invoke(null);
        } catch (Exception ignored) {
            // MySQL driver not in use
        }
    }
}
