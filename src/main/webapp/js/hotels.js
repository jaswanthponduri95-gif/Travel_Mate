/**
 * TRAVELMATE — HOTELS & ACCOMMODATION LOGIC (PAGE 6)
 * Filtering, sorting, selecting property for active trip, and booking demo.
 */

let activeRatingFilter = 0;
let activeSort = "rating";
let hotelRequestId = 0;
let hotelDestination = "";
let hotelAiRequestId = 0;

document.addEventListener("DOMContentLoaded", () => {
  renderActiveStayBanner();
  renderHotelCards();
  loadHotelsFromApi();
  document.addEventListener("tm:synced", () => {
    renderActiveStayBanner();
    renderHotelCards();
    loadHotelsFromApi();
  });

  // Rating filter
  const ratingSelect = document.getElementById("hotel-rating-filter");
  if (ratingSelect) {
    ratingSelect.addEventListener("change", (e) => {
      activeRatingFilter = parseFloat(e.target.value) || 0;
      renderHotelCards();
    });
  }

  // Sort
  const sortSelect = document.getElementById("hotel-sort-select");
  if (sortSelect) {
    sortSelect.addEventListener("change", (e) => {
      activeSort = e.target.value;
      renderHotelCards();
    });
  }
});

function renderActiveStayBanner() {
  const trip = window.TravelMateStore.getActiveTrip();
  const banner = document.getElementById("active-stay-banner");
  if (!banner) return;
  const stay = trip?.accommodation;
  if (!stay) {
    banner.innerHTML = `<div class="small text-muted"><i class="bi bi-info-circle me-1"></i>No hotel selected for <strong>${escapeHtml(trip?.destination || "your trip")}</strong>. Choose a property below to add it to your itinerary.</div>`;
    return;
  }
  const nights = Math.max(Number(stay.nights) || 1, 1);
  const totalCost = (Number(stay.pricePerNight) || 0) * nights;
  banner.innerHTML = `
    <div class="d-flex flex-wrap justify-content-between align-items-center gap-3">
      <div class="d-flex align-items-center gap-3">
        <div class="p-2 bg-primary text-white rounded-3 fs-4"><i class="bi bi-building-check"></i></div>
        <div>
          <span class="badge bg-success-subtle text-success small mb-1"><i class="bi bi-check-circle-fill"></i> Selected for Current Trip</span>
          <h5 class="fw-bold m-0 text-dark">${escapeHtml(stay.name)}</h5>
          <small class="text-muted">${escapeHtml(trip.destination || "")} &bull; ${nights} Nights &bull; Est. Total: ₹${totalCost.toLocaleString()}</small>
        </div>
      </div>
      <a href="workspace.html" class="tm-btn tm-btn-outline tm-btn-sm"><i class="bi bi-map"></i> View in Itinerary</a>
    </div>
  `;
}

function renderHotelCards() {
  const container = document.getElementById("hotels-grid-container");
  if (!container) return;
  const trip = window.TravelMateStore?.getActiveTrip?.() || {};
  const destination = String(trip.destination || "").split(",")[0].trim();
  const heading = document.getElementById("hotel-destination-heading");
  if (heading) heading.textContent = destination || "your destination";
  if (destination && hotelDestination && hotelDestination.toLowerCase() !== destination.toLowerCase()) {
    container.innerHTML = `<div class="col-12 py-4 text-center text-muted">Loading hotels for ${escapeHtml(destination)}…</div>`;
    return;
  }
  let items = Array.isArray(TRAVELMATE_DATA.hotels) ? [...TRAVELMATE_DATA.hotels] : [];

  // Filter Rating
  if (activeRatingFilter > 0) {
    items = items.filter(h => h.rating >= activeRatingFilter);
  }

  // Sort
  if (activeSort === "price-low") {
    items.sort((a, b) => a.pricePerNight - b.pricePerNight);
  } else if (activeSort === "price-high") {
    items.sort((a, b) => b.pricePerNight - a.pricePerNight);
  } else if (activeSort === "rating") {
    items.sort((a, b) => b.rating - a.rating);
  }

  const currentTrip = window.TravelMateStore?.getActiveTrip?.() || {};
  const currentSelectedName = currentTrip.accommodation?.name || "";

  if (!items.length) {
    container.innerHTML = `<div class="col-12 py-5 text-center bg-white rounded-3 border"><h5 class="fw-bold">No hotel suggestions available for ${escapeHtml(destination || "this destination")}</h5><p class="text-muted small">Check your AI settings or try again later. No hotels from another city are shown.</p></div>`;
    return;
  }

  container.innerHTML = items.map(h => {
    const isSelected = h.name === currentSelectedName;

    return `
      <div class="col-md-6 mb-4">
        <div class="tm-card h-100 d-flex flex-column tm-card-hover-elevate ${isSelected ? 'border-primary shadow' : ''}">
          <div style="height: 200px; overflow: hidden; position: relative;">
            <img src="${h.image}" class="w-100 h-100 object-fit-cover" alt="${h.name}" onerror="this.src='https://images.unsplash.com/photo-1566073771259-6a8506099945?auto=format&fit=crop&w=700&q=80'">
            <span class="tm-badge tm-badge-primary position-absolute top-0 start-0 m-3">${h.type}</span>
            ${h.badge ? `<span class="badge bg-warning text-dark position-absolute top-0 end-0 m-3 fw-bold">${h.badge}</span>` : ''}
          </div>

          <div class="p-3 d-flex flex-column flex-grow-1">
            <div class="d-flex justify-content-between align-items-start mb-1">
              <h5 class="fw-bold m-0 text-dark">${h.name}</h5>
              <div class="badge bg-success d-flex align-items-center gap-1">
                <i class="bi bi-star-fill"></i> ${h.rating == null ? "AI suggestion" : h.rating}
              </div>
            </div>

            <small class="text-muted mb-2">
              <i class="bi bi-geo-alt"></i> ${h.area || h.distanceToCenter || "Location details available on provider site"} &bull; ${h.aiSuggested ? "AI suggestion · verify details" : `(${h.reviewsCount || 0} reviews)`}
            </small>

            <p class="small text-secondary mb-3 flex-grow-1">${h.description || "Check the provider listing for current property details."}</p>

            <div class="d-flex flex-wrap gap-1 mb-3">
              ${(h.amenities || []).slice(0, 3).map(a => `<span class="badge bg-light text-secondary border small">${a}</span>`).join("")}
              ${(h.amenities || []).length > 3 ? `<span class="badge bg-light text-muted border small">+${h.amenities.length - 3} more</span>` : ''}
            </div>

            <div class="d-flex justify-content-between align-items-center border-top pt-3 mt-auto">
              <div>
                <span class="small text-muted d-block" style="font-size:0.75rem">PER NIGHT</span>
                <h4 class="fw-bold text-dark m-0">${h.pricePerNight ? `₹${Number(h.pricePerNight).toLocaleString()}` : "Check price"}</h4>
              </div>

              <div class="d-flex gap-2">
                <button type="button" class="tm-btn ${isSelected ? 'tm-btn-primary' : 'tm-btn-outline'} tm-btn-sm" onclick="selectHotelForTrip('${h.id}')">
                  ${isSelected ? '<i class="bi bi-check2"></i> Selected' : 'Choose Stay'}
                </button>
                <button type="button" class="tm-btn tm-btn-accent tm-btn-sm" onclick="openHotelBookingPlatform('${h.id}')">
                  <i class="bi bi-box-arrow-up-right"></i> Book on Booking.com
                </button>
              </div>
            </div>
          </div>
        </div>
      </div>
    `;
  }).join("");
}

function selectHotelForTrip(hotelId) {
  const h = TRAVELMATE_DATA.hotels.find(item => item.id === hotelId);
  if (!h) return;

  const trip = window.TravelMateStore.getActiveTrip();
  trip.accommodation = {
    name: h.name,
    type: h.type,
    pricePerNight: Number(h.pricePerNight) || 0,
    nights: Math.max((trip.days ? trip.days.length : trip.daysCount) - 1, 1)
  };

  window.TravelMateStore.saveActiveTrip(trip);
  renderActiveStayBanner();
  renderHotelCards();
  showToast(`"${h.name}" selected for your trip itinerary!`, "success", "bi-building-check");
}

function openHotelBookingPlatform(hotelId) {
  const hotel = TRAVELMATE_DATA.hotels.find(item => item.id === hotelId);
  if (!hotel) return;
  const trip = window.TravelMateStore?.getActiveTrip?.() || {};
  const destination = String(trip.destination || hotel.destination || "").trim();
  if (!destination || /^(undefined|null)$/i.test(destination)) {
    alert("Please set a destination in your active TravelMate trip first.");
    return;
  }
  const start = String(trip.startDate || "").slice(0, 10);
  const end = String(trip.endDate || "").slice(0, 10);
  const adults = Math.max(1, Number(trip.travelers) || 2);
  const params = new URLSearchParams({ ss: `${hotel.name}, ${destination}`, group_adults: String(adults), no_rooms: "1", group_children: "0" });
  if (start) params.set("checkin", start);
  if (end) params.set("checkout", end);
  const url = `https://www.booking.com/searchresults.html?${params.toString()}`;
  const opened = window.open(url, "_blank", "noopener,noreferrer");
  if (!opened) {
    alert("Your browser blocked the new tab. Allow pop-ups for TravelMate and click again.");
  }
}

async function loadHotelsFromApi() {
  if (!window.TravelMateStore?.getHotels) return;
  const trip = window.TravelMateStore?.getActiveTrip?.() || {};
  const destination = String(trip.destination || "").split(",")[0].trim();
  const requestId = ++hotelRequestId;
  hotelDestination = destination;
  const container = document.getElementById("hotels-grid-container");
  if (container) container.innerHTML = `<div class="col-12 py-4 text-center text-muted"><span class="spinner-border spinner-border-sm me-2"></span>Finding hotels in ${escapeHtml(destination || "your destination")}…</div>`;
  try {
    const params = { limit: 50 };
    if (destination) params.destination = destination;
    const res = await window.TravelMateStore.getHotels(params);
    if (requestId !== hotelRequestId) return;
    let items = res?.items || res;
    if (!Array.isArray(items) || items.length === 0) {
      const aiId = ++hotelAiRequestId;
      const aiBody = await window.TravelMateStore.request("POST", "/ai/place-recommendations", { destination, category: "hotels" });
      if (requestId !== hotelRequestId || aiId !== hotelAiRequestId) return;
      items = (aiBody.items || []).map((h, i) => ({ id: `ai-hotel-${i}-${Date.now()}`, name: h.name, type: h.type || "Hotel", area: h.area || "Location to verify", description: h.description || "AI-discovered property. Verify details and availability with the booking provider.", amenities: h.amenities || [], image: "https://images.unsplash.com/photo-1566073771259-6a8506099945?auto=format&fit=crop&w=700&q=80", rating: null, pricePerNight: 0, destination, aiSuggested: true }));
    }
    TRAVELMATE_DATA.hotels = Array.isArray(items) ? items : [];
    renderHotelCards();
    renderActiveStayBanner();
  } catch (err) {
    if (requestId !== hotelRequestId) return;
    TRAVELMATE_DATA.hotels = [];
    renderHotelCards();
    console.warn("Could not load destination hotels:", err);
  }
}

window.selectHotelForTrip = selectHotelForTrip;
window.openHotelBookingPlatform = openHotelBookingPlatform;
window.loadHotelsFromApi = loadHotelsFromApi;
