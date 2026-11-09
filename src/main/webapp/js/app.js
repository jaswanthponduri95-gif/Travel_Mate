/**
 * TRAVELMATE — SHARED APPLICATION UI SCRIPTS (PHASE 1)
 * Handles global navigation, dynamic toast notifications, confirmation dialogs,
 * demo action modals, and user profile state.
 */

document.addEventListener("DOMContentLoaded", () => {
  // Highlight active navigation link
  highlightActiveNavLink();

  // Initialize toast container if not already in DOM
  ensureToastContainer();

  renderNavUser();
  document.addEventListener("tm:synced", (e) => {
    renderNavUser();
    // Tell the user once per browser session when changes cannot reach the database
    if (!e.detail.online && location.protocol !== "file:" && !sessionStorage.getItem("tm_offline_notice")) {
      sessionStorage.setItem("tm_offline_notice", "1");
      showToast("Server/database not reachable — changes are saved in this browser only.", "warning", "bi-cloud-slash-fill", 6000);
    }
  });
});

/** Escape text before inserting it into innerHTML (user-entered names, notes...). */
function escapeHtml(value) {
  return String(value ?? "").replace(/[&<>"']/g, ch => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"
  })[ch]);
}

/**
 * The "AI Settings" navbar entry exists only for signed-in people on the owners' list
 * (the server sets user.aiAccess from ai.allowedEmails). Everyone else never sees it.
 */
function renderAiNavLink() {
  const nav = document.querySelector(".tm-navbar .navbar-nav");
  if (!nav || !window.TravelMateStore) return;
  const user = window.TravelMateStore.getCurrentUser();
  const link = nav.querySelector('a[href="settings.html"]');
  if (user && user.aiAccess) {
    if (link) return;
    nav.insertAdjacentHTML("beforeend",
      '<li class="nav-item"><a class="nav-link tm-nav-link" href="settings.html"><i class="bi bi-cpu"></i> AI Settings</a></li>');
    highlightActiveNavLink();
  } else if (link) {
    link.closest("li").remove();
  }
}

/** Show the signed-in user's name (and a sign-out option) on the navbar Sign In button. */
function renderNavUser() {
  renderAiNavLink();
  const btn = document.getElementById("nav-signin-btn");
  if (!btn || !window.TravelMateStore) return;
  const user = window.TravelMateStore.getCurrentUser();
  if (user) {
    btn.innerHTML = `<i class="bi bi-person-check"></i> ${escapeHtml(user.fullName.split(" ")[0])} · Sign Out`;
    btn.setAttribute("href", "#");
    btn.onclick = async (ev) => {
      ev.preventDefault();
      await window.TravelMateStore.logout();
      showToast("Signed out", "primary", "bi-box-arrow-right");
      renderNavUser();
    };
  } else {
    btn.innerHTML = `<i class="bi bi-person"></i> Sign In`;
    btn.setAttribute("href", "auth.html");
    btn.onclick = null;
  }
}

// Highlight current page in navbar
function highlightActiveNavLink() {
  let currentPath = window.location.pathname.split("/").pop() || "index.html";
  if (!currentPath || currentPath === "" || currentPath === "travelmate") {
    currentPath = "index.html";
  }
  const navLinks = document.querySelectorAll(".tm-nav-link");

  navLinks.forEach(link => {
    const href = link.getAttribute("href");
    if (!href) return;
    const cleanHref = href.split("?")[0].split("#")[0];
    if (cleanHref === currentPath) {
      link.classList.add("active");
      link.setAttribute("aria-current", "page");
    } else {
      link.classList.remove("active");
      link.removeAttribute("aria-current");
    }
  });
}

// Global Toast Container creation
function ensureToastContainer() {
  let container = document.getElementById("tm-toast-container");
  if (!container) {
    container = document.createElement("div");
    container.id = "tm-toast-container";
    container.className = "tm-toast-container";
    document.body.appendChild(container);
  }
}

/**
 * Show a modern floating toast notification
 * @param {string} message - Text to display
 * @param {string} type - 'success' | 'danger' | 'warning' | 'accent' | 'primary'
 * @param {string} icon - Bootstrap icon class (e.g. 'bi-check-circle-fill')
 * @param {number} duration - Milliseconds before auto-dismiss
 */
function showToast(message, type = "primary", icon = "bi-info-circle-fill", duration = 3500) {
  ensureToastContainer();
  const container = document.getElementById("tm-toast-container");

  const toast = document.createElement("div");
  toast.className = `tm-toast tm-toast-${type}`;

  const iconHtml = `<i class="bi ${icon} fs-5" style="color: var(--tm-${type === 'primary' ? 'primary' : type})"></i>`;
  const online = window.TravelMateStore && window.TravelMateStore.isOnline();
  const textHtml = `<div class="d-flex flex-column"><span class="fw-semibold text-dark fs-6">${escapeHtml(message)}</span><small class="text-muted" style="font-size:0.75rem">${online ? "Synced with TravelMate server" : "Offline mode — saved in this browser"}</small></div>`;
  const closeHtml = `<button type="button" class="btn-close ms-auto" style="font-size:0.75rem" aria-label="Close"></button>`;

  toast.innerHTML = `<div class="d-flex align-items-center gap-2">${iconHtml}${textHtml}</div>${closeHtml}`;

  const closeBtn = toast.querySelector(".btn-close");
  closeBtn.addEventListener("click", () => {
    toast.remove();
  });

  container.appendChild(toast);

  setTimeout(() => {
    if (toast.parentElement) {
      toast.style.opacity = "0";
      toast.style.transform = "translateX(100%)";
      toast.style.transition = "all 0.3s ease";
      setTimeout(() => toast.remove(), 300);
    }
  }, duration);
}

/**
 * Standard Demo Booking or External Action Modal
 */
function showDemoNoticeModal(title, detail) {
  const modalHtml = `
    <div class="modal fade" id="demoNoticeModal" tabindex="-1" aria-hidden="true">
      <div class="modal-dialog modal-dialog-centered">
        <div class="modal-content tm-modal-content">
          <div class="modal-header tm-modal-header d-flex justify-content-between align-items-center">
            <h5 class="modal-title fw-bold text-dark d-flex align-items-center gap-2">
              <i class="bi bi-info-circle-fill text-primary"></i> ${title}
            </h5>
            <button type="button" class="btn-close" data-bs-dismiss="modal" aria-label="Close"></button>
          </div>
          <div class="modal-body tm-modal-body">
            <div class="alert alert-warning py-2 px-3 d-flex align-items-center gap-2 mb-3">
              <i class="bi bi-exclamation-triangle-fill fs-5"></i>
              <span class="small fw-semibold">Booking Information</span>
            </div>
            <p class="text-secondary mb-3">${detail}</p>
            <div class="p-3 bg-light rounded-3 border">
              <div class="small text-muted mb-1">Booking details:</div>
              <ul class="small text-secondary ps-3 mb-0">
                <li>Java Servlet endpoints: <code>com.travelmate.servlet.BookingServlet</code></li>
                <li>MySQL booking transaction tables with JDBC persistence</li>
                <li>Live IRCTC, RedBus & Flight partner API integration</li>
              </ul>
            </div>
          </div>
          <div class="modal-footer tm-modal-footer">
            <button type="button" class="tm-btn tm-btn-primary w-100" data-bs-dismiss="modal">
              Continue
            </button>
          </div>
        </div>
      </div>
    </div>
  `;

  // Remove existing modal if any
  const oldModal = document.getElementById("demoNoticeModal");
  if (oldModal) oldModal.remove();

  document.body.insertAdjacentHTML("beforeend", modalHtml);
  const modalEl = document.getElementById("demoNoticeModal");
  const bsModal = new bootstrap.Modal(modalEl);
  bsModal.show();
}

/**
 * Share / Export Modal Demo
 */
function showShareExportModal() {
  const trip = window.TravelMateStore.getActiveTrip();
  const shareUrl = window.location.origin + window.location.pathname.replace("workspace.html", "") + "workspace.html?tripId=" + encodeURIComponent(trip.id);

  const modalHtml = `
    <div class="modal fade" id="shareExportModal" tabindex="-1" aria-hidden="true">
      <div class="modal-dialog modal-dialog-centered">
        <div class="modal-content tm-modal-content">
          <div class="modal-header tm-modal-header d-flex justify-content-between align-items-center">
            <h5 class="modal-title fw-bold text-dark d-flex align-items-center gap-2">
              <i class="bi bi-share-fill text-primary"></i> Share & Export Itinerary
            </h5>
            <button type="button" class="btn-close" data-bs-dismiss="modal" aria-label="Close"></button>
          </div>
          <div class="modal-body tm-modal-body">
            <div class="mb-3">
              <label class="form-label small fw-bold text-muted">SHAREABLE TRIP LINK</label>
              <div class="input-group">
                <input type="text" class="form-control form-control-sm" id="shareTripUrlInput" value="${shareUrl}" readonly>
                <button class="btn btn-outline-primary btn-sm" type="button" onclick="navigator.clipboard.writeText(document.getElementById('shareTripUrlInput').value).then(() => showToast('Trip link copied to clipboard!', 'success', 'bi-clipboard-check-fill'), () => { document.getElementById('shareTripUrlInput').select(); showToast('Press Ctrl+C to copy the selected link', 'warning', 'bi-clipboard'); });">
                  <i class="bi bi-copy"></i> Copy
                </button>
              </div>
            </div>

            <div class="d-grid gap-2 mt-4">
              <button class="tm-btn tm-btn-outline d-flex align-items-center justify-content-center gap-2" onclick="window.print();">
                <i class="bi bi-printer"></i> Print or Save as PDF
              </button>
              <button class="tm-btn tm-btn-outline d-flex align-items-center justify-content-center gap-2" onclick="exportTripToIcs();">
                <i class="bi bi-calendar-event"></i> Export to Google Calendar / iCal
              </button>
            </div>
          </div>
          <div class="modal-footer tm-modal-footer">
            <button type="button" class="tm-btn tm-btn-outline w-100" data-bs-dismiss="modal">Close</button>
          </div>
        </div>
      </div>
    </div>
  `;

  const oldModal = document.getElementById("shareExportModal");
  if (oldModal) oldModal.remove();

  document.body.insertAdjacentHTML("beforeend", modalHtml);
  const modalEl = document.getElementById("shareExportModal");
  const bsModal = new bootstrap.Modal(modalEl);
  bsModal.show();
}

/** Download the active trip's stops as an .ics calendar file. */
function exportTripToIcs() {
  const trip = window.TravelMateStore.getActiveTrip();
  const pad = (n) => String(n).padStart(2, "0");
  const icsText = (v) => String(v ?? "").replace(/[\\;,]/g, m => "\\" + m).replace(/\r?\n/g, "\\n");
  const start = trip.startDate ? new Date(trip.startDate + "T00:00:00") : new Date();
  const lines = ["BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//TravelMate//Itinerary//EN"];

  trip.days.forEach((day, dayIdx) => {
    day.stops.forEach((stop, stopIdx) => {
      const m = /(\d{1,2}):(\d{2})\s*(AM|PM)?/i.exec(stop.time || "") || [null, "9", "00", "AM"];
      let hours = Number(m[1]) % 12;
      if ((m[3] || "").toUpperCase() === "PM") hours += 12;
      if (!m[3] && Number(m[1]) === 12) hours = 12;
      const begin = new Date(start);
      begin.setDate(start.getDate() + dayIdx);
      begin.setHours(hours, Number(m[2]), 0, 0);
      const end = new Date(begin.getTime() + (parseFloat(stop.duration) || 1) * 3600000);
      const fmt = (d) => `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}T${pad(d.getHours())}${pad(d.getMinutes())}00`;
      lines.push("BEGIN:VEVENT", `UID:${trip.id}-${dayIdx}-${stopIdx}@travelmate`, `DTSTAMP:${fmt(new Date())}`,
        `DTSTART:${fmt(begin)}`, `DTEND:${fmt(end)}`, `SUMMARY:${icsText(stop.name)}`,
        `DESCRIPTION:${icsText(stop.notes)}`, `LOCATION:${icsText(trip.destination)}`, "END:VEVENT");
    });
  });
  lines.push("END:VCALENDAR");

  const blob = new Blob([lines.join("\r\n")], { type: "text/calendar" });
  const link = document.createElement("a");
  link.href = URL.createObjectURL(blob);
  link.download = `${(trip.title || "trip").replace(/[^\w-]+/g, "_")}.ics`;
  link.click();
  URL.revokeObjectURL(link.href);
  showToast("Itinerary exported as an iCal calendar (.ics)", "accent", "bi-calendar-check");
}

/** Catalog destination matching a trip's free-text destination ("Mysuru, Karnataka" -> mysuru). */
function findDestination(name) {
  const key = String(name || "").toLowerCase();
  return TRAVELMATE_DATA.destinations.find(d => key.includes(d.name.toLowerCase()) || key.includes(d.id));
}

// Global expose
window.escapeHtml = escapeHtml;
window.exportTripToIcs = exportTripToIcs;
window.findDestination = findDestination;
window.showToast = showToast;
window.showDemoNoticeModal = showDemoNoticeModal;
window.showShareExportModal = showShareExportModal;
