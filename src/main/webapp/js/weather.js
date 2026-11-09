/**
 * TRAVELMATE — WEATHER & SMART ALTERNATIVES LOGIC (PHASE 7)
 * Live Open-Meteo REST API integration (/api/weather/*), geocoding place search,
 * deterministic heuristics advice display, 6 activity suitabilities, and alternative replacement.
 */

let weatherLocation = {
  name: "Mysuru, Karnataka",
  lat: 12.2958,
  lng: 76.6394,
  date: null
};

let activeWeatherAbortController = null;
let isWeatherFetching = false;

document.addEventListener("DOMContentLoaded", () => {
  initWeatherLocationFromActiveTrip().then(fetchLiveWeatherData);
  document.addEventListener("tm:synced", () => {
    initWeatherLocationFromActiveTrip().then(() => {
      renderAlternativeSection();
      fetchLiveWeatherData();
    });
  });

  // Setup click outside to close suggestions
  document.addEventListener("click", (e) => {
    const box = document.getElementById("wt-search-suggestions");
    const input = document.getElementById("wt-place-input");
    if (box && !box.contains(e.target) && e.target !== input) {
      box.classList.add("d-none");
    }
  });
});

/**
 * Initialize weather location from active trip coordinates or catalog
 */
async function initWeatherLocationFromActiveTrip() {
  const store = window.TravelMateStore;
  const trip = store?.getActiveTrip?.();
  if (!trip?.destination) return;
  const destinationName = String(trip.destination).trim();
  const dest = typeof findDestination === "function" ? findDestination(destinationName) : null;
  let lat = Array.isArray(dest?.coordinates) ? Number(dest.coordinates[0]) : null;
  let lng = Array.isArray(dest?.coordinates) ? Number(dest.coordinates[1]) : null;
  if (!isValidCoord(lat, lng) && Array.isArray(trip.days)) {
    const stop = trip.days.flatMap(day => day.stops || [])
      .find(item => isValidCoord(Number(item.lat), Number(item.lng)));
    if (stop) { lat = Number(stop.lat); lng = Number(stop.lng); }
  }
  if (!isValidCoord(lat, lng) && store?.request) {
    try {
      const place = await store.request("GET", `/places/geocode?q=${encodeURIComponent(destinationName)}`);
      if (place && isValidCoord(Number(place.lat), Number(place.lng))) {
        lat = Number(place.lat); lng = Number(place.lng);
      }
    } catch (error) { console.warn("Could not geocode active trip destination for weather:", error); }
  }
  weatherLocation.name = destinationName;
  weatherLocation.lat = isValidCoord(lat, lng) ? lat : null;
  weatherLocation.lng = isValidCoord(lat, lng) ? lng : null;
  weatherLocation.date = trip.startDate || null;
  syncInputsWithLocation();
  if (!isValidCoord(weatherLocation.lat, weatherLocation.lng)) {
    showWeatherError("Destination coordinates unavailable",
      `Could not resolve weather coordinates for ${destinationName}. Search for the destination or enter coordinates manually.`);
  }
}

function syncInputsWithLocation() {
  const latInput = document.getElementById("wt-lat-input");
  const lngInput = document.getElementById("wt-lng-input");
  const dateInput = document.getElementById("wt-date-input");
  if (latInput) latInput.value = isValidCoord(weatherLocation.lat, weatherLocation.lng) ? weatherLocation.lat.toFixed(4) : "";
  if (lngInput) lngInput.value = isValidCoord(weatherLocation.lat, weatherLocation.lng) ? weatherLocation.lng.toFixed(4) : "";
  if (dateInput && weatherLocation.date) dateInput.value = weatherLocation.date;
}

function isValidCoord(lat, lng) {
  return typeof lat === "number" && typeof lng === "number" &&
    !isNaN(lat) && !isNaN(lng) &&
    lat >= -90 && lat <= 90 &&
    lng >= -180 && lng <= 180;
}

// ------------------------------------------------------------------ FETCHING

async function fetchLiveWeatherData() {
  const store = window.TravelMateStore;
  if (!store) return;

  const lat = Number(weatherLocation.lat);
  const lng = Number(weatherLocation.lng);
  const date = weatherLocation.date;

  if (!isValidCoord(lat, lng)) {
    showWeatherError("Invalid Coordinates", "Latitude must be between -90 and 90, and longitude between -180 and 180.");
    return;
  }

  // Cancel any older request so the newest active trip always wins.
  if (activeWeatherAbortController) activeWeatherAbortController.abort();
  const requestController = new AbortController();
  activeWeatherAbortController = requestController;
  const signal = requestController.signal;

  isWeatherFetching = true;
  setWeatherLoading(true);
  hideWeatherAlerts();

  try {
    // The 16-day response includes current conditions. Advice then reuses the backend cache.
    const forecastData = await store.getWeatherForecast(lat, lng, 16, { signal });
    if (signal.aborted) return;
    let adviceData = null;
    try {
      adviceData = await store.getWeatherAdvice(lat, lng, date, { signal });
    } catch (adviceError) {
      console.warn("Weather advisory unavailable; forecast remains available:", adviceError);
    }
    renderCurrentWeather(forecastData?.current || null, forecastData);
    renderForecast(forecastData);
    renderAdvice(adviceData);

    // Stale cached indicator
    if ((forecastData && forecastData.isStale) || (forecastData && forecastData.isStale)) {
      const staleEl = document.getElementById("wt-state-stale");
      if (staleEl) staleEl.classList.remove("d-none");
    }

    renderAlternativeSection();

  } catch (err) {
    if (err.name !== "AbortError") {
      showWeatherError("Network Exception", err.message || "An unexpected error occurred while communicating with the weather service.");
      renderForecast(null);
    }
  } finally {
    if (activeWeatherAbortController === requestController) {
      isWeatherFetching = false;
      activeWeatherAbortController = null;
      setWeatherLoading(false);
    }
  }
}

// ------------------------------------------------------------------ RENDERERS

function renderCurrentWeather(current, forecast) {
  const cityTitle = document.getElementById("wt-city-title");
  const coordsDisplay = document.getElementById("wt-coords-display");
  const tzBadge = document.getElementById("wt-timezone-badge");
  const retrievedBadge = document.getElementById("wt-retrieved-badge");

  if (cityTitle) cityTitle.textContent = `${weatherLocation.name} Weather`;
  if (coordsDisplay) coordsDisplay.textContent = `Coordinates: ${weatherLocation.lat.toFixed(4)}° N, ${weatherLocation.lng.toFixed(4)}° E`;
  if (tzBadge && forecast && forecast.timezone) tzBadge.textContent = `Timezone: ${forecast.timezone}`;

  const tempEl = document.getElementById("wt-current-temp");
  const condEl = document.getElementById("wt-current-cond");
  const iconWrap = document.getElementById("wt-current-icon-wrap");
  const appTempEl = document.getElementById("wt-apparent-temp");
  const humidityEl = document.getElementById("wt-humidity");
  const windEl = document.getElementById("wt-wind");
  const precipEl = document.getElementById("wt-precipitation");

  const cw = current || (forecast && forecast.current);
  if (cw) {
    if (tempEl) tempEl.textContent = cw.temperature != null ? `${Math.round(cw.temperature)}°C` : "--";
    if (condEl) condEl.textContent = cw.condition || "Moderate";
    if (iconWrap && cw.icon) {
      iconWrap.innerHTML = `<i class="bi ${escapeHtml(cw.icon)}"></i>`;
    }
    if (appTempEl) appTempEl.textContent = cw.apparentTemperature != null ? `${Math.round(cw.apparentTemperature)}°C` : "N/A";
    if (humidityEl) humidityEl.textContent = cw.relativeHumidity != null ? `${cw.relativeHumidity}%` : "N/A";
    if (windEl) windEl.textContent = cw.windSpeed != null ? `${cw.windSpeed.toFixed(1)} km/h` : "N/A";
    if (precipEl) precipEl.textContent = cw.precipitation != null ? `${cw.precipitation.toFixed(1)} mm` : "0.0 mm";

    if (retrievedBadge) {
      retrievedBadge.className = "badge bg-success-subtle text-success border small";
      retrievedBadge.textContent = "Live Open-Meteo";
    }
  }
}

function renderForecast(forecastData) {
  const container = document.getElementById("wt-forecast-container");
  const hourlyContainer = document.getElementById("wt-hourly-container");
  if (!container) return;

  if (!forecastData || !forecastData.daily || forecastData.daily.length === 0) {
    container.innerHTML = `
      <div class="col-12 py-3 text-center text-muted">
        <i class="bi bi-cloud-slash fs-4 d-block mb-1"></i>
        <span>Daily forecast unavailable for this location.</span>
      </div>
    `;
    return;
  }

  const days = forecastData.daily;
  container.innerHTML = days.map((f, i) => {
    const isAlert = !!f.alert;
    const dateObj = new Date(f.date + "T00:00:00");
    const dayName = i === 0 ? "Today" : dateObj.toLocaleDateString("en-US", { weekday: "short" });
    const formattedDate = dateObj.toLocaleDateString("en-US", { month: "short", day: "numeric" });
    const maxTemp = f.tempMax != null ? Math.round(f.tempMax) : "--";
    const minTemp = f.tempMin != null ? Math.round(f.tempMin) : "--";
    const rainProb = f.precipitationProbability != null ? `${f.precipitationProbability}%` : "0%";
    const precipSum = f.precipitationSum != null && f.precipitationSum > 0 ? `${f.precipitationSum.toFixed(1)}mm` : "";

    return `
      <div class="col text-center">
        <div class="p-3 bg-white rounded-3 border h-100 ${isAlert ? 'border-warning shadow-sm' : ''}">
          <span class="small text-muted d-block fw-semibold">${escapeHtml(dayName)}, ${escapeHtml(formattedDate)}</span>
          <div class="my-2 fs-2 ${isAlert ? 'text-warning' : 'text-primary'}">
            <i class="bi ${escapeHtml(f.icon || 'bi-cloud-sun')}"></i>
          </div>
          <div class="fw-bold text-dark">${maxTemp}° <span class="text-muted fw-normal">/ ${minTemp}°</span></div>
          <small class="text-muted d-block mt-1 text-truncate" title="${escapeHtml(f.condition)}">${escapeHtml(f.condition)}</small>
          <div class="d-flex justify-content-center gap-1 mt-2 flex-wrap">
            <span class="badge ${isAlert ? 'bg-warning text-dark' : 'bg-light text-secondary border'}">
              Rain: ${rainProb}
            </span>
            ${precipSum ? `<span class="badge bg-light text-primary border">${precipSum}</span>` : ''}
          </div>
        </div>
      </div>
    `;
  }).join("");

  // Hourly Carousel
  if (hourlyContainer && forecastData.hourly && forecastData.hourly.length > 0) {
    const next24 = forecastData.hourly.slice(0, 24);
    hourlyContainer.innerHTML = next24.map(h => {
      const timeStr = h.time ? h.time.split("T")[1] || h.time : "";
      const tempStr = h.temperature != null ? `${Math.round(h.temperature)}°` : "--";
      const probStr = h.precipitationProbability != null ? `${h.precipitationProbability}%` : "";

      return `
        <div class="p-2 bg-light rounded-3 border text-center" style="min-width: 75px; flex-shrink: 0;">
          <small class="text-muted d-block" style="font-size:0.75rem">${escapeHtml(timeStr)}</small>
          <div class="fs-4 my-1 text-primary"><i class="bi ${escapeHtml(h.icon || 'bi-cloud')}"></i></div>
          <div class="fw-bold small text-dark">${tempStr}</div>
          ${probStr ? `<small class="text-muted d-block" style="font-size:0.7rem"><i class="bi bi-droplet"></i> ${probStr}</small>` : ''}
        </div>
      `;
    }).join("");
  }
}

function renderAdvice(advice) {
  if (!advice) return;

  // Severe Weather Banner
  const severeBanner = document.getElementById("wt-severe-warning");
  const severeTitle = document.getElementById("wt-severe-title");
  const severeDesc = document.getElementById("wt-severe-desc");

  if (severeBanner) {
    if (advice.severeWarning) {
      severeBanner.classList.remove("d-none");
      if (severeTitle) severeTitle.textContent = `Severe Weather Advisory (${advice.primaryCondition || "Hazardous"})`;
      if (severeDesc) severeDesc.textContent = `Adverse conditions forecast for ${escapeHtml(advice.targetDate || "travel date")}. Outdoor activities are heavily impacted and discouraged.`;
    } else {
      severeBanner.classList.add("d-none");
    }
  }

  // Impact Score
  const scoreVal = document.getElementById("wt-score-value");
  const scoreBar = document.getElementById("wt-score-bar");
  const scoreBadge = document.getElementById("wt-score-badge");
  const scoreSummary = document.getElementById("wt-score-summary");

  const score = advice.impactScore != null ? Math.max(0, Math.min(100, advice.impactScore)) : 80;
  if (scoreVal) scoreVal.textContent = score;
  if (scoreBar) {
    scoreBar.style.width = `${score}%`;
    scoreBar.setAttribute("aria-valuenow", score);
    scoreBar.className = score >= 75 ? "progress-bar bg-success" : score >= 50 ? "progress-bar bg-warning" : "progress-bar bg-danger";
  }
  if (scoreBadge) {
    const rating = advice.impactRating || (score >= 75 ? "Low Impact" : score >= 50 ? "Moderate" : "High Impact");
    scoreBadge.className = score >= 75 ? "badge bg-success" : score >= 50 ? "badge bg-warning text-dark" : "badge bg-danger";
    scoreBadge.textContent = rating;
  }
  if (scoreSummary) {
    scoreSummary.textContent = advice.severeWarning
      ? "Severe meteorological hazards expected. Outdoor travel strongly restricted."
      : score >= 75
        ? "Ideal meteorological conditions for outdoor sightseeing and scheduled travel."
        : score >= 50
          ? "Moderate weather impact. Pack umbrellas and plan outdoor stops in optimal hours."
          : "Significant disruption risk. Re-routing to sheltered indoor attractions recommended.";
  }

  // Recommendations List
  const recsContainer = document.getElementById("wt-recommendations-list");
  if (recsContainer && advice.recommendations) {
    recsContainer.innerHTML = advice.recommendations.map(r => {
      const isCritical = r.severity === "CRITICAL" || r.severity === "WARNING";
      const badgeClass = isCritical ? "badge bg-danger" : "badge bg-primary-subtle text-primary";

      return `
        <div class="col-12 col-md-6">
          <div class="p-2 rounded border bg-light h-100 d-flex align-items-start gap-2">
            <i class="bi ${escapeHtml(r.icon || 'bi-info-circle')} fs-5 ${isCritical ? 'text-danger' : 'text-primary'}"></i>
            <div>
              <div class="d-flex align-items-center gap-2">
                <strong class="small text-dark">${escapeHtml(r.title)}</strong>
                <span class="${badgeClass}" style="font-size:0.65rem">${escapeHtml(r.category || 'Tip')}</span>
              </div>
              <p class="small text-secondary m-0 mt-1" style="font-size:0.8rem">${escapeHtml(r.description)}</p>
            </div>
          </div>
        </div>
      `;
    }).join("");
  }

  // Activity Suitability Grid (6 Core Domains)
  const actContainer = document.getElementById("wt-activities-grid");
  if (actContainer && advice.activities) {
    actContainer.innerHTML = advice.activities.map(a => {
      let badgeHtml;
      if (a.suitability === "SUITABLE") {
        badgeHtml = `<span class="badge bg-success-subtle text-success border border-success w-100"><i class="bi bi-check-circle"></i> Suitable</span>`;
      } else if (a.suitability === "SUITABLE_WITH_PRECAUTIONS") {
        badgeHtml = `<span class="badge bg-warning-subtle text-warning-emphasis border border-warning w-100"><i class="bi bi-exclamation-triangle"></i> Caution</span>`;
      } else {
        badgeHtml = `<span class="badge bg-danger-subtle text-danger border border-danger w-100"><i class="bi bi-x-circle"></i> Avoid</span>`;
      }

      return `
        <div class="col">
          <div class="p-2 bg-white rounded-3 border h-100 d-flex flex-column text-center">
            <div class="fs-4 text-primary my-1"><i class="bi ${escapeHtml(a.icon || 'bi-activity')}"></i></div>
            <span class="small fw-bold text-dark mb-1 text-truncate" title="${escapeHtml(a.activity)}">${escapeHtml(a.activity)}</span>
            <div class="mt-auto mb-1">${badgeHtml}</div>
            <small class="text-muted" style="font-size:0.7rem; line-height:1.2;">${escapeHtml(a.reason || '')}</small>
          </div>
        </div>
      `;
    }).join("");
  }
}

function renderAlternativeSection() {
  const isAltAccepted = window.TravelMateStore && window.TravelMateStore.isWeatherAltAccepted();
  const statusBadge = document.getElementById("alt-dash-status-badge");
  const actionBtn = document.getElementById("alt-dash-action-btn");
  if (!statusBadge || !actionBtn) return;

  if (isAltAccepted) {
    statusBadge.className = "badge bg-success py-2 px-3 fs-6";
    statusBadge.innerHTML = '<i class="bi bi-check-circle-fill"></i> Replacement Applied in Workspace';
    actionBtn.className = "tm-btn tm-btn-outline";
    actionBtn.innerHTML = '<i class="bi bi-arrow-counterclockwise"></i> Revert to Outdoor Activity';
  } else {
    statusBadge.className = "badge bg-warning text-dark py-2 px-3 fs-6";
    statusBadge.innerHTML = '<i class="bi bi-exclamation-triangle-fill"></i> Advisory Pending Acceptance';
    actionBtn.className = "tm-btn tm-btn-accent";
    actionBtn.innerHTML = '<i class="bi bi-check-lg"></i> Accept Smart Alternative';
  }
}

// ------------------------------------------------------------------ INTERACTION HANDLERS

function handleDestinationDropdownChange(selectEl) {
  const val = selectEl.value;
  if (!val) return;

  if (val === "active-trip") {
    initWeatherLocationFromActiveTrip(true);
    fetchLiveWeatherData();
    return;
  }

  const parts = val.split(",");
  if (parts.length >= 3) {
    const lat = parseFloat(parts[0]);
    const lng = parseFloat(parts[1]);
    const name = parts.slice(2).join(",").trim();

    if (isValidCoord(lat, lng)) {
      weatherLocation.name = name;
      weatherLocation.lat = lat;
      weatherLocation.lng = lng;
      syncInputsWithLocation();
      fetchLiveWeatherData();
    }
  }
}

async function handleGeocodeSearch() {
  const input = document.getElementById("wt-place-input");
  const query = input ? input.value.trim() : "";
  const suggestionsBox = document.getElementById("wt-search-suggestions");
  if (!query) return;

  if (suggestionsBox) {
    suggestionsBox.classList.remove("d-none");
    suggestionsBox.innerHTML = `<div class="p-2 text-muted small"><span class="spinner-border spinner-border-sm text-primary"></span> Searching OpenStreetMap...</div>`;
  }

  try {
    const res = await window.TravelMateStore.searchPlaces(query);
    const places = (res && res.places) || [];

    if (places.length === 0) {
      if (suggestionsBox) {
        suggestionsBox.innerHTML = `<div class="p-2 text-muted small">No locations found for "${escapeHtml(query)}"</div>`;
      }
      return;
    }

    if (suggestionsBox) {
      suggestionsBox.innerHTML = places.map((p, i) => `
        <button type="button" class="list-group-item list-group-item-action text-start p-2 border-bottom" onclick="selectPlaceSuggestion(${i})">
          <div class="fw-bold small text-dark"><i class="bi bi-geo-alt text-danger"></i> ${escapeHtml(p.name)}</div>
          <small class="text-muted d-block text-truncate" style="font-size:0.75rem">${escapeHtml(p.address || p.category)}</small>
        </button>
      `).join("");
      window._wtLastPlaces = places;
    }
  } catch (err) {
    if (suggestionsBox) {
      suggestionsBox.innerHTML = `<div class="p-2 text-danger small">Geocode search failed: ${escapeHtml(err.message)}</div>`;
    }
  }
}

function selectPlaceSuggestion(index) {
  const places = window._wtLastPlaces;
  if (!places || !places[index]) return;
  const p = places[index];
  const lat = Number(p.lat);
  const lng = Number(p.lng);

  if (isValidCoord(lat, lng)) {
    weatherLocation.name = p.name;
    weatherLocation.lat = lat;
    weatherLocation.lng = lng;
    syncInputsWithLocation();

    const box = document.getElementById("wt-search-suggestions");
    if (box) box.classList.add("d-none");

    const input = document.getElementById("wt-place-input");
    if (input) input.value = p.name;

    fetchLiveWeatherData();
  }
}

function handleWeatherSearchSubmit(event) {
  event.preventDefault();
  fetchLiveWeatherData();
}

function toggleManualCoords() {
  const row = document.getElementById("wt-manual-coords-row");
  if (row) row.classList.toggle("d-none");
}

function handleManualCoordsApply() {
  const latInput = document.getElementById("wt-lat-input");
  const lngInput = document.getElementById("wt-lng-input");
  const dateInput = document.getElementById("wt-date-input");

  const lat = parseFloat(latInput ? latInput.value : "");
  const lng = parseFloat(lngInput ? lngInput.value : "");
  const date = dateInput && dateInput.value ? dateInput.value : null;

  if (!isValidCoord(lat, lng)) {
    showWeatherError("Invalid Coordinates", "Please enter latitude between -90 and 90, and longitude between -180 and 180.");
    return;
  }

  weatherLocation.lat = lat;
  weatherLocation.lng = lng;
  weatherLocation.date = date;
  weatherLocation.name = `Custom (${lat.toFixed(2)}°, ${lng.toFixed(2)}°)`;
  fetchLiveWeatherData();
}

function retryCurrentWeather() {
  hideWeatherAlerts();
  fetchLiveWeatherData();
}

function setWeatherLoading(loading) {
  isWeatherFetching = loading;
  const spinner = document.getElementById("wt-state-loading");
  const btn = document.getElementById("wt-btn-refresh");
  if (spinner) {
    if (loading) spinner.classList.remove("d-none");
    else spinner.classList.add("d-none");
  }
  if (btn) {
    btn.disabled = loading;
    btn.innerHTML = loading
      ? '<span class="spinner-border spinner-border-sm me-1"></span> Fetching...'
      : '<i class="bi bi-arrow-clockwise"></i> Fetch Weather';
  }
}

function showWeatherError(title, msg) {
  const errEl = document.getElementById("wt-state-error");
  const errTitle = document.getElementById("wt-error-title");
  const errMsg = document.getElementById("wt-error-message");

  if (errEl) errEl.classList.remove("d-none");
  if (errTitle) errTitle.textContent = title;
  if (errMsg) errMsg.textContent = msg;
}

function hideWeatherAlerts() {
  const errEl = document.getElementById("wt-state-error");
  const staleEl = document.getElementById("wt-state-stale");
  const severeEl = document.getElementById("wt-severe-warning");

  if (errEl) errEl.classList.add("d-none");
  if (staleEl) staleEl.classList.add("d-none");
  if (severeEl) severeEl.classList.add("d-none");
}

function fallbackToStaticSimulation() {
  if (typeof TRAVELMATE_DATA !== "undefined" && TRAVELMATE_DATA.weather) {
    const w = TRAVELMATE_DATA.weather;
    const tempEl = document.getElementById("wt-current-temp");
    const condEl = document.getElementById("wt-current-cond");
    const humidityEl = document.getElementById("wt-humidity");
    const windEl = document.getElementById("wt-wind");

    if (tempEl) tempEl.textContent = w.currentTemp || "27°C";
    if (condEl) condEl.textContent = w.currentCondition || "Partly Cloudy";
    if (humidityEl) humidityEl.textContent = w.humidity || "62%";
    if (windEl) windEl.textContent = w.wind || "14 km/h";

    const retrievedBadge = document.getElementById("wt-retrieved-badge");
    if (retrievedBadge) {
      retrievedBadge.className = "badge bg-warning-subtle text-warning-emphasis border small";
      retrievedBadge.textContent = "Offline Simulation";
    }
  }
}

function handleToggleWeatherAltDashboard() {
  if (!window.TravelMateStore) return;
  const isAccepted = window.TravelMateStore.isWeatherAltAccepted();
  window.TravelMateStore.applyWeatherAlternative(!isAccepted);
  renderAlternativeSection();

  if (!isAccepted) {
    if (typeof showToast === "function") showToast("Applied Indoor Rail Museum replacement to Day 2!", "success", "bi-check-circle-fill");
  } else {
    if (typeof showToast === "function") showToast("Reverted Day 2 schedule to Brindavan Gardens", "primary", "bi-arrow-counterclockwise");
  }
}

// Global expose
window.handleDestinationDropdownChange = handleDestinationDropdownChange;
window.handleGeocodeSearch = handleGeocodeSearch;
window.selectPlaceSuggestion = selectPlaceSuggestion;
window.handleWeatherSearchSubmit = handleWeatherSearchSubmit;
window.toggleManualCoords = toggleManualCoords;
window.handleManualCoordsApply = handleManualCoordsApply;
window.retryCurrentWeather = retryCurrentWeather;
window.handleToggleWeatherAltDashboard = handleToggleWeatherAltDashboard;
