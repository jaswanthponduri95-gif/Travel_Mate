/**
 * TRAVELMATE — FOOD & RESTAURANTS LOGIC (PAGE 7)
 * Proximity-based dining discovery, cuisine filters, and itinerary insertion.
 */

let activeMealFilter = "all";
let activeDietaryFilter = "all";
let restaurantRequestId = 0;
let restaurantDestination = "";
let restaurantAiRequestId = 0;

document.addEventListener("DOMContentLoaded", () => {
  renderRestaurantCards();
  loadRestaurantsFromApi();
  document.addEventListener("tm:synced", () => {
    loadRestaurantsFromApi();
  });

  // Meal tab buttons
  document.querySelectorAll(".food-meal-tab").forEach(tab => {
    tab.addEventListener("click", () => {
      document.querySelectorAll(".food-meal-tab").forEach(t => t.classList.remove("active"));
      tab.classList.add("active");
      activeMealFilter = tab.dataset.meal;
      renderRestaurantCards();
    });
  });

  // Dietary filter
  const dietSelect = document.getElementById("food-diet-filter");
  if (dietSelect) {
    dietSelect.addEventListener("change", (e) => {
      activeDietaryFilter = e.target.value;
      renderRestaurantCards();
    });
  }
});

function renderRestaurantCards() {
  const container = document.getElementById("restaurants-grid-container");
  if (!container) return;
  const trip = window.TravelMateStore?.getActiveTrip?.() || {};
  const destination = String(trip.destination || "").split(",")[0].trim();
  const heading = document.getElementById("food-destination-heading");
  if (heading) heading.textContent = destination || "your destination";
  if (destination && restaurantDestination && restaurantDestination.toLowerCase() !== destination.toLowerCase()) {
    container.innerHTML = `<div class="col-12 py-4 text-center text-muted">Loading restaurants for ${escapeHtml(destination)}…</div>`;
    return;
  }
  const sourceItems = Array.isArray(TRAVELMATE_DATA.restaurants) ? TRAVELMATE_DATA.restaurants : [];
  let items = sourceItems.filter(r => {
    const matchesMeal = activeMealFilter === "all" || String(r.mealType || "").toLowerCase().includes(activeMealFilter.toLowerCase());
    const matchesDiet = activeDietaryFilter === "all" || String(r.dietary || "").toLowerCase().includes(activeDietaryFilter.toLowerCase());
    return matchesMeal && matchesDiet;
  });

  if (items.length === 0) {
    container.innerHTML = `
      <div class="col-12 py-5 text-center bg-white rounded-3 border">
        <i class="bi bi-cup-hot text-muted fs-1 mb-2"></i>
        <h5 class="fw-bold">No restaurants found for ${escapeHtml(destination || "this destination")}</h5>
        <p class="text-muted small">Try another filter or check again after selecting a destination with available restaurant listings.</p>
      </div>
    `;
    return;
  }

  container.innerHTML = items.map(r => `
    <div class="col-md-6 col-lg-6 mb-4">
      <div class="tm-card h-100 d-flex flex-column tm-card-hover-elevate">
        <div style="height: 180px; overflow: hidden; position: relative;">
          <img src="${r.image}" class="w-100 h-100 object-fit-cover" alt="${r.name}" onerror="this.src='https://images.unsplash.com/photo-1555396273-367ea4eb4db5?auto=format&fit=crop&w=600&q=80'">
          <span class="tm-badge tm-badge-primary position-absolute top-0 start-0 m-3">${r.mealType}</span>
          <span class="badge bg-light text-dark position-absolute top-0 end-0 m-3 shadow-sm">
            <i class="bi bi-geo-alt-fill text-danger"></i> Near Stop
          </span>
        </div>

        <div class="p-3 d-flex flex-column flex-grow-1">
          <div class="d-flex justify-content-between align-items-start mb-1">
            <h5 class="fw-bold m-0 text-dark">${r.name}</h5>
            <span class="badge bg-success"><i class="bi bi-star-fill"></i> ${r.rating == null ? "AI suggestion" : r.rating}</span>
          </div>

          <p class="small text-muted mb-1">${r.cuisine}</p>
          <p class="small text-secondary mb-2"><i class="bi bi-pin-map text-primary"></i> ${r.area || r.distanceToStop || "Location to verify"}</p>

          <div class="p-2 bg-light rounded-2 small text-dark mb-3">
            <strong>Signature Dish:</strong> ${r.specialty}
          </div>

          <div class="d-flex justify-content-between align-items-center border-top pt-2 mt-auto">
            <div>
              <span class="small text-muted d-block" style="font-size:0.75rem">APPROX COST</span>
              <span class="fw-bold text-dark">${r.pricePerPerson ? `₹${r.pricePerPerson} <small class="text-muted fw-normal">/ person</small>` : "Check menu for current prices"}</span>
            </div>

            <div class="d-flex gap-2">
              <button type="button" class="tm-btn tm-btn-outline tm-btn-sm" onclick="openRestaurantOnMap('${r.id}')">
                <i class="bi bi-compass"></i> View on Map
              </button>
              <button type="button" class="tm-btn tm-btn-accent tm-btn-sm" onclick="openAddToItineraryModal('${r.id}')">
                <i class="bi bi-plus-circle"></i> Add to Itinerary
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  `).join("");
}


function openRestaurantOnMap(restaurantId) {
  const restaurant = (TRAVELMATE_DATA.restaurants || []).find(item => item.id === restaurantId);
  if (!restaurant) return;
  const trip = window.TravelMateStore?.getActiveTrip?.() || {};
  const destination = String(trip.destination || restaurant.destination || "").trim();
  const hasCoordinates = restaurant.lat != null && restaurant.lng != null
    && Number.isFinite(Number(restaurant.lat)) && Number.isFinite(Number(restaurant.lng));
  const query = hasCoordinates
    ? `${restaurant.lat},${restaurant.lng}`
    : `${restaurant.name}, ${destination}`.trim();
  const url = `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(query)}`;
  const opened = window.open(url, "_blank", "noopener,noreferrer");
  if (!opened) {
    alert("Your browser blocked the map tab. Allow pop-ups for TravelMate and click again.");
  }
}

// Open modal to select which day to insert meal stop into
function openAddToItineraryModal(restaurantId) {
  const r = TRAVELMATE_DATA.restaurants.find(item => item.id === restaurantId);
  if (!r) return;

  const modalEl = document.getElementById("addRestaurantModal");
  if (!modalEl) return;

  document.getElementById("food-modal-rest-name").textContent = r.name;
  document.getElementById("food-modal-rest-id").value = r.id;

  // Offer the active trip's real days (it may have more or fewer than 3)
  const trip = window.TravelMateStore.getActiveTrip();
  document.getElementById("food-modal-day-select").innerHTML = trip.days
    .map(d => `<option value="${d.dayNumber}">Day ${d.dayNumber}: ${escapeHtml(d.title || "Exploration")}</option>`).join("");

  const bsModal = new bootstrap.Modal(modalEl);
  bsModal.show();
}

function handleConfirmAddFoodToDay(event) {
  event.preventDefault();
  const restId = document.getElementById("food-modal-rest-id").value;
  const dayNumber = Number(document.getElementById("food-modal-day-select").value);
  const mealType = document.getElementById("food-modal-meal-type").value;

  const r = TRAVELMATE_DATA.restaurants.find(item => item.id === restId);
  if (!r) return;
  const travelers = Math.max(Number(window.TravelMateStore.getActiveTrip().travelers) || 1, 1);

  const newStop = {
    id: "stop-food-" + Date.now(),
    name: `${mealType}: ${r.name}`,
    category: "Food",
    categoryIcon: "bi-cup-hot",
    time: mealType === "Breakfast" ? "08:30 AM" : mealType === "Lunch" ? "01:00 PM" : "08:00 PM",
    duration: "1.0 hr",
    cost: (Number(r.pricePerPerson) || 0) * travelers,
    lat: Number.isFinite(Number(r.lat)) ? Number(r.lat) : null,
    lng: Number.isFinite(Number(r.lng)) ? Number(r.lng) : null,
    image: r.image,
    notes: `Signature: ${r.specialty}. Estimated spend to confirm with restaurant for ${travelers} ${travelers === 1 ? "person" : "people"}.`
  };

  if (!window.TravelMateStore.addStopToDay(dayNumber, newStop)) {
    showToast(`Day ${dayNumber} does not exist in this trip`, "danger", "bi-exclamation-octagon");
    return;
  }
  bootstrap.Modal.getInstance(document.getElementById("addRestaurantModal")).hide();
  showToast(`Added ${r.name} to Day ${dayNumber} Itinerary!`, "success", "bi-check-circle-fill");
}

async function loadRestaurantsFromApi() {
  if (!window.TravelMateStore?.getRestaurants) return;
  const trip = window.TravelMateStore?.getActiveTrip?.() || {};
  const destination = String(trip.destination || "").split(",")[0].trim();
  const requestId = ++restaurantRequestId;
  restaurantDestination = destination;
  const container = document.getElementById("restaurants-grid-container");
  if (container) container.innerHTML = `<div class="col-12 py-4 text-center text-muted"><span class="spinner-border spinner-border-sm me-2"></span>Finding restaurants in ${escapeHtml(destination || "your destination")}…</div>`;
  try {
    const params = { limit: 50 };
    if (destination) params.destination = destination;
    const res = await window.TravelMateStore.getRestaurants(params);
    if (requestId !== restaurantRequestId) return;
    let items = res?.items || res;
    if (!Array.isArray(items) || items.length === 0) {
      const aiId = ++restaurantAiRequestId;
      const aiBody = await window.TravelMateStore.request("POST", "/ai/place-recommendations", { destination, category: "restaurants" });
      if (requestId !== restaurantRequestId || aiId !== restaurantAiRequestId) return;
      items = (aiBody.items || []).map((r, i) => ({ id: `ai-restaurant-${i}-${Date.now()}`, name: r.name, cuisine: r.cuisine || "Cuisine to verify", specialty: r.specialty || "Not verified", area: r.area || "Location to verify", mealType: r.mealType || "All Meals", dietary: "General", image: "https://images.unsplash.com/photo-1555396273-367ea4eb4db5?auto=format&fit=crop&w=600&q=80", rating: null, pricePerPerson: null, destination, aiSuggested: true }));
    }
    TRAVELMATE_DATA.restaurants = Array.isArray(items) ? items : [];
    renderRestaurantCards();
  } catch (err) {
    if (requestId !== restaurantRequestId) return;
    TRAVELMATE_DATA.restaurants = [];
    renderRestaurantCards();
    console.warn("Could not load destination restaurants:", err);
  }
}

window.openRestaurantOnMap = openRestaurantOnMap;
window.openAddToItineraryModal = openAddToItineraryModal;
window.handleConfirmAddFoodToDay = handleConfirmAddFoodToDay;
window.loadRestaurantsFromApi = loadRestaurantsFromApi;
