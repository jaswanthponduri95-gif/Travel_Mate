# TravelMate

HTML/JS frontend + Java Servlet backend (Jakarta Servlet 5, Tomcat 10+) + MySQL 8 via JDBC.


### 1. With MySQL (the real setup)

1. Start MySQL 8 (MySQL Workbench / XAMPP / `net start mysql80`).
2. Put your MySQL user and password in `src/main/resources/db.properties`
   (or set the environment variables `TM_DB_USER` / `TM_DB_PASSWORD`).
   You do **not** need to create the database or tables: on start-up the app creates the
   `tripmate` database, runs `schema.sql`, and loads the demo data.
3. Start the server:
   ```
   mvn jetty:run
   ```
   or build `mvn package` and deploy `target/travelmate.war` to **Tomcat 10 or newer**
   (Tomcat 9 uses `javax.servlet` and will not load this app).
4. Open http://localhost:8080/travelmate/ and check http://localhost:8080/travelmate/api/health:
   it should report `"database":"up"`.

### 2. Without MySQL (quick demo)

```
mvn -Ph2 jetty:run
```
Uses an embedded H2 database in MySQL mode, stored in `./data/`.

### Tests

```
mvn test
```
Runs the DAO tests against an in-memory H2 database.

## AI model (Plan a Trip)

The planner can use any **OpenAI-compatible** chat API: OpenRouter, OpenAI, Groq, Gemini's
OpenAI endpoint, Together, Mistral, an experimental lab endpoint, or a local Ollama / LM Studio.

1. Put the sign-in emails of you and your friends in `ai.allowedEmails` (`src/main/resources/ai.properties`,
   or the `TM_AI_ALLOWED_EMAILS` environment variable). **Only those accounts see the "AI Settings" link**
   in the navbar; the server answers 403 to everyone else.
2. Sign in with one of those accounts and open **AI Settings** (`settings.html`). Choose a provider preset or
   "Custom", then enter the **base URL** (e.g. `https://openrouter.ai/api/v1`), **API key** and **model id**.
   "Load models" fetches the provider's model list.
3. Click **Test Connection**, then turn on "Use AI for trip planning" and **Save**.

The settings are saved in the database, so everyone who uses the same database gets the same AI model; only one
of you has to enter the key (see "Sharing one database with friends" below).

Advanced: temperature, max tokens, timeout, JSON mode, and extra HTTP headers.
Settings are saved in the database (`ai_settings` table). Defaults come from
`src/main/resources/ai.properties` or the `TM_AI_BASE_URL` / `TM_AI_API_KEY` / `TM_AI_MODEL`
environment variables. The key is never sent back to the browser, and changing the base URL to
another server removes the saved key. If the model call fails, the planner falls back to the
built-in template and shows the reason.

## Real places from the map + AI Tour Plan (Itinerary workspace)

- **Search box on the map** or **click anywhere on the map**: details come from OpenStreetMap
  (Nominatim): name, type, address, opening hours, website, phone. "Add to trip" saves the place
  as a stop in the database (`itinerary_stops.address`, `place_ref`, `opening_hours`, `website`, `phone`).
- **AI Tour Plan is automatic.** There is no button: when a trip is opened in the workspace (a new trip, or one
  saved earlier from My Trips) and AI is set up, the AI schedules every day, orders the stops, adds times,
  durations, tips and transit, and suggests extra places and lunch stops. A banner shows the progress; the stops
  are locked while it writes. Your own stops keep their map data, and AI suggestions are looked up on OpenStreetMap
  so their pins and addresses are real.
- The finished plan is saved and the trip is marked `ai_planned`, so it is planned **once**: later visits (yours
  or a friend's) just show the saved plan and your edits are never overwritten. If the model fails, the banner
  offers "Try again" and the trip stays unplanned. Trips from Plan a Trip that the AI already wrote are not planned twice.
- Shared links (`workspace.html?tripId=...`) open that trip from the database.
- The public OpenStreetMap service allows about 1 lookup per second, so the server rate-limits and caches
  lookups (an AI plan with many new places can take ~1 s per place). To use your own Nominatim server,
  set `TM_GEO_URL`.

Endpoints: `GET /api/places/search?q=...&lat=..&lng=..`, `GET /api/places/reverse?lat=..&lng=..`,
`POST /api/ai/tour-plan`.

## Sharing one database with friends

Trips, accounts and the AI settings all live in one database and trips are not tied to a single user, so you and your
friends see the same trips and the same AI model as soon as every copy of TravelMate points at the **same MySQL database**:

1. Host one MySQL 8 database that everyone can reach (a hosted MySQL service, or your own server).
2. Everyone starts TravelMate with `TM_DB_URL`, `TM_DB_USER` and `TM_DB_PASSWORD` set to that database
   (or the same values in `db.properties`). Don't send the real password around inside the project zip.
3. Each friend registers with an email listed in `ai.allowedEmails` (only needed for those who may change AI settings).
   Register your own email first: sign-up does not verify email addresses.
4. One person enters the model and key once on AI Settings; it is stored in the shared database (readable by anyone
   who can query that database), and every friend gets automatic AI plans.

If each friend runs their own `localhost` MySQL, the databases are separate and nothing is shared.

## Troubleshooting the database connection

`/api/health` shows the exact JDBC error. Common ones:

| Error | Fix |
|---|---|
| `Communications link failure` | MySQL isn't running, or not on port 3306 |
| `Access denied for user 'root'@'localhost'` | Wrong `db.user` / `db.password` in `db.properties` |
| `Public Key Retrieval is not allowed` | Keep `allowPublicKeyRetrieval=true` in `db.url` |
| `No suitable driver found` | Fixed: the driver is loaded explicitly in `DBConnection` |

If the backend is unreachable, the pages keep working in **offline mode** (data saved in
the browser only) and show a warning toast.

## API

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/health` | Server + database status |
| GET / POST | `/api/trips` | List trips / create a trip |
| GET / PUT / DELETE | `/api/trips/{id}` | Full trip with days & stops / replace / delete |
| POST | `/api/trips/{id}/duplicate` | Copy a trip (with its budget) |
| GET / PUT | `/api/budget/{tripId}` | Budget / set limit |
| POST / DELETE | `/api/budget/{tripId}/items[/{itemId}]` | Add / remove an expense |
| POST | `/api/auth/register`, `/api/auth/login`, `/api/auth/logout` | Accounts (session cookie) |
| GET | `/api/auth/me` | Signed-in user, or `null` |
| GET | `/api/ai/status` | Is AI set up? (public) |
| POST | `/api/ai/tour-plan` | AI plan for a trip's days |
| GET / PUT / DELETE | `/api/ai/settings` | AI model settings (owners only) |
| POST | `/api/demo/reset` | Restore demo data |

Demo account: `demo.traveler@travelmate.in` / `demo12345`.

## Code layout

- `src/main/java/com/travelmate/db` – connection config (`DBConnection`), schema + demo data loader
- `src/main/java/com/travelmate/dao` – JDBC DAOs (`TripDAO`, `BudgetDAO`, `UserDAO`)
- `src/main/java/com/travelmate/servlet` – REST servlets, CORS filter, start-up listener
- `src/main/resources` – `db.properties`, `schema.sql`, `seed-data.json`
- `src/main/webapp/js/store.js` – frontend data layer: calls the API, caches in localStorage


## Recent frontend and AI reliability updates

- Booking links for transport and hotels open in a separate tab; popup-blocked cases no longer redirect the current TravelMate tab.
- Restaurant directions open the restaurant in Google Maps. Self-drive directions end at the selected hotel when one has been chosen.
- Weather refreshes when the active trip changes.
- The planner reports AI/backend failures instead of silently creating a demo itinerary.
- AI output truncation is reported clearly; generated prompt output and map enrichment are bounded to reduce avoidable waiting.
- Local MySQL default database: `tripmate`.
See `UPDATE_NOTES.md` for setup and limitations.
