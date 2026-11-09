/**
 * TRAVELMATE — EXPLORE DESTINATIONS LOGIC (PAGE 5)
 * Search, multi-category & region filtering, destination detail drawer/modal.
 */

let activeCategory = "all";
let activeRegion = "all";
let searchQuery = "";
let catalogDestinations = null;

document.addEventListener("DOMContentLoaded", () => {
  renderDestinations();
  loadDestinationsFromApi();
  document.addEventListener("tm:synced", loadDestinationsFromApi);

  // Search input
  const searchInput = document.getElementById("explore-search-input");
  if (searchInput) {
    searchInput.addEventListener("input", (e) => {
      searchQuery = e.target.value.toLowerCase().trim();
      renderDestinations();
    });
  }

  // Category filter chips
  document.querySelectorAll(".explore-cat-chip").forEach(chip => {
    chip.addEventListener("click", () => {
      document.querySelectorAll(".explore-cat-chip").forEach(c => c.classList.remove("active"));
      chip.classList.add("active");
      activeCategory = chip.dataset.category;
      renderDestinations();
    });
  });

  // Region filter selector
  const regionSelect = document.getElementById("explore-region-select");
  if (regionSelect) {
    regionSelect.addEventListener("change", (e) => {
      activeRegion = e.target.value;
      renderDestinations();
    });
  }

  // Check if URL query param has ?dest=mysuru
  const params = new URLSearchParams(window.location.search);
  const destParam = params.get("dest");
  if (destParam) {
    setTimeout(() => {
      openDestinationDetailModal(destParam);
    }, 300);
  }
});

function renderDestinations() {
  const container = document.getElementById("explore-dest-grid");
  if (!container || !TRAVELMATE_DATA.destinations) return;

  let items = TRAVELMATE_DATA.destinations.filter(d => {
    const matchesSearch = d.name.toLowerCase().includes(searchQuery) || 
                          d.state.toLowerCase().includes(searchQuery) ||
                          d.description.toLowerCase().includes(searchQuery);

    const matchesCategory = activeCategory === "all" || d.category.toLowerCase() === activeCategory.toLowerCase();
    const matchesRegion = activeRegion === "all" || d.region.toLowerCase() === activeRegion.toLowerCase();

    return matchesSearch && matchesCategory && matchesRegion;
  });

  const countBadge = document.getElementById("explore-count-badge");
  if (countBadge) countBadge.textContent = `${items.length} Destinations`;

  if (items.length === 0) {
    container.innerHTML = `
      <div class="col-12 py-5 text-center bg-white rounded-3 border">
        <i class="bi bi-geo-alt text-muted fs-1 mb-2"></i>
        <h5 class="fw-bold">No destinations found matching your filters</h5>
        <p class="text-muted small">Try searching for Mysuru, Ooty, Jaipur, Goa, Munnar or Hyderabad.</p>
        <button class="tm-btn tm-btn-outline tm-btn-sm" onclick="resetExploreFilters()">Reset All Filters</button>
      </div>
    `;
    return;
  }

  container.innerHTML = items.map(d => `
    <div class="col-md-6 col-lg-4">
      <div class="tm-card tm-dest-card tm-card-hover-elevate">
        <div class="tm-dest-image-wrap">
          <img src="${d.image}" class="tm-dest-image" alt="${d.name}" loading="lazy" onerror="this.src='https://images.unsplash.com/photo-1488646953014-85cb44e25828?auto=format&fit=crop&w=800&q=80'">
          <span class="tm-badge tm-badge-primary tm-dest-badge-float">${d.category}</span>
          <button class="tm-dest-bookmark-float" onclick="toggleSaveDestination('${d.id}'); event.stopPropagation();" title="Save Destination">
            <i class="bi bi-bookmark"></i>
          </button>
        </div>

        <div class="p-3 d-flex flex-column flex-grow-1">
          <div class="d-flex justify-content-between align-items-start mb-1">
            <h5 class="fw-bold m-0 text-dark">${d.name}</h5>
            <small class="text-muted fw-semibold">${d.state}</small>
          </div>
          <p class="small text-secondary mb-2 fst-italic" style="font-size:0.8rem">${d.tagline}</p>
          <p class="small text-muted mb-3 flex-grow-1" style="line-height:1.4">${d.description.substring(0, 105)}...</p>

          <div class="bg-light p-2 rounded-2 mb-3 small">
            <div class="d-flex justify-content-between mb-1">
              <span class="text-muted">Best Season:</span>
              <span class="fw-semibold text-dark text-truncate ms-2" style="max-width:160px;">${d.bestSeason}</span>
            </div>
            <div class="d-flex justify-content-between">
              <span class="text-muted">Est. Budget:</span>
              <span class="fw-bold text-success">${d.budgetRange}</span>
            </div>
          </div>

          <div class="d-flex justify-content-between align-items-center border-top pt-2 mt-auto">
            <button type="button" class="tm-btn tm-btn-outline tm-btn-sm" onclick="openDestinationDetailModal('${d.id}')">
              <i class="bi bi-info-circle"></i> Details
            </button>
            <a href="plan.html?dest=${encodeURIComponent(d.name)}" class="tm-btn tm-btn-primary tm-btn-sm">
              <i class="bi bi-magic"></i> Plan Trip
            </a>
          </div>
        </div>
      </div>
    </div>
  `).join("");
}

// Destination Detail Modal
function openDestinationDetailModal(destId) {
  const d = TRAVELMATE_DATA.destinations.find(item => item.id === destId || item.name.toLowerCase() === destId.toLowerCase());
  if (!d) return;

  const modalEl = document.getElementById("destinationDetailModal");
  if (!modalEl) return;

  document.getElementById("detail-modal-title").textContent = `${d.name}, ${d.state}`;
  document.getElementById("detail-modal-image").src = d.image;
  document.getElementById("detail-modal-category").textContent = d.category;
  document.getElementById("detail-modal-desc").textContent = d.description;
  document.getElementById("detail-modal-days").textContent = `${d.suggestedDays} Days Recommended`;
  document.getElementById("detail-modal-season").textContent = d.bestSeason;
  document.getElementById("detail-modal-budget").textContent = d.budgetRange;

  // Highlights
  const highlightsEl = document.getElementById("detail-modal-highlights");
  if (highlightsEl) {
    highlightsEl.innerHTML = d.highlights.map(h => `<li class="small text-secondary mb-1"><i class="bi bi-check-circle-fill text-success me-1"></i> ${h}</li>`).join("");
  }

  // Cuisine
  const cuisineEl = document.getElementById("detail-modal-cuisine");
  if (cuisineEl) {
    cuisineEl.innerHTML = d.cuisine.map(c => `<span class="badge bg-light text-dark border me-1 mb-1"><i class="bi bi-cup-hot text-danger me-1"></i> ${c}</span>`).join("");
  }

  // Plan CTA link
  const planBtn = document.getElementById("detail-modal-plan-btn");
  if (planBtn) {
    planBtn.href = `plan.html?dest=${encodeURIComponent(d.name)}&days=${d.suggestedDays}`;
  }

  const bsModal = new bootstrap.Modal(modalEl);
  bsModal.show();
}

function resetExploreFilters() {
  activeCategory = "all";
  activeRegion = "all";
  searchQuery = "";
  document.getElementById("explore-search-input").value = "";
  document.getElementById("explore-region-select").value = "all";
  document.querySelectorAll(".explore-cat-chip").forEach(c => {
    if (c.dataset.category === "all") c.classList.add("active");
    else c.classList.remove("active");
  });
  renderDestinations();
}

function toggleSaveDestination(destId) {
  showToast(`Destination saved to your saved bucket!`, "success", "bi-bookmark-check-fill");
}

async function loadDestinationsFromApi() {
  if (!window.TravelMateStore?.getDestinations) return;
  try {
    const res = await window.TravelMateStore.getDestinations({ limit: 50 });
    const items = res?.items || res;
    if (Array.isArray(items) && items.length > 0) {
      TRAVELMATE_DATA.destinations = items;
      renderDestinations();
    }
  } catch (err) {
    console.warn("Could not load destinations from live API, fallback to offline data:", err);
  }
}

window.openDestinationDetailModal = openDestinationDetailModal;
window.resetExploreFilters = resetExploreFilters;
window.toggleSaveDestination = toggleSaveDestination;
window.loadDestinationsFromApi = loadDestinationsFromApi;
