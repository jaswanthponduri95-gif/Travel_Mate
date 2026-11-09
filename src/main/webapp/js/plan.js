/**
 * TRAVELMATE — PLAN A TRIP LOGIC (PAGE 2)
 * Handles multi-section trip configuration, live summary synchronization,
 * interest selection chips, AI generation simulation, and routing to workspace.html.
 */

document.addEventListener("DOMContentLoaded", () => {
  // Pre-fill form from URL parameters if available
  prefillFromQueryParams();

  // Setup interest chip listeners
  setupInterestChips();

  // Setup form inputs change listeners to update live summary card
  setupLiveSummaryListeners();

  // Initial summary update
  updateLiveTripSummary();
});

const BUDGET_TIER_AMOUNTS = { budget: "8000", moderate: "18000", luxury: "35000" };

// Parse query params (e.g. from Hero quick form or Explore page)
function prefillFromQueryParams() {
  const params = new URLSearchParams(window.location.search);
  
  if (params.get("origin")) {
    document.getElementById("plan-origin").value = params.get("origin");
  }
  if (params.get("dest")) {
    document.getElementById("plan-dest").value = params.get("dest");
  }
  if (params.get("date")) {
    document.getElementById("plan-date").value = params.get("date");
  }
  if (params.get("days")) {
    document.getElementById("plan-days").value = params.get("days");
  }
  if (params.get("travelers")) {
    document.getElementById("plan-travelers").value = params.get("travelers");
  }
  if (params.get("budget")) {
    const budgetTier = params.get("budget");
    const budgetRadio = document.querySelector(`input[name="budgetCategory"][value="${CSS.escape(budgetTier)}"]`);
    if (budgetRadio) {
      budgetRadio.checked = true;
      // Keep the amount in step with the tier picked on the home page
      document.getElementById("plan-budget-amount").value = BUDGET_TIER_AMOUNTS[budgetTier];
    }
  }
}

// Interest Chips toggle handler
function setupInterestChips() {
  const chips = document.querySelectorAll(".tm-interest-chip");
  chips.forEach(chip => {
    chip.addEventListener("click", () => {
      chip.classList.toggle("active");
      const checkbox = chip.querySelector('input[type="checkbox"]');
      if (checkbox) checkbox.checked = chip.classList.contains("active");
      updateLiveTripSummary();
    });
  });
}

// Live summary real-time updates
function setupLiveSummaryListeners() {
  const inputs = [
    "plan-origin", "plan-dest", "plan-date", "plan-days", "plan-travelers", 
    "plan-budget-amount", "plan-pace", "plan-accommodation"
  ];

  inputs.forEach(id => {
    const el = document.getElementById(id);
    if (el) {
      el.addEventListener("input", updateLiveTripSummary);
      el.addEventListener("change", updateLiveTripSummary);
    }
  });

  document.querySelectorAll('input[name="budgetCategory"]').forEach(r => {
    r.addEventListener("change", (e) => {
      const amountInput = document.getElementById("plan-budget-amount");
      if (BUDGET_TIER_AMOUNTS[e.target.value]) amountInput.value = BUDGET_TIER_AMOUNTS[e.target.value];
      updateLiveTripSummary();
    });
  });
}

function updateLiveTripSummary() {
  const origin = document.getElementById("plan-origin")?.value || "Bengaluru";
  const dest = document.getElementById("plan-dest")?.value || "Mysuru";
  const date = document.getElementById("plan-date")?.value || "2026-11-14";
  const days = document.getElementById("plan-days")?.value || "3";
  const travelers = document.getElementById("plan-travelers")?.value || "2";
  const budget = document.getElementById("plan-budget-amount")?.value || "18000";
  const pace = document.getElementById("plan-pace")?.value || "Balanced";

  // Selected interests
  const activeChips = Array.from(document.querySelectorAll(".tm-interest-chip.active")).map(c => c.dataset.value);

  // Update Summary elements
  const sumDest = document.getElementById("summary-destination");
  const sumRoute = document.getElementById("summary-route");
  const sumDates = document.getElementById("summary-dates");
  const sumDuration = document.getElementById("summary-duration");
  const sumTravelers = document.getElementById("summary-travelers");
  const sumBudget = document.getElementById("summary-budget");
  const sumPace = document.getElementById("summary-pace");
  const sumInterests = document.getElementById("summary-interests");

  if (sumDest) sumDest.textContent = dest;
  if (sumRoute) sumRoute.textContent = `${origin} ➔ ${dest}`;
  if (sumDates) sumDates.textContent = date ? new Date(date + "T00:00:00").toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' }) : "Flexible";
  if (sumDuration) sumDuration.textContent = `${days} Days`;
  if (sumTravelers) sumTravelers.textContent = `${travelers} ${travelers == 1 ? 'Traveler' : 'Travelers'}`;
  if (sumBudget) sumBudget.textContent = `₹${Number(budget).toLocaleString()} Total (₹${Math.round(Number(budget) / Math.max(Number(travelers), 1)).toLocaleString()}/person)`;
  if (sumPace) sumPace.textContent = pace;

  if (sumInterests) {
    if (activeChips.length === 0) {
      sumInterests.innerHTML = '<span class="text-muted fst-italic">None selected yet</span>';
    } else {
      sumInterests.innerHTML = activeChips.map(i => `<span class="tm-badge tm-badge-primary me-1 mb-1">${escapeHtml(i)}</span>`).join("");
    }
  }
}

// Clear Form Handler
function handleClearPlanForm() {
  if (confirm("Are you sure you want to reset all form fields?")) {
    document.getElementById("trip-planner-form").reset();
    document.querySelectorAll(".tm-interest-chip").forEach(c => c.classList.remove("active"));
    updateLiveTripSummary();
    showToast("Form cleared to defaults", "warning", "bi-arrow-counterclockwise");
  }
}

// Submit the form and request a real AI-generated itinerary.
let isGeneratingItinerary = false;

async function handleGenerateItinerary(event) {
  event.preventDefault();
  if (isGeneratingItinerary) return;

  const form = document.getElementById("trip-planner-form");
  if (!form.checkValidity()) {
    form.reportValidity();
    return;
  }

  isGeneratingItinerary = true;
  const submitButton = form.querySelector('[type="submit"]');
  if (submitButton) submitButton.disabled = true;

  const modalEl = document.getElementById("aiGeneratingModal");
  const bsModal = bootstrap.Modal.getOrCreateInstance(modalEl, { backdrop: "static", keyboard: false });
  bsModal.show();

  const origin = document.getElementById("plan-origin").value.trim();
  const dest = document.getElementById("plan-dest").value.trim();
  const date = document.getElementById("plan-date").value;
  const days = Number(document.getElementById("plan-days").value);
  const travelers = Number(document.getElementById("plan-travelers").value);
  const budget = Number(document.getElementById("plan-budget-amount").value);
  const activeChips = Array.from(document.querySelectorAll(".tm-interest-chip.active")).map(c => c.dataset.value);
  const progressText = document.getElementById("ai-gen-step-text");
  const progressBar = document.getElementById("ai-gen-progress-bar");
  const footnote = document.getElementById("ai-gen-footnote");

  const step = (pct, message) => {
    if (progressBar) progressBar.style.width = pct;
    if (progressText) progressText.textContent = message;
  };
  const params = { origin, dest, date, days, travelers, budget, interests: activeChips };

  try {
    if (footnote) footnote.textContent = "Generating a real itinerary with your configured AI model.";
    const ai = await generateWithAi(params, step, footnote);
    step("96%", "Saving your AI itinerary...");
    window.TravelMateStore.createTrip(buildGeneratedTrip(params, ai));
    await window.TravelMateStore.flush();
    step("100%", "Itinerary saved! Opening your workspace...");
    bsModal.hide();
    window.location.href = "workspace.html";
  } catch (err) {
    console.error("TravelMate AI itinerary generation failed:", err);
    bsModal.hide();
    showToast(`Could not generate or save the AI itinerary: ${err.message || "Unknown error"}`, "danger", "bi-exclamation-triangle", 10000);
  } finally {
    isGeneratingItinerary = false;
    if (submitButton) submitButton.disabled = false;
  }
}

/**
 * Ask the configured model (via POST /api/ai/itinerary) for the itinerary.
 * Rejects when the backend or configured AI provider is unavailable.
 */
async function generateWithAi(params, step, footnote) {
  const store = window.TravelMateStore;
  await store.ready;
  if (!store.isOnline()) {
    throw new Error("TravelMate cannot reach the backend. Check that the server is running and sign in again.");
  }
  // Skip a separate /ai/status round trip: the itinerary endpoint validates AI
  // configuration and returns a clear 503 if it is not ready.
  const progressLabel = `Generating ${params.days} days in ${params.dest}…`;
  if (footnote) footnote.textContent = "One AI generation request is being processed; no separate status probe is needed.";
  step("25%", progressLabel);
  // The request can take a while; advance the indicator without extra API polling.
  let pct = 25;
  const creep = setInterval(() => {
    pct = Math.min(pct + 3, 90);
    step(pct + "%", pct < 60 ? progressLabel : "The AI is writing the day-by-day plan…");
  }, 1500);

  const catalog = findDestination(params.dest);
  try {
    const result = await store.request("POST", "/ai/itinerary", {
      origin: params.origin,
      destination: params.dest,
      startDate: params.date,
      days: params.days,
      travelers: params.travelers,
      budget: params.budget,
      budgetCategory: (document.querySelector('input[name="budgetCategory"]:checked') || {}).value,
      pace: document.getElementById("plan-pace").value,
      accommodation: document.getElementById("plan-accommodation").value,
      interests: params.interests,
      center: catalog ? catalog.coordinates : null
    });
    if (!result || !Array.isArray(result.days) || result.days.length !== params.days
        || result.days.some(day => !Array.isArray(day.stops) || day.stops.length === 0)) {
      throw new Error("The AI provider returned an incomplete itinerary. Try again or choose a more capable model.");
    }
    const elapsed = Number(result.elapsedMs);
    step("95%", `Received a complete plan${Number.isFinite(elapsed) ? ` in ${(elapsed / 1000).toFixed(1)}s` : ""}.`);
    return result;
  } finally {
    clearInterval(creep);
  }
}

/**
 * Build a trip only from a successful AI result; never silently substitute demo stops.
 */
function buildGeneratedTrip({ origin, dest, date, days, travelers, budget, interests }, ai) {
  const catalog = findDestination(dest);
  const demo = TRAVELMATE_DATA.activeTrip;
  const isMysuru = catalog && catalog.id === "mysuru";
  const startDate = date || new Date().toISOString().slice(0, 10);
  const dayDate = (offset) => {
    const d = new Date(startDate + "T00:00:00");
    d.setDate(d.getDate() + offset);
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
  };
  const stamp = Date.now();

  const itinerary = ai ? ai.days.map((d, i) => ({ ...d, dayNumber: i + 1, date: dayDate(i) })) : Array.from({ length: days }, (_, i) => {
    let day;
    if (isMysuru && demo.days[i]) {
      day = JSON.parse(JSON.stringify(demo.days[i]));
      day.stops.forEach(s => { s.id = `${s.id}-${stamp}`; });
    } else {
      const highlight = catalog && catalog.highlights[i];
      day = {
        title: highlight ? `${highlight} & Surroundings` : `Day ${i + 1} Exploration`,
        stops: highlight ? [{
          id: `stop-${stamp}-${i}`,
          name: highlight,
          category: catalog.category,
          categoryIcon: "bi-geo-alt",
          time: "10:00 AM",
          duration: "2.0 hrs",
          lat: catalog.coordinates[0] + (Math.random() - 0.5) * 0.04,
          lng: catalog.coordinates[1] + (Math.random() - 0.5) * 0.04,
          cost: 0,
          image: catalog.image,
          notes: `Suggested highlight in ${catalog.name}. Edit timing and cost as you plan.`
        }] : []
      };
      day.summary = `${day.stops.length} stops • Plan open`;
    }
    day.dayNumber = i + 1;
    day.date = dayDate(i);
    return day;
  });

  const tier = document.querySelector('input[name="budgetCategory"]:checked');
  return {
    id: "trip-" + stamp,
    title: ai && ai.title ? ai.title : `${catalog ? catalog.name : dest} Tailored Journey`,
    origin,
    destination: catalog ? `${catalog.name}, ${catalog.state}` : dest,
    startDate,
    endDate: dayDate(days - 1),
    daysCount: days,
    travelers,
    budgetTotal: budget,
    budgetCategory: tier ? tier.value.charAt(0).toUpperCase() + tier.value.slice(1) : "Moderate",
    interests: interests.length > 0 ? interests : ["Heritage", "Food", "Nature"],
    coverImage: catalog ? catalog.image : demo.coverImage,
    status: "Upcoming",
    accommodation: isMysuru ? JSON.parse(JSON.stringify(demo.accommodation)) : null,
    weatherAltAccepted: false,
    aiPlanned: !!ai, // a model-written itinerary is already an AI plan; template trips get one when opened
    days: itinerary
  };
}

window.handleClearPlanForm = handleClearPlanForm;
window.handleGenerateItinerary = handleGenerateItinerary;
