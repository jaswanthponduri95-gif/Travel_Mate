-- TRAVELMATE — MySQL 8 SCHEMA
-- Executed automatically on application start-up by DatabaseInitializer
-- (every statement is idempotent). You can also run it by hand:
--   mysql -u root -p < src/main/resources/schema.sql
-- The JDBC URL in db.properties uses createDatabaseIfNotExist=true, so the
-- `travelmate` database itself is created by the driver on first connect.

CREATE TABLE IF NOT EXISTS users (
    id            INT AUTO_INCREMENT PRIMARY KEY,
    full_name     VARCHAR(120) NOT NULL,
    email         VARCHAR(190) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS trips (
    id                   VARCHAR(64)   PRIMARY KEY,
    user_id              INT           NULL,
    title                VARCHAR(200)  NOT NULL,
    destination          VARCHAR(120),
    origin               VARCHAR(120),
    start_date           DATE          NULL,
    end_date             DATE          NULL,
    days_count           INT           NOT NULL DEFAULT 1,
    travelers            INT           NOT NULL DEFAULT 1,
    budget_total         DECIMAL(12,2) NOT NULL DEFAULT 0,
    budget_limit         DECIMAL(12,2) NULL,
    budget_category      VARCHAR(40),
    interests            VARCHAR(500),
    cover_image          VARCHAR(500),
    status               VARCHAR(20)   NOT NULL DEFAULT 'Draft',
    acc_name             VARCHAR(200),
    acc_type             VARCHAR(80),
    acc_address          VARCHAR(300),
    acc_price_per_night  DECIMAL(12,2),
    acc_nights           INT,
    weather_alt_accepted BOOLEAN       NOT NULL DEFAULT FALSE,
    ai_planned           BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at           TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_trips_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL
);

CREATE TABLE IF NOT EXISTS itinerary_days (
    id         INT AUTO_INCREMENT PRIMARY KEY,
    trip_id    VARCHAR(64)  NOT NULL,
    day_number INT          NOT NULL,
    title      VARCHAR(200),
    day_date   DATE         NULL,
    summary    VARCHAR(300),
    CONSTRAINT uq_day UNIQUE (trip_id, day_number),
    CONSTRAINT fk_days_trip FOREIGN KEY (trip_id) REFERENCES trips(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS itinerary_stops (
    row_id           INT AUTO_INCREMENT PRIMARY KEY,
    stop_id          VARCHAR(64)   NOT NULL,
    day_id           INT           NOT NULL,
    sort_order       INT           NOT NULL DEFAULT 0,
    name             VARCHAR(200)  NOT NULL,
    category         VARCHAR(60),
    category_icon    VARCHAR(60),
    time_label       VARCHAR(20),
    duration         VARCHAR(30),
    lat              DOUBLE,
    lng              DOUBLE,
    cost             DECIMAL(12,2) NOT NULL DEFAULT 0,
    image            VARCHAR(500),
    notes            TEXT,
    transit_mode     VARCHAR(60),
    transit_icon     VARCHAR(60),
    transit_duration VARCHAR(30),
    transit_distance VARCHAR(30),
    transit_cost     DECIMAL(12,2),
    weather_impacted BOOLEAN       NOT NULL DEFAULT FALSE,
    weather_replaced BOOLEAN       NOT NULL DEFAULT FALSE,
    -- Real place details from OpenStreetMap (added in v1.1; DatabaseInitializer adds them to older databases)
    address          VARCHAR(500),
    place_ref        VARCHAR(80),
    opening_hours    VARCHAR(300),
    website          VARCHAR(500),
    phone            VARCHAR(60),
    CONSTRAINT fk_stops_day FOREIGN KEY (day_id) REFERENCES itinerary_days(id) ON DELETE CASCADE
);

-- AI model settings saved from the AI Settings page (key/value pairs)
CREATE TABLE IF NOT EXISTS ai_settings (
    setting_key   VARCHAR(60)  PRIMARY KEY,
    setting_value TEXT
);

CREATE TABLE IF NOT EXISTS budget_items (
    row_id    INT AUTO_INCREMENT PRIMARY KEY,
    item_id   VARCHAR(64)   NOT NULL,
    trip_id   VARCHAR(64)   NOT NULL,
    name      VARCHAR(200)  NOT NULL,
    category  VARCHAR(80)   NOT NULL,
    amount    DECIMAL(12,2) NOT NULL DEFAULT 0,
    status    VARCHAR(20)   NOT NULL DEFAULT 'Estimated',
    day_label VARCHAR(40),
    CONSTRAINT uq_budget_item UNIQUE (trip_id, item_id),
    CONSTRAINT fk_budget_trip FOREIGN KEY (trip_id) REFERENCES trips(id) ON DELETE CASCADE
);


-- Phase 3: Static Catalog Data Tables (Destinations, Hotels, Restaurants, Transport Options)
CREATE TABLE IF NOT EXISTS destinations (
    id             VARCHAR(64)   PRIMARY KEY,
    name           VARCHAR(200)  NOT NULL,
    state          VARCHAR(120),
    category       VARCHAR(60),
    region         VARCHAR(40),
    suggested_days INT,
    details        TEXT          NOT NULL
);

CREATE TABLE IF NOT EXISTS hotels (
    id              VARCHAR(64)   PRIMARY KEY,
    name            VARCHAR(200)  NOT NULL,
    destination     VARCHAR(120),
    type            VARCHAR(80),
    rating          DECIMAL(3,1),
    price_per_night DECIMAL(12,2),
    details         TEXT          NOT NULL
);

CREATE TABLE IF NOT EXISTS restaurants (
    id               VARCHAR(64)   PRIMARY KEY,
    name             VARCHAR(200)  NOT NULL,
    destination      VARCHAR(120),
    meal_type        VARCHAR(80),
    dietary          VARCHAR(120),
    rating           DECIMAL(3,1),
    price_per_person DECIMAL(12,2),
    details          TEXT          NOT NULL
);

CREATE TABLE IF NOT EXISTS transport_options (
    id           VARCHAR(64)   PRIMARY KEY,
    mode         VARCHAR(20),
    provider     VARCHAR(200),
    origin       VARCHAR(120),
    destination  VARCHAR(120),
    price        DECIMAL(12,2),
    duration_min INT,
    details      TEXT          NOT NULL
);
