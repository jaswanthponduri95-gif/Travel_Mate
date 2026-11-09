/**
 * TRAVELMATE — TRANSPORT COMPARISON LOGIC (PAGE 3)
 * Handles multimodal search filtering (Train, Bus, Flight, Cab),
 * sorting by price/duration/recommended, and transparent booking demo modal.
 */

let currentTransportFilter = "all";
let currentSortBy = "recommended";
let transportLoadVersion = 0;

document.addEventListener("DOMContentLoaded", () => {
  renderTransportHeader();
  const transportContainer = document.getElementById("transport-cards-container");
  if (transportContainer) transportContainer.innerHTML = '<div class="p-4 text-center text-muted"><span class="spinner-border spinner-border-sm me-2"></span>Loading transport options for your trip…</div>';
  loadTransportFromApi();
  document.addEventListener("tm:synced", () => {
    renderTransportHeader();
    loadTransportFromApi();
  });

  // Mode filter pills
  document.querySelectorAll(".tm-transport-filter-btn").forEach(btn => {
    btn.addEventListener("click", () => {
      document.querySelectorAll(".tm-transport-filter-btn").forEach(b => b.classList.remove("active"));
      btn.classList.add("active");
      currentTransportFilter = btn.dataset.mode;
      renderTransportCards();
    });
  });

  // Sorting control
  const sortSelect = document.getElementById("transport-sort-select");
  if (sortSelect) {
    sortSelect.addEventListener("change", (e) => {
      currentSortBy = e.target.value;
      renderTransportCards();
    });
  }
});

function renderTransportHeader() {
  const trip = window.TravelMateStore.getActiveTrip();
  if (!trip) return;

  const headerOrigin = document.getElementById("tr-header-origin");
  const headerDest = document.getElementById("tr-header-dest");
  const headerDate = document.getElementById("tr-header-date");
  const headerTravelers = document.getElementById("tr-header-travelers");

  if (headerOrigin) headerOrigin.textContent = trip.origin || "Origin not set";
  if (headerDest) headerDest.textContent = trip.destination || "Destination not set";
  if (headerDate) headerDate.textContent = trip.startDate ? new Date(trip.startDate).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' }) : "Date not set";
  if (headerTravelers) headerTravelers.textContent = `${trip.travelers} Travelers`;
}

function renderTransportCards() {
  const container = document.getElementById("transport-cards-container");
  if (!container) return;
  const trip = window.TravelMateStore?.getActiveTrip?.() || {};
  const itemsSource = Array.isArray(TRAVELMATE_DATA.transports) ? TRAVELMATE_DATA.transports : [];
  let items = itemsSource.map(item => ({ ...item,
    origin: trip.origin || "Origin not set",
    destination: trip.destination || "Destination not set"
  }));

  // Filter by Mode
  if (currentTransportFilter !== "all") {
    items = items.filter(i => i.mode === currentTransportFilter);
  }

  // Sort
  if (currentSortBy === "cheapest") {
    items.sort((a, b) => a.price - b.price);
  } else if (currentSortBy === "fastest") {
    // Parse duration string e.g. "1h 45m" into minutes
    items.sort((a, b) => parseDurationToMinutes(a.duration) - parseDurationToMinutes(b.duration));
  }

  const countEl = document.getElementById("transport-results-count");
  if (countEl) countEl.textContent = `${items.length} options found`;

  if (items.length === 0) {
    container.innerHTML = `
      <div class="p-5 text-center bg-white rounded-3 border">
        <i class="bi bi-slash-circle text-muted fs-1 mb-2"></i>
        <h5 class="fw-bold">No transport options match this filter</h5>
        <p class="text-muted small">No transport providers are available in the catalog for this active trip yet. The app will not substitute sample cities.</p>
      </div>
    `;
    return;
  }

  container.innerHTML = items.map(tr => `
    <div class="tm-card p-4 mb-3 tm-card-hover-elevate">
      <div class="row align-items-center g-3">
        <!-- Provider Info -->
        <div class="col-md-3">
          <div class="d-flex align-items-center gap-2 mb-1">
            <span class="rounded-2 p-2 bg-light text-primary fs-5"><i class="bi ${tr.icon}"></i></span>
            <div>
              <span class="badge bg-secondary-subtle text-dark small">${tr.modeName}</span>
              <h6 class="fw-bold text-dark m-0 mt-1">${tr.provider}</h6>
            </div>
          </div>
          <small class="text-muted">${tr.number}</small>
        </div>

        <!-- Schedule & Duration -->
        <div class="col-md-5">
          <div class="d-flex align-items-center justify-content-between text-center">
            <div class="text-start">
              <h5 class="fw-bold m-0 text-dark">${tr.depTime}</h5>
              <small class="text-muted text-truncate d-block" style="max-width:130px;">${tr.origin}</small>
            </div>

            <div class="px-2 flex-grow-1">
              <small class="text-muted d-block fw-semibold">${tr.duration}</small>
              <div class="position-relative my-1">
                <hr class="m-0 border-secondary">
                <i class="bi bi-arrow-right position-absolute top-50 start-50 translate-middle bg-white px-1 text-muted" style="font-size:0.75rem;"></i>
              </div>
              <small class="badge bg-light text-success border border-success-subtle">${tr.priceCategory}</small>
            </div>

            <div class="text-end">
              <h5 class="fw-bold m-0 text-dark">${tr.arrTime}</h5>
              <small class="text-muted text-truncate d-block" style="max-width:130px;">${tr.destination}</small>
            </div>
          </div>
        </div>

        <!-- Price & Action -->
        <div class="col-md-4 text-md-end border-start-md ps-md-4">
          <div class="d-flex flex-md-column justify-content-between align-items-md-end align-items-center">
            <div>
              <span class="small text-muted d-block">ESTIMATED FARE</span>
              <h4 class="fw-bold text-dark m-0">₹${Number(tr.price || 0).toLocaleString()} <span class="fs-6 text-muted fw-normal">/ person</span></h4>
              <small class="text-success fw-semibold"><i class="bi bi-check-circle-fill"></i> ${tr.availability}</small>
            </div>

            <div class="mt-md-3">
              ${renderTransportActions(tr)}
            </div>
          </div>
        </div>
      </div>

      <!-- Features chips -->
      <div class="d-flex flex-wrap gap-2 pt-3 mt-3 border-top">
        ${(tr.features || []).map(f => `<span class="badge bg-light text-secondary border small"><i class="bi bi-dot"></i> ${f}</span>`).join("")}
        <span class="tm-badge tm-badge-demo ms-auto">Simulated Fare</span>
      </div>
    </div>
  `).join("");
}

function parseDurationToMinutes(durationStr) {
  let minutes = 0;
  const hMatch = durationStr.match(/(\d+)h/);
  const mMatch = durationStr.match(/(\d+)m/);
  if (hMatch) minutes += parseInt(hMatch[1]) * 60;
  if (mMatch) minutes += parseInt(mMatch[1]);
  return minutes;
}

// External booking redirects. TravelMate remains the source of trip details.
function tmTripRoute(item) {
  const trip = window.TravelMateStore?.getActiveTrip?.() || {};
  const clean = value => String(value || "").trim();
  const invalid = value => !value || /^(undefined|null|-)$/i.test(value.trim());
  let from = clean(trip.origin);
  let to = clean(trip.destination);
  if (invalid(from)) from = "";
  if (invalid(to)) to = "";
  return { trip, from, to };
}

function tmOpenExternal(url) {
  if (!url) return false;
  // Open only a new tab. Never fall back to changing the TravelMate tab,
  // which caused two apparent redirects when the popup was blocked.
  const opened = window.open(url, "_blank", "noopener,noreferrer");
  if (!opened) {
    alert("Your browser blocked the new tab. Allow pop-ups for TravelMate and click again.");
    return false;
  }
  return true;
}

function tmDate(trip) {
  return String(trip?.startDate || "").slice(0, 10);
}

function tmSlug(value) {
  return String(value || "").toLowerCase().replace(/\([^)]*\)/g, " ").replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "");
}

function tmRedbusBusUrl(from, to, date) {
  const base = `https://www.redbus.in/bus-tickets/${tmSlug(from)}-to-${tmSlug(to)}`;
  const params = new URLSearchParams();
  if (date) params.set("onward", date);
  return base + (params.toString() ? `?${params}` : "");
}

function tmRedrailUrl(from, to, date) {
  const params = new URLSearchParams({ fromCityName: from, toCityName: to, fromCity: from, toCity: to });
  if (date) { params.set("date", date); params.set("journeyDate", date); }
  return `https://www.redbus.in/railways?${params.toString()}`;
}

function tmBookingFlightsUrl(from, to, date) {
  const params = new URLSearchParams({ from, to });
  if (date) params.set("depart", date);
  return `https://flights.booking.com/flights/?${params.toString()}`;
}

function tmUberUrl(from, to) {
  const params = new URLSearchParams();
  params.set("action", "setPickup");
  params.set("pickup[formatted_address]", from);
  params.set("dropoff[formatted_address]", to);
  return `https://m.uber.com/ul/?${params.toString()}`;
}

function tmRapidoUrl(from, to) {
  // Rapido's public web entry does not provide a stable documented route-prefill URL.
  // Keep the route in a Google Maps directions link as a useful companion.
  const params = new URLSearchParams({ api: "1", origin: from, destination: to, travelmode: "driving" });
  return `https://www.google.com/maps/dir/?${params.toString()}`;
}

function tmSelfDriveMapUrl(trip, from, to) {
  const stops = [];
  (trip?.days || []).forEach(day => (day.stops || []).forEach(stop => {
    const lat = Number(stop.lat), lng = Number(stop.lng);
    if (Number.isFinite(lat) && Number.isFinite(lng)) stops.push(`${lat},${lng}`);
  }));
  const selectedHotel = String(trip?.accommodation?.name || "").trim();
  const mapDestination = selectedHotel
    ? `${selectedHotel}, ${to}`
    : to;
  const params = new URLSearchParams({ api: "1", origin: from, destination: mapDestination, travelmode: "driving" });
  if (stops.length) params.set("waypoints", stops.slice(0, 8).join("|"));
  return `https://www.google.com/maps/dir/?${params.toString()}`;
}

function renderTransportActions(tr) {
  if (tr.mode === "train") return `<button type="button" class="tm-btn tm-btn-accent tm-btn-sm" onclick="openTransportPlatform('${tr.id}')"><i class="bi bi-train-front"></i> Search on redRail</button>`;
  if (tr.mode === "bus") return `<button type="button" class="tm-btn tm-btn-accent tm-btn-sm" onclick="openTransportPlatform('${tr.id}')"><i class="bi bi-bus-front"></i> Search on redBus</button>`;
  if (tr.mode === "flight") return `<button type="button" class="tm-btn tm-btn-accent tm-btn-sm" onclick="openTransportPlatform('${tr.id}')"><i class="bi bi-airplane"></i> Search Flights</button>`;
  if (tr.mode === "car") return `<div class="d-flex flex-wrap gap-2 justify-content-md-end">
    <button type="button" class="tm-btn tm-btn-accent tm-btn-sm" onclick="openCabPlatform('${tr.id}','uber')"><i class="bi bi-car-front"></i> Uber</button>
    <button type="button" class="tm-btn tm-btn-outline tm-btn-sm" onclick="openCabPlatform('${tr.id}','rapido')"><i class="bi bi-bicycle"></i> Rapido</button>
    <button type="button" class="tm-btn tm-btn-outline tm-btn-sm" onclick="openSelfDriveMap('${tr.id}')"><i class="bi bi-map"></i> Self-drive Map</button>
  </div>`;
  return `<button type="button" class="tm-btn tm-btn-accent tm-btn-sm" onclick="openTransportPlatform('${tr.id}')">Continue</button>`;
}

function openTransportPlatform(transportId) {
  const item = TRAVELMATE_DATA.transports.find(t => t.id === transportId);
  if (!item) return;
  const { trip, from, to } = tmTripRoute(item);
  if (!from || !to) { alert("Please set both From and To in your active TravelMate trip first."); return; }
  const date = tmDate(trip);
  if (item.mode === "train") tmOpenExternal(tmRedrailUrl(from, to, date));
  else if (item.mode === "bus") tmOpenExternal(tmRedbusBusUrl(from, to, date));
  else if (item.mode === "flight") tmOpenExternal(tmBookingFlightsUrl(from, to, date));
}

function openCabPlatform(transportId, provider) {
  const item = TRAVELMATE_DATA.transports.find(t => t.id === transportId);
  if (!item) return;
  const { from, to } = tmTripRoute(item);
  if (!from || !to) { alert("Please set both From and To in your active TravelMate trip first."); return; }
  if (provider === "uber") tmOpenExternal(tmUberUrl(from, to));
  else tmOpenExternal("https://www.rapido.bike/");
}

function openSelfDriveMap(transportId) {
  const item = TRAVELMATE_DATA.transports.find(t => t.id === transportId);
  if (!item) return;
  const { trip, from, to } = tmTripRoute(item);
  if (!from || !to) { alert("Please set both From and To in your active TravelMate trip first."); return; }
  tmOpenExternal(tmSelfDriveMapUrl(trip, from, to));
}

async function loadTransportFromApi() {
  const requestVersion = ++transportLoadVersion;
  if (!window.TravelMateStore?.getTransports) return;
  try {
    const trip = window.TravelMateStore?.getActiveTrip?.();
    if (!trip?.origin || !trip?.destination) {
      TRAVELMATE_DATA.transports = [];
      renderTransportCards();
      return;
    }
    // Load provider options independent of the bundled sample route, then bind them to this trip.
    const res = await window.TravelMateStore.getTransports({ limit: 50 });
    if (requestVersion !== transportLoadVersion) return;
    const currentTrip = window.TravelMateStore?.getActiveTrip?.();
    if (currentTrip?.id !== trip.id) return;
    const items = res?.items || res;
    TRAVELMATE_DATA.transports = Array.isArray(items) ? items : [];
    renderTransportCards();
  } catch (err) {
    if (requestVersion !== transportLoadVersion) return;
    console.error("Could not load transport options for the active trip:", err);
    TRAVELMATE_DATA.transports = [];
    renderTransportCards();
  }
}

window.openTransportPlatform = openTransportPlatform;
window.openCabPlatform = openCabPlatform;
window.openSelfDriveMap = openSelfDriveMap;
window.loadTransportFromApi = loadTransportFromApi;
