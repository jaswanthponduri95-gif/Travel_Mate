# TravelMate — Production Deployment Guide

**Application:** TravelMate Web Application  
**Version:** 1.0.0-SNAPSHOT  
**Artifact:** `travelmate.war`  
**Application Context Path:** `/travelmate`  
**Target Environment:** Apache Tomcat 10.1+ / Eclipse Jetty 11+ / Jakarta EE 9+  

---

## 1. System Prerequisites

| Component | Minimum Requirement | Recommended Production Specification |
| :--- | :--- | :--- |
| **Operating System** | Linux (Ubuntu 22.04 LTS / RHEL 9), Windows Server 2022 | Ubuntu 22.04 LTS or Debian 12 |
| **Java Runtime** | OpenJDK 17 LTS (Java 17 target bytecode) | Eclipse Temurin 17 LTS or Amazon Corretto 17 |
| **Servlet Container** | Apache Tomcat 10.1+ (Jakarta Servlet 5.0 / 6.0) | Apache Tomcat 10.1.x |
| **Database** | MySQL 8.0+ / 8.3+ | MySQL 8.0.36+ with InnoDB storage engine |
| **Memory (RAM)** | 2 GB minimum | 4 GB+ (allocated heap: `-Xms1g -Xmx2g`) |
| **Disk Space** | 1 GB free disk space | 10 GB+ SSD for logs and database storage |
| **Network** | Outbound HTTPS (port 443) access | Access to `api.open-meteo.com` and `nominatim.openstreetmap.org` |

---

## 2. Configuration & Precedence Hierarchy

TravelMate enforces a strict, secure configuration hierarchy. Sensitive credentials should **never** be committed to disk in configuration files.

### Configuration Resolution Order:
1. **JVM System Properties:** `-Dtm.db.*` and `-Dtm.ai.*` (Highest Priority)
2. **Environment Variables:** `TM_DB_*` and `TM_AI_*` (Recommended for Containers & Systemd)
3. **Properties Files on Classpath:** `db.properties` and `ai.properties` (Development Fallback Only)

### Required Database Environment Variables:
```bash
# Database JDBC URL (MySQL 8 parameters required for connection resilience)
export TM_DB_URL="jdbc:mysql://localhost:3306/travelmate?createDatabaseIfNotExist=true&useSSL=true&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8"

# Database Username
export TM_DB_USER="travelmate_app"

# Database Password (never commit plain text)
export TM_DB_PASSWORD="<STRONG_PRODUCTION_PASSWORD>"
```

In Windows PowerShell:
```powershell
$env:TM_DB_URL="jdbc:mysql://localhost:3306/travelmate?createDatabaseIfNotExist=true&useSSL=true&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8"
$env:TM_DB_USER="travelmate_app"
$env:TM_DB_PASSWORD="<STRONG_PRODUCTION_PASSWORD>"
```

### Optional AI Provider Environment Variables:
```bash
# Set to 'true' to activate live LLM itinerary generation (defaults to false for deterministic heuristic fallback)
export TM_AI_ENABLED="true"

# OpenAI-compatible Chat Completions Endpoint
export TM_AI_BASE_URL="https://openrouter.ai/api/v1"

# Provider API Key
export TM_AI_API_KEY="<YOUR_AI_PROVIDER_KEY>"

# Target LLM Model Identifier
export TM_AI_MODEL="google/gemini-2.0-flash-001"

# Authorized Administrator Email List (comma-separated; permitted to access /settings.html)
export TM_AI_ALLOWED_EMAILS="admin@example.com,ops@example.com"
```

---

## 3. MySQL 8 Database Setup

### Step 1: Provision Database & Dedicated Service User
Execute the following SQL commands on your MySQL 8 instance using `mysql -u root -p`:

```sql
-- 1. Create dedicated production database with UTF-8 character encoding
CREATE DATABASE IF NOT EXISTS travelmate
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

-- 2. Create restricted application user (replace placeholder with secure password)
CREATE USER IF NOT EXISTS 'travelmate_app'@'localhost'
  IDENTIFIED BY '<STRONG_PRODUCTION_PASSWORD>';

-- 3. Grant necessary DDL and DML privileges
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, DROP
  ON travelmate.* TO 'travelmate_app'@'localhost';

-- 4. Apply changes
FLUSH PRIVILEGES;
```

### Step 2: Automatic First-Run Schema Initialization & Migration
TravelMate features an automated, idempotent database bootstrapper ([`DatabaseInitializer.java`](file:///c:/Users/Tavva%20Purna%20Ganesh/OneDrive/Desktop/updated/project/src/main/java/com/travelmate/db/DatabaseInitializer.java)):
- On startup, [`AppContextListener`](file:///c:/Users/Tavva%20Purna%20Ganesh/OneDrive/Desktop/updated/project/src/main/java/com/travelmate/servlet/AppContextListener.java) automatically executes `schema.sql` to generate all tables if they do not exist.
- Performs schema migrations (`ADD COLUMN IF NOT EXISTS` emulation for `itinerary_stops` and `trips`).
- Automatically seeds the initial catalog data from `catalog-data.json`.
- Creates the default initial demonstration user (`demo.traveler@travelmate.in` / `demo12345`).

---

## 4. Building the Production WAR

From the repository root (`project/`), run the Maven Wrapper:

```powershell
# 1. Ensure JAVA_HOME points to JDK 17+
$env:JAVA_HOME = "C:\Program Files\Java\jdk-26.0.1"

# 2. Run clean automated tests
.\mvnw.cmd clean test

# 3. Package the WAR
.\mvnw.cmd package -DskipTests
```

On Linux/macOS:
```bash
export JAVA_HOME="/usr/lib/jvm/java-17-openjdk-amd64"
./mvnw clean test
./mvnw package -DskipTests
```

**Output Artifact:** `project/target/travelmate.war` (~4.65 MB, 116 archive entries).

---

## 5. Deployment to Apache Tomcat 10.1+

### Step 1: Configure Tomcat Environment (`setenv.sh` / `setenv.bat`)
Create or edit `$CATALINA_BASE/bin/setenv.sh` (Linux) or `setenv.bat` (Windows):

```bash
#!/bin/bash
# Tomcat JVM memory & database configuration
export CATALINA_OPTS="$CATALINA_OPTS -Xms1024m -Xmx2048m -XX:+UseG1GC"

# Production Database Credentials via Environment Variables
export TM_DB_URL="jdbc:mysql://localhost:3306/travelmate?createDatabaseIfNotExist=true&useSSL=true&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8"
export TM_DB_USER="travelmate_app"
export TM_DB_PASSWORD="<STRONG_PRODUCTION_PASSWORD>"

# Optional AI Configuration
export TM_AI_ENABLED="false"
```

Windows PowerShell / `setenv.bat`:
```cmd
set "CATALINA_OPTS=%CATALINA_OPTS% -Xms1024m -Xmx2048m -XX:+UseG1GC"
set "TM_DB_URL=jdbc:mysql://localhost:3306/travelmate?createDatabaseIfNotExist=true&useSSL=true&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8"
set "TM_DB_USER=travelmate_app"
set "TM_DB_PASSWORD=<STRONG_PRODUCTION_PASSWORD>"
set "TM_AI_ENABLED=false"
```

### Step 2: Deploy Artifact
1. Copy `target/travelmate.war` to Tomcat's `webapps/` directory:
   ```bash
   cp target/travelmate.war $CATALINA_BASE/webapps/travelmate.war
   ```
2. Start Tomcat:
   ```bash
   $CATALINA_BASE/bin/startup.sh
   ```
   Or in Windows PowerShell:
   ```powershell
   & "$env:CATALINA_HOME\bin\startup.bat"
   ```
3. Tomcat automatically unpacks the WAR to `$CATALINA_BASE/webapps/travelmate/` and mounts the application at:
   `http://<SERVER_IP_OR_DOMAIN>:8080/travelmate/`

---

## 6. Lightweight Development Deployment (Eclipse Jetty)

For development, testing, or quick evaluation without installing Tomcat:

```powershell
# Run with MySQL (using settings from db.properties or env vars):
.\mvnw.cmd jetty:run

# Run completely standalone with an embedded file-backed H2 database:
.\mvnw.cmd -Ph2 jetty:run
```
Access the application at: `http://localhost:8080/travelmate/`

---

## 7. External Service Requirements & Attribution

### 1. Open-Meteo Meteorological API
- **Endpoint:** `https://api.open-meteo.com/v1/forecast`
- **Authentication:** None required.
- **Attribution Requirement:** Weather data provided by [Open-Meteo.com](https://open-meteo.com/) under [CC-BY 4.0](https://creativecommons.org/licenses/by/4.0/). Attribution is displayed in the application footer and weather dashboard.
- **Resilience:** TravelMate maintains a 30-minute in-memory LRU cache to minimize network calls. If the service is unreachable, client-side offline fallbacks seamlessly operate.

### 2. OpenStreetMap Nominatim Geocoding API
- **Endpoint:** `https://nominatim.openstreetmap.org/search`
- **Authentication:** None required.
- **Usage Policy:** Strictly complies with Nominatim's usage policy:
  - Custom HTTP User-Agent: `TravelMate/1.0 (contact: travelmate.app@example.com)`
  - Global rate limiting: Minimum 1,000 ms between requests enforced by internal timestamp limiter.
  - 24-hour in-memory coordinate cache.
- **Attribution Requirement:** Map and geocoding data &copy; [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors.

### 3. AI Provider (Optional)
- **Supported Endpoints:** OpenRouter, Google Gemini (OpenAI-compatible endpoint), OpenAI, Groq, Ollama, LM Studio.
- **Resilience:** When disabled, unconfigured, or unreachable, TravelMate automatically falls back to deterministic heuristic itinerary generation without failing user requests.

---

## 8. Post-Deployment Verification Checklist

Once deployed, verify the installation with these validation steps:

1. **Health Endpoint:**  
   Navigate to `http://localhost:8080/travelmate/api/health`.  
   Expected response: `{"status":"UP","database":"jdbc:mysql://...","uptime":...}`
2. **Catalog API:**  
   Navigate to `http://localhost:8080/travelmate/api/destinations`.  
   Expected response: JSON object with `"count"` and `"items"`.
3. **Frontend Landing Page:**  
   Open `http://localhost:8080/travelmate/` in a web browser. Verify CSS styles, navigation links, and destination cards load correctly.
4. **Interactive Weather Dashboard:**  
   Open `http://localhost:8080/travelmate/weather.html`. Select a destination and confirm live meteorological conditions and activity scores render.
5. **AI Itinerary Workspace:**  
   Open `http://localhost:8080/travelmate/workspace.html`. Verify that itinerary days render and that dated days display weather cards or 7-day forecast availability notices.

---

## 9. Common Errors & Troubleshooting

| Symptom / Error | Root Cause | Solution |
| :--- | :--- | :--- |
| `CommunicationsException: Communications link failure` | MySQL 8 is not running or port 3306 is blocked. | Verify MySQL service status (`sudo systemctl status mysql` or `Get-Service mysql`). Confirm port 3306 is accessible. |
| `Public Key Retrieval is not allowed` | MySQL 8 `caching_sha2_password` auth plugin requires public key retrieval over non-SSL connections. | Ensure `allowPublicKeyRetrieval=true` is present in JDBC URL (`TM_DB_URL`). |
| `The server time zone value '...' is unrecognized` | MySQL server time zone is not configured or ambiguous. | Append `serverTimezone=UTC` to JDBC URL. |
| `Unknown database 'travelmate'` | Target schema has not been created. | Append `createDatabaseIfNotExist=true` to JDBC URL or run `CREATE DATABASE travelmate`. |
| `Access denied for user 'travelmate_app'@'localhost'` | Incorrect database credentials supplied. | Verify `TM_DB_USER` and `TM_DB_PASSWORD` match MySQL user privileges. |
| `404 Not Found on static assets` | Incorrect context path in request URL. | Access the application via `/travelmate/` (with trailing slash) matching the WAR filename. |
| `500 AI Generation Timeout` | Remote LLM provider slow or unresponsive. | Increase `ai.timeoutSeconds` in `ai.properties` or set `TM_AI_ENABLED=false` to use instant heuristics. |

---

## 10. Production HTTPS Reverse Proxy Configuration

In production, run Tomcat behind an HTTPS reverse proxy (such as Nginx or Caddy) to handle TLS termination, HTTP/2, and secure header enforcement.

### Recommended Nginx Configuration (`/etc/nginx/sites-available/travelmate`):

```nginx
server {
    listen 80;
    server_name travelmate.yourdomain.com;
    return 301 https://$host$request_uri;
}

server {
    listen 443 ssl http2;
    server_name travelmate.yourdomain.com;

    ssl_certificate /etc/letsencrypt/live/travelmate.yourdomain.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/travelmate.yourdomain.com/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers HIGH:!aNULL:!MD5;

    # Security Headers
    add_header X-Frame-Options "SAMEORIGIN" always;
    add_header X-Content-Type-Options "nosniff" always;
    add_header X-XSS-Protection "1; mode=block" always;
    add_header Referrer-Policy "strict-origin-when-cross-origin" always;
    add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;

    location /travelmate/ {
        proxy_pass http://127.0.0.1:8080/travelmate/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
        proxy_connect_timeout 60s;
        proxy_read_timeout 120s;
    }

    # Redirect root to /travelmate/
    location = / {
        return 301 /travelmate/;
    }
}
```

---

## 11. Secret Rotation & Zero-Downtime Updates

1. **Database Password Rotation:**
   - Create a second user in MySQL or update password:
     ```sql
     ALTER USER 'travelmate_app'@'localhost' IDENTIFIED BY '<NEW_STRONG_PASSWORD>';
     FLUSH PRIVILEGES;
     ```
   - Update `TM_DB_PASSWORD` in `setenv.sh` / `setenv.bat` or environment management system.
   - Restart Tomcat service or reload container.
2. **AI Provider API Key Rotation:**
   - Update `TM_AI_API_KEY` in environment variables.
   - Alternatively, authorized administrators can update the key live without restarting via `https://<DOMAIN>/travelmate/settings.html`.
3. **Application Updates:**
   - Deploy new WAR with versioned naming or standard blue/green deployment strategy.

---

## 12. Backup, Recovery & Disaster Recovery

### Database Backup
Automate daily backups using `mysqldump`:
```bash
# Automated Daily Backup Script
TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
BACKUP_DIR="/var/backups/travelmate"
mkdir -p $BACKUP_DIR

mysqldump -u root -p"<ROOT_PASSWORD>" \
  --single-transaction \
  --quick \
  --routines \
  --triggers \
  travelmate | gzip > "$BACKUP_DIR/travelmate_$TIMESTAMP.sql.gz"

# Retain backups for 30 days
find $BACKUP_DIR -type f -name "travelmate_*.sql.gz" -mtime +30 -delete
```

### Database Restoration
```bash
gunzip < /var/backups/travelmate/travelmate_20261104_120000.sql.gz | mysql -u root -p travelmate
```
