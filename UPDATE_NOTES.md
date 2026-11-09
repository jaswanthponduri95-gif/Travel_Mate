# TravelMate update notes

This package is based on the uploaded `updated1.zip` source. The `target/` directory and local H2 data file are intentionally excluded so an old WAR or local test database cannot overwrite the rebuilt application.

## Changes in this update

- External transport and hotel booking actions open a new browser tab only. They no longer fall back to navigating the TravelMate tab when a popup is blocked.
- Restaurant **View on Map** opens the selected restaurant in Google Maps in a new tab. It uses restaurant coordinates when present and otherwise searches by restaurant name and trip destination.
- Self-drive opens Google Maps directions in a new tab. If a hotel has been selected in TravelMate, that hotel is used as the route destination.
- Weather refreshes when the active trip is synchronized/changed. The newest request cancels an older in-flight request so stale destination weather does not replace the current trip's forecast.
- Trip itinerary generation no longer silently substitutes a demo/template itinerary when AI is unavailable or fails. It reports the AI/backend error and confirms persistence before navigating to the workspace.
- Duplicate itinerary form submissions are prevented while generation is running.
- AI output truncation is reported clearly when the provider ends a response because of its output-token limit.
- AI prompt output is more concise by default, and map enrichment is bounded to reduce post-generation delay.
- Local MySQL default database is `tripmate`; `TM_DB_URL`, `TM_DB_USER`, and `TM_DB_PASSWORD` can override it.

## Important limitations

AI response time still depends on the configured model/provider, its load, network latency, and token limits. This update reduces avoidable work after generation; it cannot guarantee instant responses from an external AI provider.

A popup blocker can prevent a new tab from opening. In that case TravelMate displays a message asking the user to allow pop-ups; it does not redirect the current tab.

## Build and run on Windows

From the folder containing `pom.xml`:

```powershell
mvn clean package
```

Deploy `target\travelmate.war` to Tomcat 10.1+ and open:

```text
http://localhost:8080/travelmate/
```

Configure the database credentials with `TM_DB_USER` and `TM_DB_PASSWORD` or edit `src/main/resources/db.properties`. Configure an OpenAI-compatible provider in AI Settings. Make sure `ai.allowedEmails` in `src/main/resources/ai.properties` contains the sign-in email addresses allowed to manage AI Settings; replace the example addresses before use.

The full Maven build and Tomcat deployment must still be run in the local Windows environment.


## Follow-up UI/destination changes
- Fixed the itinerary weather summary so badges and the Weather Advisory button wrap cleanly on mobile and desktop.
- Food and hotel pages now reload catalog records for the active trip destination, rather than retaining Mysuru sample listings after a trip change.
- Empty API results are shown honestly; the UI does not substitute restaurants or hotels from a different city.
- Removed the default Grand Mercure Mysuru hotel selection when the active trip has no selected accommodation.
