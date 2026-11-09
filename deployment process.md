# TravelMate — Comprehensive Production Deployment Guide

This guide details the end-to-end process for deploying the **TravelMate** web application (Java 17 / Jakarta Servlet 5.0 + HTML5/CSS3/JavaScript frontend + MySQL 8 / H2 database).

---

## 1. Where to Deploy What? (Platform Recommendations)

| Component | Recommended Platform | Alternative Platform | Why This Choice? |
| :--- | :--- | :--- | :--- |
| **Web Application** (Frontend + Backend in one WAR/Container) | **[Railway](https://railway.app/)** or **[Render](https://render.com/)** | **AWS App Runner / Cloud Run** or **VPS (DigitalOcean / Hetzner)** | - Zero-config Docker deployment via the included `Dockerfile`<br>- Automatic SSL certificate (`https://`)<br>- Automatic health checks & zero-downtime deploys |
| **Database** (MySQL 8) | **Railway MySQL** or **[Aiven for MySQL](https://aiven.io/free-mysql-database)** | **AWS RDS MySQL 8** or **GCP Cloud SQL** | - Managed backups, automatic restarts<br>- Direct connection via `DATABASE_URL` (TravelMate parses it automatically) |
| **AI Planning Service** (OpenAI-compatible) | **Google Gemini API** (via Google AI Studio) | **OpenAI API** or **Groq Cloud** | - Free-tier availability via Google AI Studio<br>- Fast inference, generous rate limits for trip itinerary planning |
| **Static Geocoding & Weather** | **OpenStreetMap (Nominatim)** + **Open-Meteo** | Self-hosted Nominatim (optional) | - Free, no API keys needed for standard student/demo traffic |

---

## 2. Sensitive Configuration & Environment Variables (`.env`)

All sensitive credentials and environment-specific settings have been decoupled from the codebase into `.env` (development) and cloud environment variables (production).

> [!IMPORTANT]
> Never commit `.env` to Git. The `.gitignore` file is already configured to exclude `.env` and `.env.*` while keeping `.env.example`.

### Complete Variables Reference

| Variable Name | Required? | Example Value | Description |
| :--- | :---: | :--- | :--- |
| `TM_DB_URL` | Yes (or `DATABASE_URL`) | `jdbc:mysql://localhost:3306/tripmate?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8` | JDBC connection string. For embedded H2 demo: `jdbc:h2:file:./data/travelmate-dev;MODE=MySQL;DATABASE_TO_LOWER=TRUE` |
| `TM_DB_USER` | Yes | `root` or `sa` | Database user account |
| `TM_DB_PASSWORD` | Yes | `your_mysql_password` | Database password (**SENSITIVE**) |
| `DATABASE_URL` / `MYSQL_URL` | Optional | `mysql://root:secret@containers-us-west-1.railway.app:3306/railway` | Cloud URI standard provided by Railway/Render. TravelMate automatically parses host, port, user, password, and database into JDBC format. |
| `TM_AI_ENABLED` | No | `true` or `false` | Enables/disables automated AI trip generation on startup |
| `TM_AI_BASE_URL` | If AI enabled | `https://generativelanguage.googleapis.com/v1beta/openai` | OpenAI-compatible API base URL (Gemini, OpenAI, OpenRouter, Groq, Ollama) |
| `TM_AI_API_KEY` | If AI enabled | `AIzaSy...` or `sk-...` | AI provider API secret key (**SENSITIVE**) |
| `TM_AI_MODEL` | If AI enabled | `gemini-2.5-flash` or `gpt-4o-mini` | AI Model ID |
| `TM_AI_ALLOWED_EMAILS` | Recommended | `admin@example.com,user@example.com` | Comma-separated emails allowed to access `settings.html` |
| `TM_GEO_URL` | No | `https://nominatim.openstreetmap.org` | OpenStreetMap Nominatim endpoint |
| `TM_WEATHER_URL` | No | `https://api.open-meteo.com/v1/forecast` | Open-Meteo forecast API endpoint |
| `PORT` | Container/PaaS | `8080` (or injected by PaaS) | Server listening port |

---

## 3. Step-by-Step Deployment Methods

### Method 1: Deploy on Railway (Fastest & Easiest — Recommended)

Railway offers a unified dashboard for running both the MySQL database and the Java/Tomcat Docker container with automatic HTTPS.

1. **Push your code to GitHub:**
   ```bash
   git init
   git add .
   git commit -m "TravelMate production ready"
   git branch -M main
   git remote add origin https://github.com/YOUR_USERNAME/TravelMate.git
   git push -u origin main
   ```
2. **Create a Railway Project:**
   - Log in to [Railway.app](https://railway.app/).
   - Click **New Project** → **Provision MySQL**.
   - Railway will provision a MySQL 8 database and automatically create a `DATABASE_URL` variable.
3. **Deploy the TravelMate Application:**
   - In the same project canvas, click **New** → **GitHub Repo** → select your `TravelMate` repository.
   - Go to the service **Settings**:
     - **Build**: Ensure it detects the `Dockerfile` (or set Root Directory to `TravelMate` if your repo has the subfolder).
   - Go to the service **Variables** tab and add:
     - `DATABASE_URL`: `${{MySQL.DATABASE_URL}}` (links directly to the MySQL database service!)
     - `TM_AI_ENABLED`: `true`
     - `TM_AI_BASE_URL`: `https://generativelanguage.googleapis.com/v1beta/openai`
     - `TM_AI_MODEL`: `gemini-2.5-flash`
     - `TM_AI_API_KEY`: *(Your Google Gemini or OpenAI API Key)*
     - `TM_AI_ALLOWED_EMAILS`: `your-email@example.com`
4. **Generate Public Domain:**
   - Under service **Settings** → **Networking** → Click **Generate Domain**.
   - Open your new public URL (e.g., `https://travelmate-production.up.railway.app/`).
   - Check health: `https://travelmate-production.up.railway.app/api/health`.

---

### Method 2: Deploy on Render

1. Log in to [Render.com](https://render.com/).
2. **Create a Database:**
   - You can provision a MySQL instance on [Aiven.io](https://aiven.io) (free tier) or use Render's PostgreSQL/MySQL partner.
   - Note the connection string or host, port, database, user, password.
3. **Deploy Web Service:**
   - Click **New +** → **Web Service**.
   - Connect your GitHub repository.
   - Set **Runtime** to **Docker**.
   - If `Dockerfile` is inside `TravelMate/`, set **Root Directory** to `TravelMate`.
4. **Add Environment Variables:**
   - In the **Environment** tab, add:
     - `TM_DB_URL`: `jdbc:mysql://<host>:<port>/<dbname>?useSSL=true&allowPublicKeyRetrieval=true&serverTimezone=UTC`
     - `TM_DB_USER`: `<database_user>`
     - `TM_DB_PASSWORD`: `<database_password>`
     - `TM_AI_ENABLED`: `true`
     - `TM_AI_BASE_URL`: `https://generativelanguage.googleapis.com/v1beta/openai`
     - `TM_AI_MODEL`: `gemini-2.5-flash`
     - `TM_AI_API_KEY`: `<your_key>`
     - `TM_AI_ALLOWED_EMAILS`: `<your_email>`
5. Click **Create Web Service**. Render will automatically build the multi-stage Docker container and issue a free SSL certificate.

---

### Method 3: Deploy with Docker Compose (VPS: AWS EC2, DigitalOcean, Linode, Ubuntu)

If deploying to your own virtual server (Ubuntu 22.04 / 24.04 LTS):

1. **Install Docker and Docker Compose on the VPS:**
   ```bash
   sudo apt update && sudo apt install -y docker.io docker-compose-v2
   sudo systemctl enable --now docker
   ```
2. **Clone the Project & Configure `.env`:**
   ```bash
   git clone https://github.com/YOUR_USERNAME/TravelMate.git
   cd TravelMate/TravelMate
   cp .env.example .env
   nano .env
   ```
   *Edit `.env` to set your MySQL password and AI keys.*
3. **Start the Stack:**
   ```bash
   docker compose up -d --build
   ```
4. **Verify Container Status:**
   ```bash
   docker compose ps
   curl http://localhost:8080/api/health
   ```
5. **Set up Nginx Reverse Proxy & SSL (Certbot):**
   ```nginx
   # /etc/nginx/sites-available/travelmate
   server {
       server_name yourdomain.com;

       location / {
           proxy_pass http://127.0.0.1:8080;
           proxy_set_header Host $host;
           proxy_set_header X-Real-IP $remote_addr;
           proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
           proxy_set_header X-Forwarded-Proto $scheme;
       }
   }
   ```
   Enable site & generate free SSL:
   ```bash
   sudo ln -s /etc/nginx/sites-available/travelmate /etc/nginx/sites-enabled/
   sudo nginx -t && sudo systemctl reload nginx
   sudo certbot --nginx -d yourdomain.com
   ```

---

### Method 4: Traditional Standalone Apache Tomcat 10.1+ Deployment

If deploying to an existing Tomcat server:

1. **Ensure Prerequisites:**
   - Java 17+ installed (`java -version`).
   - Apache Tomcat 10.1+ installed (Tomcat 9 will NOT work because Jakarta Servlet 5 is required).
   - MySQL 8 installed and running.
2. **Build the Production WAR File:**
   ```powershell
   .\mvnw.cmd clean package -DskipTests
   ```
   *The package is created at `target\travelmate.war`.*
3. **Deploy to Tomcat:**
   - To serve from root URL (`http://your-server:8080/`):
     - Delete existing `tomcat/webapps/ROOT/` folder.
     - Copy `target/travelmate.war` to `tomcat/webapps/ROOT.war`.
   - To serve from `/travelmate/`:
     - Copy `target/travelmate.war` to `tomcat/webapps/travelmate.war`.
4. **Configure Environment Variables in Tomcat:**
   - Create or edit `tomcat/bin/setenv.sh` (Linux) or `tomcat/bin/setenv.bat` (Windows):
     ```bash
     # Linux (bin/setenv.sh)
     export TM_DB_URL="jdbc:mysql://localhost:3306/tripmate?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8"
     export TM_DB_USER="root"
     export TM_DB_PASSWORD="your_mysql_password"
     export TM_AI_ENABLED="true"
     export TM_AI_BASE_URL="https://generativelanguage.googleapis.com/v1beta/openai"
     export TM_AI_MODEL="gemini-2.5-flash"
     export TM_AI_API_KEY="your_key"
     export TM_AI_ALLOWED_EMAILS="admin@example.com"
     ```
5. **Start Tomcat:**
   - Windows: `bin\startup.bat`
   - Linux: `bin/startup.sh`

---

## 4. Frontend Pages & Routing Matrix

The frontend uses responsive Bootstrap 5, Leaflet interactive maps, and clean relative API routing compatible with both root deployment (`/`) and context deployment (`/travelmate/`).

| Page | Path | Functions / Features |
| :--- | :--- | :--- |
| **Home** | `index.html` | Hero trip configurator, destination showcase, quick plan action |
| **Explore** | `explore.html` | Multi-category destination filters (Heritage, Nature, Beach), modal place details |
| **Plan a Trip** | `plan.html` | Configurator form with pace, budget, interests, and automated AI generation |
| **Workspace** | `workspace.html` | Interactive Day-wise itinerary timeline + Leaflet map synchronization |
| **My Trips** | `trips.html` | Trip library, duplicate, delete, and open active trip |
| **Budget** | `budget.html` | Expense tracker, budget limits, categorical progress bars |
| **Weather** | `weather.html` | Live 16-day Open-Meteo forecasts, heuristics weather advisory, outdoor activity alerts |
| **Hotels** | `hotels.html` | Destination accommodations catalog + AI hotel discovery fallback |
| **Transport** | `transport.html` | Route options (Flight, Train, Bus, Drive), fare comparisons, Google Maps routing |
| **Food & Dining** | `food.html` | Local restaurant recommendations, cuisine tags, Google Maps directions |
| **AI Settings** | `settings.html` | Model selector, token limits, connection tester (protected by allowlist) |
| **Account** | `auth.html` | User registration, login, and session persistence |
| **About** | `about.html` | Project overview, technology stack documentation |

---

## 5. Post-Deployment Verification Checklist

Once deployed, run this checklist:

1. **Verify Health Endpoint:**
   ```bash
   curl -i https://your-domain.com/api/health
   ```
   *Expected response: HTTP 200 with `{"server":"up","database":"up"}`.*
2. **Verify Database Seeding:**
   - Open `https://your-domain.com/explore.html`.
   - Destinations (Mysuru, Goa, Ooty, etc.) should load immediately from `/api/destinations`.
3. **Verify Weather Service:**
   - Open `https://your-domain.com/weather.html`.
   - Check that live temperatures and forecast badges populate.
4. **Verify Map Place Search:**
   - Open `https://your-domain.com/workspace.html`.
   - Use the place search input on the map to search for any landmark (e.g. `Mysore Palace`).
5. **Verify AI Generation (if enabled):**
   - Sign in with an email listed in `TM_AI_ALLOWED_EMAILS`.
   - Open `settings.html` and click **Test Connection**.
   - Create a 2-day trip in `plan.html` to confirm that the AI automatically generates and writes stops to your itinerary.
