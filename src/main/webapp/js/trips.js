/**
 * TRAVELMATE — MY TRIPS DASHBOARD LOGIC (PAGE 9)
 * Trip card management, filtering by status (Upcoming, Draft, Completed),
 * duplicate trip, delete trip with confirmation, and loading into workspace.
 */

let activeTripTab = "all";

document.addEventListener("DOMContentLoaded", () => {
  renderTripsList();
  document.addEventListener("tm:synced", renderTripsList);

  // Tab filter listeners
  document.querySelectorAll(".trips-tab-btn").forEach(btn => {
    btn.addEventListener("click", () => {
      document.querySelectorAll(".trips-tab-btn").forEach(b => b.classList.remove("active"));
      btn.classList.add("active");
      activeTripTab = btn.dataset.status;
      renderTripsList();
    });
  });
});

function renderTripsList() {
  const container = document.getElementById("trips-cards-container");
  if (!container) return;

  const trips = window.TravelMateStore.getTrips();
  let filtered = trips;

  if (activeTripTab !== "all") {
    filtered = trips.filter(t => (t.status || "").toLowerCase() === activeTripTab.toLowerCase());
  }

  if (filtered.length === 0) {
    container.innerHTML = `
      <div class="col-12 py-5 text-center bg-white rounded-3 border">
        <i class="bi bi-journal-x text-muted fs-1 mb-2"></i>
        <h5 class="fw-bold">No trips found in this category</h5>
        <p class="text-muted small">You don't have any ${activeTripTab === 'all' ? '' : activeTripTab} journeys at the moment.</p>
        <a href="plan.html" class="tm-btn tm-btn-primary tm-btn-sm">
          <i class="bi bi-plus-circle"></i> Plan a New Journey
        </a>
      </div>
    `;
    return;
  }

  container.innerHTML = filtered.map(t => `
    <div class="col-md-6 col-lg-4 mb-4">
      <div class="tm-card h-100 d-flex flex-column tm-card-hover-elevate">
        <div style="height: 180px; overflow: hidden; position: relative;">
          <img src="${escapeHtml(t.image)}" class="w-100 h-100 object-fit-cover" alt="${escapeHtml(t.title)}" onerror="this.src='https://images.unsplash.com/photo-1488646953014-85cb44e25828?auto=format&fit=crop&w=700&q=80'">
          <span class="badge ${t.status === 'Upcoming' ? 'bg-primary' : t.status === 'Draft' ? 'bg-warning text-dark' : 'bg-success'} position-absolute top-0 start-0 m-3">
            ${escapeHtml(t.status)}
          </span>
          <span class="badge bg-dark bg-opacity-75 text-white position-absolute top-0 end-0 m-3">
            ${t.stopsCount || 0} ${t.stopsCount === 1 ? "Stop" : "Stops"}
          </span>
        </div>

        <div class="p-3 d-flex flex-column flex-grow-1">
          <div class="d-flex justify-content-between align-items-start mb-1">
            <h5 class="fw-bold m-0 text-dark">${escapeHtml(t.title)}</h5>
          </div>
          <small class="text-muted mb-2"><i class="bi bi-geo-alt"></i> ${escapeHtml(t.destination)}</small>

          <div class="small text-secondary mb-3">
            <i class="bi bi-calendar-event"></i> ${escapeHtml(t.dates)} &bull; 
            <i class="bi bi-people"></i> ${t.travelers} Travelers
          </div>

          <div class="d-flex justify-content-between align-items-center bg-light p-2 rounded-2 mb-3 small">
            <span class="text-muted">Target Budget:</span>
            <span class="fw-bold text-success">₹${Number(t.budget || t.budgetTotal || 0).toLocaleString()}</span>
          </div>

          <div class="d-flex justify-content-between align-items-center border-top pt-2 mt-auto">
            <small class="text-muted" style="font-size:0.75rem">${escapeHtml(t.lastUpdated)}</small>
            
            <div class="d-flex gap-1">
              <button type="button" class="btn btn-sm btn-outline-secondary" onclick="handleDuplicateTrip('${t.id}')" title="Duplicate Trip">
                <i class="bi bi-copy"></i>
              </button>
              <button type="button" class="btn btn-sm btn-outline-danger" onclick="handleDeleteTrip('${t.id}')" title="Delete Trip">
                <i class="bi bi-trash3"></i>
              </button>
              <button type="button" class="tm-btn tm-btn-primary tm-btn-sm" onclick="handleOpenTrip('${t.id}')">
                Open Workspace &rarr;
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  `).join("");
}

async function handleOpenTrip(tripId) {
  const trips = window.TravelMateStore.getTrips();
  const found = trips.find(t => t.id === tripId);
  if (!found) return;
  showToast(`Opening "${found.title}" in workspace...`, "primary", "bi-map");
  try {
    // Make this trip the active one (loads its itinerary and budget from the server)
    await window.TravelMateStore.setActiveTrip(tripId);
    window.location.href = "workspace.html";
  } catch (err) {
    showToast(`Could not open trip: ${err.message}`, "danger", "bi-exclamation-octagon");
  }
}

async function handleResetDemoData() {
  if (!confirm("Reset all trips and budgets to the original demo data?")) return;
  try {
    await window.TravelMateStore.resetDemoData();
    location.reload();
  } catch (err) {
    showToast(`Reset failed: ${err.message}`, "danger", "bi-exclamation-octagon");
  }
}

function handleDuplicateTrip(tripId) {
  window.TravelMateStore.duplicateTrip(tripId);
  renderTripsList();
  showToast("Trip duplicated successfully!", "success", "bi-copy");
}

function handleDeleteTrip(tripId) {
  if (confirm("Are you sure you want to delete this trip? This action cannot be undone.")) {
    window.TravelMateStore.deleteTrip(tripId);
    renderTripsList();
    showToast("Trip deleted from dashboard", "warning", "bi-trash3");
  }
}

window.handleOpenTrip = handleOpenTrip;
window.handleResetDemoData = handleResetDemoData;
window.handleDuplicateTrip = handleDuplicateTrip;
window.handleDeleteTrip = handleDeleteTrip;
