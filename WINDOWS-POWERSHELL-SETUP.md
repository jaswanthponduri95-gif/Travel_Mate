# TravelMate — Windows PowerShell Setup Guide

## 1. Prerequisites

Install:
- **JDK 17 or newer** (the project compiles for Java 17).
- **MySQL 8** for persistent local data, or use the H2 demo profile.
- **Node.js** (optional; only needed for the JavaScript syntax checks below).

Open PowerShell in the extracted `TravelMate` project folder.

Check Java:
```powershell
java -version
```

## 2. Configure MySQL (recommended)

Start the MySQL Windows service (the service name may differ on your machine):
```powershell
Get-Service *mysql*
Start-Service MySQL80
```

Set the connection values for this PowerShell session. Replace the password with your own:
```powershell
$env:TM_DB_URL = "jdbc:mysql://localhost:3306/tripmate?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8"
$env:TM_DB_USER = "root"
$env:TM_DB_PASSWORD = "YOUR_MYSQL_PASSWORD"
```

The application initializes its schema and demo catalog at startup. Do not commit passwords or API keys.

## 3. Configure the AI provider (optional)

The app accepts OpenAI-compatible chat-completions providers. Configure AI from the application's **AI Settings** page after starting the app, or set environment variables before launch:
```powershell
$env:TM_AI_ENABLED = "true"
$env:TM_AI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/openai"
$env:TM_AI_API_KEY = "YOUR_PROVIDER_API_KEY"
$env:TM_AI_MODEL = "YOUR_MODEL_ID"
$env:TM_AI_ALLOWED_EMAILS = "your-login-email@example.com"
```
Use the provider's actual supported model identifier. Keep the API key private.

## 4. Run tests and build

From the project root:
```powershell
.\mvnw.cmd test
.\mvnw.cmd package
```

If the Maven wrapper cannot download Maven, check internet access to Maven Central and retry. A successful package creates:
```text
target\travelmate.war
```

## 5. Run the application

Start the embedded Jetty server:
```powershell
.\mvnw.cmd jetty:run
```

Open:
- Application: http://localhost:8080/travelmate/
- Health check: http://localhost:8080/travelmate/api/health

Stop the server with `Ctrl+C`.

## 6. JavaScript syntax check

If Node.js is installed:
```powershell
Get-ChildItem .\src\main\webapp\js\*.js | ForEach-Object {
    node --check $_.FullName
    if ($LASTEXITCODE -ne 0) { throw "JavaScript syntax error in $($_.Name)" }
}
```

## 7. Weather and transport behavior

- Weather uses the active trip destination's catalog coordinates, saved itinerary stop coordinates, or OpenStreetMap geocoding for free-text destinations.
- Open-Meteo is queried for up to 16 forecast days. Dates not returned by the provider remain explicitly unavailable; the UI does not substitute today's weather.
- Transport provider/catalog options are displayed against the active trip's origin and destination. The catalog fare/schedule metadata remains illustrative and is not a live seat inventory or quote.
- Booking links open in a separate tab. Self-drive directions continue to route to the selected hotel when one is set.

## 8. Catalog duration regression fix

This update restores the catalog duration metadata for `tr-001` (`1h 45m`) and
`tr-005` (`50m`) and converts those values to `duration_min` during catalog seeding.
For an existing database, application startup now repairs only those two records
when their stored JSON details still contain the legacy `Check route` placeholder.
It does not overwrite customized transport rows that no longer contain that placeholder.

After replacing the project files, restart the application so `DatabaseInitializer`
can apply this targeted compatibility repair, then run:
```powershell
.\mvnw.cmd test
.\mvnw.cmd package
```


## AI destination discovery

The Hotels and Food pages first query the destination catalog. If no matching records exist, they call `POST /api/ai/place-recommendations` using the active trip destination. Configure a usable AI provider in TravelMate Settings. AI results are labelled as suggestions; verify names, locations, prices and availability with the provider. No AI-generated ratings, prices or coordinates are fabricated.
