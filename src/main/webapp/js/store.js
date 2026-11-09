/**
 * TRAVELMATE — STATE STORE (PHASE 2: JAVA SERVLET + MYSQL BACKEND)
 *
 * Pages read state synchronously from a localStorage cache. On every page load the
 * cache is refreshed from the backend (GET /api/trips, /api/trips/{id}, /api/budget/{id})
 * and a "tm:synced" event is fired so pages can re-render. Every change is written to
 * the cache immediately and then sent to the backend in order.
 *
 * When the backend is unreachable (server stopped, page opened from disk) the app keeps
 * working in offline mode on the localStorage cache alone.
 */

const TravelMateStore = (() => {
  const KEY_TRIP_DETAILS = "tm_trip_details";   // { tripId: full trip }
  const KEY_TRIPS_LIST = "tm_trips_list";       // [trip summary]
  const KEY_BUDGETS = "tm_budgets";             // { tripId: budget }
  const KEY_ACTIVE_ID = "tm_active_trip_id";
  const KEY_USER = "tm_user";

  // Served by the Java app -> same-origin "api/". Opened from disk or VS Code Live Server
  // -> the default local Tomcat/Jetty URL. Set window.TM_API_BASE before this script to override.
  const servedElsewhere = location.protocol === "file:" || ["5500", "5501"].includes(location.port);
  const contextPrefix = window.location.pathname.startsWith("/travelmate") ? "/travelmate/api" : "/api";
  const API_BASE = window.TM_API_BASE || (servedElsewhere ? "http://localhost:8080/travelmate/api" : contextPrefix);

  let online = false;
  let writeQueue = Promise.resolve();

  const clone = (obj) => JSON.parse(JSON.stringify(obj));

  const read = (key, fallback) => {
    try {
      const raw = localStorage.getItem(key);
      return raw ? JSON.parse(raw) : fallback;
    } catch (e) {
      return fallback;
    }
  };
  const write = (key, value) => localStorage.setItem(key, JSON.stringify(value));

  // ------------------------------------------------------------------ HTTP

  async function api(method, path, body, options = {}) {
    const keepalive = typeof options === "boolean" ? options : (options && options.keepalive) || false;
    const signal = options && options.signal ? options.signal : undefined;
    const cleanBase = API_BASE.replace(/\/+$/, "");
    const cleanPath = path.replace(/^\/+/, "");
    const url = cleanBase + "/" + cleanPath;

    let res;
    try {
      res = await fetch(url, {
        method,
        headers: body !== undefined ? { "Content-Type": "application/json", "Accept": "application/json" } : { "Accept": "application/json" },
        body: body !== undefined ? JSON.stringify(body) : undefined,
        credentials: "include",
        keepalive,
        signal
      });
    } catch (netErr) {
      if (netErr.name === "AbortError") {
        throw netErr;
      }
      const err = new Error("Network error: Server is unreachable. Check connection.");
      err.status = 0;
      err.cause = netErr;
      throw err;
    }

    const text = await res.text();
    let data = null;
    if (text) {
      try {
        data = JSON.parse(text);
      } catch (parseErr) {
        data = { error: res.status >= 400 ? `Server returned HTTP ${res.status}` : text };
      }
    }

    if (!res.ok) {
      const errMsg = (data && (data.error || data.message)) || `Server error (HTTP ${res.status})`;
      const err = new Error(errMsg);
      err.status = res.status;
      err.data = data;
      throw err;
    }
    return data;
  }

  /** Queue a write to the backend; writes run one at a time, in order. */
  function push(method, path, body, onResult) {
    if (!online) return writeQueue;
    // keepalive lets the request finish even if the page navigates away right after
    const keepalive = body === undefined || JSON.stringify(body).length < 60000;
    writeQueue = writeQueue
      .then(() => api(method, path, body, keepalive))
      .then(data => { if (onResult) onResult(data); })
      .catch(err => {
        console.error(`[TravelMate] ${method} ${path} failed:`, err);
        if (window.showToast) {
          window.showToast(`Could not save to server: ${err.message}`, "danger", "bi-cloud-slash-fill", 6000);
        }
      });
    return writeQueue;
  }

  // ------------------------------------------------------------------ local cache helpers

  const details = () => read(KEY_TRIP_DETAILS, {});
  const budgets = () => read(KEY_BUDGETS, {});

  const fmtShort = (iso) => new Date(iso + "T00:00:00").toLocaleDateString("en-US", { month: "short", day: "numeric" });

  /** Summary card fields for "My Trips", mirroring what the server derives. */
  function summarize(trip) {
    const days = trip.days ? trip.days.length : (trip.daysCount || 1);
    const stops = trip.days ? trip.days.reduce((n, d) => n + (d.stops ? d.stops.length : 0), 0) : (trip.stopsCount || 0);
    let dates = "Dates flexible";
    if (trip.startDate) {
      const year = (trip.endDate || trip.startDate).slice(0, 4);
      dates = trip.endDate ? `${fmtShort(trip.startDate)} – ${fmtShort(trip.endDate)}, ${year}` : `${fmtShort(trip.startDate)}, ${year}`;
    }
    return {
      id: trip.id,
      title: trip.title,
      destination: trip.destination,
      origin: trip.origin,
      startDate: trip.startDate,
      endDate: trip.endDate,
      daysCount: days,
      travelers: trip.travelers,
      budget: trip.budgetTotal,
      budgetTotal: trip.budgetTotal,
      image: trip.coverImage || trip.image,
      coverImage: trip.coverImage || trip.image,
      status: trip.status || "Draft",
      dates,
      duration: `${days} ${days === 1 ? "Day" : "Days"}${days > 1 ? `, ${days - 1} ${days === 2 ? "Night" : "Nights"}` : ""}`,
      stopsCount: stops,
      lastUpdated: trip.lastUpdated || "Just now"
    };
  }

  function upsertSummary(summary) {
    const list = read(KEY_TRIPS_LIST, []);
    const idx = list.findIndex(t => t.id === summary.id);
    if (idx === -1) list.unshift(summary);
    else list[idx] = { ...list[idx], ...summary };
    write(KEY_TRIPS_LIST, list);
  }

  function cacheTrip(trip) {
    const all = details();
    all[trip.id] = trip;
    write(KEY_TRIP_DETAILS, all);
  }

  /** Full trip for a list entry that has no cached detail yet (offline mode). */
  function skeletonFromSummary(s) {
    const count = s.daysCount || parseInt(s.duration, 10) || 1;
    return {
      ...clone(s),
      budgetTotal: s.budgetTotal || s.budget || 0,
      daysCount: count,
      interests: s.interests || [],
      days: Array.from({ length: count }, (_, i) => ({
        dayNumber: i + 1, title: `Day ${i + 1} Exploration`, summary: "0 stops • Plan open", stops: []
      }))
    };
  }

  function recalcDaySummary(day) {
    const hrs = day.stops.reduce((h, s) => h + (parseFloat(s.duration) || 1.5), 0);
    day.summary = `${day.stops.length} stops • ~${hrs.toFixed(1)} hrs planned`;
  }

  function recalcBudget(b) {
    b.estimatedTotal = b.items.reduce((acc, i) => acc + Number(i.amount || 0), 0);
    b.actualSpend = b.items.filter(i => i.status === "Actual").reduce((acc, i) => acc + Number(i.amount || 0), 0);
    return b;
  }

  function seedLocalDemo() {
    const trip = clone(TRAVELMATE_DATA.activeTrip);
    const list = clone(TRAVELMATE_DATA.tripsList);
    write(KEY_TRIP_DETAILS, { [trip.id]: trip });
    write(KEY_TRIPS_LIST, list);
    const b = clone(TRAVELMATE_DATA.budget);
    delete b.categories;
    write(KEY_BUDGETS, { [trip.id]: recalcBudget(b) });
    localStorage.setItem(KEY_ACTIVE_ID, trip.id);
  }

  // First visit (or data from the old Phase 1 store format): load the demo data locally
  if (!localStorage.getItem(KEY_TRIPS_LIST) || !localStorage.getItem(KEY_TRIP_DETAILS)) seedLocalDemo();

  // ------------------------------------------------------------------ initial sync

  async function loadTripFromServer(tripId) {
    const [trip, budget] = await Promise.all([
      api("GET", `/trips/${encodeURIComponent(tripId)}`),
      api("GET", `/budget/${encodeURIComponent(tripId)}`)
    ]);
    cacheTrip(trip);
    const all = budgets();
    all[tripId] = budget;
    write(KEY_BUDGETS, all);
    return trip;
  }

  const ready = (async () => {
    try {
      await api("GET", "/health");
      online = true;
      const list = await api("GET", "/trips");
      write(KEY_TRIPS_LIST, list);
      let activeId = localStorage.getItem(KEY_ACTIVE_ID);
      if (!list.some(t => t.id === activeId)) activeId = list.length ? list[0].id : null;
      if (activeId) {
        localStorage.setItem(KEY_ACTIVE_ID, activeId);
        await loadTripFromServer(activeId);
      }
      const user = await api("GET", "/auth/me");
      if (user) write(KEY_USER, user);
      else localStorage.removeItem(KEY_USER);
    } catch (err) {
      online = false;
      console.warn(`[TravelMate] Backend not reachable at ${API_BASE} — running in offline mode.`, err.message);
    }
    document.dispatchEvent(new CustomEvent("tm:synced", { detail: { online } }));
    return online;
  })();

  // ------------------------------------------------------------------ public API

  const store = {
    ready,
    isOnline: () => online,
    getApiBase: () => API_BASE,
    /** Raw JSON call to the backend, e.g. request("GET", "/ai/status"). Throws with the server's error message. */
    request: (method, path, body, options = {}) => api(method, path, body, options),
    /** Resolves once every queued write has reached the server. */
    flush: () => writeQueue,

    // Live Weather APIs (Phase 6 & 7)
    getCurrentWeather: (lat, lng, options = {}) => {
      const q = `?lat=${encodeURIComponent(lat)}&lng=${encodeURIComponent(lng)}`;
      return api("GET", "/weather/current" + q, undefined, options);
    },

    getWeatherForecast: (lat, lng, days = 5, options = {}) => {
      const q = `?lat=${encodeURIComponent(lat)}&lng=${encodeURIComponent(lng)}&days=${encodeURIComponent(days)}`;
      return api("GET", "/weather/forecast" + q, undefined, options);
    },

    getWeatherAdvice: (lat, lng, date = null, options = {}) => {
      let q = `?lat=${encodeURIComponent(lat)}&lng=${encodeURIComponent(lng)}`;
      if (date) q += `&date=${encodeURIComponent(date)}`;
      return api("GET", "/weather/advice" + q, undefined, options);
    },

    // Map & Geocoding Search (Phase 5)
    searchPlaces: (query, lat = null, lng = null, options = {}) => {
      let q = `?q=${encodeURIComponent(query)}`;
      if (lat != null && lng != null) q += `&lat=${encodeURIComponent(lat)}&lng=${encodeURIComponent(lng)}`;
      return api("GET", "/places/search" + q, undefined, options);
    },

    // Catalog APIs (Phase 3)
    getDestinations: (params = {}, options = {}) => {
      const qs = new URLSearchParams(params).toString();
      return api("GET", "/destinations" + (qs ? "?" + qs : ""), undefined, options);
    },

    getHotels: (params = {}, options = {}) => {
      const qs = new URLSearchParams(params).toString();
      return api("GET", "/hotels" + (qs ? "?" + qs : ""), undefined, options);
    },

    getRestaurants: (params = {}, options = {}) => {
      const qs = new URLSearchParams(params).toString();
      return api("GET", "/restaurants" + (qs ? "?" + qs : ""), undefined, options);
    },

    getTransports: (params = {}, options = {}) => {
      const qs = new URLSearchParams(params).toString();
      return api("GET", "/transport/search" + (qs ? "?" + qs : ""), undefined, options);
    },

    // Reset to demo defaults (server data too, when online)
    resetDemoData: async () => {
      if (online) {
        await writeQueue;
        await api("POST", "/demo/reset");
      }
      seedLocalDemo();
    },

    getActiveTripId: () => localStorage.getItem(KEY_ACTIVE_ID),

    getActiveTrip: () => {
      const all = details();
      const id = localStorage.getItem(KEY_ACTIVE_ID);
      if (id && all[id]) return clone(all[id]);
      const summary = read(KEY_TRIPS_LIST, []).find(t => t.id === id);
      if (summary) return skeletonFromSummary(summary);
      const first = Object.values(all)[0];
      return first ? clone(first) : clone(TRAVELMATE_DATA.activeTrip);
    },

    /** Make another trip the active one (loads its itinerary from the server when online). */
    setActiveTrip: async (tripId) => {
      localStorage.setItem(KEY_ACTIVE_ID, tripId);
      if (online) {
        await writeQueue;
        await loadTripFromServer(tripId);
      } else if (!details()[tripId]) {
        const summary = read(KEY_TRIPS_LIST, []).find(t => t.id === tripId);
        if (summary) cacheTrip(skeletonFromSummary(summary));
      }
    },

    saveActiveTrip: (trip) => {
      trip.daysCount = trip.days ? trip.days.length : trip.daysCount;
      cacheTrip(trip);
      upsertSummary(summarize(trip));
      return push("PUT", `/trips/${encodeURIComponent(trip.id)}`, trip, saved => {
        // Keep server-derived summary fields (lastUpdated, stopsCount, ...) in the list
        const { days, ...summary } = saved;
        upsertSummary(summary);
      });
    },

    /** Create a brand-new trip, make it active and save it. */
    createTrip: (trip) => {
      trip.id = trip.id || "trip-" + Date.now();
      trip.status = trip.status || "Upcoming";
      localStorage.setItem(KEY_ACTIVE_ID, trip.id);
      const all = budgets();
      all[trip.id] = { tripId: trip.id, totalLimit: trip.budgetTotal, estimatedTotal: 0, actualSpend: 0, items: [] };
      write(KEY_BUDGETS, all);
      return store.saveActiveTrip(trip);
    },

    addStopToDay: (dayNumber, stop) => {
      const trip = store.getActiveTrip();
      const targetDay = trip.days.find(d => d.dayNumber === Number(dayNumber));
      if (!targetDay) return false;
      stop.id = stop.id || "stop-" + Date.now();
      targetDay.stops.push(stop);
      recalcDaySummary(targetDay);
      store.saveActiveTrip(trip);
      return trip;
    },

    updateStop: (dayNumber, updatedStop) => {
      const trip = store.getActiveTrip();
      const targetDay = trip.days.find(d => d.dayNumber === Number(dayNumber));
      if (!targetDay) return false;
      const stopIdx = targetDay.stops.findIndex(s => s.id === updatedStop.id);
      if (stopIdx === -1) return false;
      targetDay.stops[stopIdx] = { ...targetDay.stops[stopIdx], ...updatedStop };
      recalcDaySummary(targetDay);
      store.saveActiveTrip(trip);
      return trip;
    },

    deleteStop: (dayNumber, stopId) => {
      const trip = store.getActiveTrip();
      const targetDay = trip.days.find(d => d.dayNumber === Number(dayNumber));
      if (!targetDay) return false;
      targetDay.stops = targetDay.stops.filter(s => s.id !== stopId);
      recalcDaySummary(targetDay);
      store.saveActiveTrip(trip);
      return trip;
    },

    /**
     * Route optimisation: greedy nearest-neighbour ordering starting from the day's
     * first stop, keeping the original time slots in order.
     */
    optimizeDayRoute: (dayNumber) => {
      const trip = store.getActiveTrip();
      const targetDay = trip.days.find(d => d.dayNumber === Number(dayNumber));
      if (!targetDay || targetDay.stops.length <= 2) return trip;

      const located = targetDay.stops.filter(s => s.lat && s.lng);
      const unlocated = targetDay.stops.filter(s => !(s.lat && s.lng));
      const times = targetDay.stops.map(s => s.time);
      const dist = (a, b) => Math.hypot(a.lat - b.lat, (a.lng - b.lng) * Math.cos(a.lat * Math.PI / 180));

      const ordered = located.length ? [located.shift()] : [];
      while (located.length) {
        const last = ordered[ordered.length - 1];
        let best = 0;
        located.forEach((s, i) => { if (dist(last, s) < dist(last, located[best])) best = i; });
        ordered.push(located.splice(best, 1)[0]);
      }
      targetDay.stops = ordered.concat(unlocated).map((s, i) => ({ ...s, time: times[i] }));
      store.saveActiveTrip(trip);
      return trip;
    },

    // Weather alternative (stored per trip)
    isWeatherAltAccepted: () => !!store.getActiveTrip().weatherAltAccepted,

    applyWeatherAlternative: (accept = true) => {
      const trip = store.getActiveTrip();
      const day2 = trip.days.find(d => d.dayNumber === 2);
      if (!day2) return trip;

      const alt = TRAVELMATE_DATA.weather.alternativeProposal;
      const stopIdx = day2.stops.findIndex(s => s.id === "stop-203" || s.id === "stop-alt-weather");
      trip.weatherAltAccepted = accept;

      if (stopIdx !== -1) {
        day2.stops[stopIdx] = accept ? {
          id: "stop-alt-weather",
          name: alt.recommendedStop.name,
          category: "Indoor Heritage",
          categoryIcon: "bi-building-check",
          time: "03:30 PM",
          duration: "2.2 hrs",
          lat: 12.3168,
          lng: 76.6445,
          cost: alt.recommendedStop.cost,
          image: alt.recommendedStop.image,
          notes: "★ [WEATHER REPLACEMENT APPLIED] " + alt.recommendedStop.notes,
          isWeatherReplaced: true
        } : {
          id: "stop-203",
          name: "Brindavan Gardens (Outdoor Musical Fountains)",
          category: "Gardens",
          categoryIcon: "bi-water",
          time: "03:30 PM",
          duration: "2.5 hrs",
          lat: 12.4243,
          lng: 76.5732,
          cost: 100,
          image: "https://images.unsplash.com/photo-1585320806297-9794b3e4eeae?auto=format&fit=crop&w=600&q=80",
          notes: "Terraced botanical gardens beside KRS Dam. [WEATHER ALERT: Outdoor rain forecast simulated for this time!]",
          isWeatherImpacted: true
        };
      }
      store.saveActiveTrip(trip);
      return trip;
    },

    // Trips list
    getTrips: () => read(KEY_TRIPS_LIST, []),

    duplicateTrip: (tripId) => {
      const trips = store.getTrips();
      const source = trips.find(t => t.id === tripId);
      if (!source) return false;

      const newId = "trip-" + Date.now();
      const copy = { ...clone(source), id: newId, title: source.title + " (Copy)", status: "Draft", lastUpdated: "Just now" };
      trips.unshift(copy);
      write(KEY_TRIPS_LIST, trips);

      const full = details()[tripId];
      if (full) cacheTrip({ ...clone(full), id: newId, title: copy.title, status: "Draft" });
      const allBudgets = budgets();
      if (allBudgets[tripId]) {
        allBudgets[newId] = { ...clone(allBudgets[tripId]), tripId: newId };
        write(KEY_BUDGETS, allBudgets);
      }
      push("POST", `/trips/${encodeURIComponent(tripId)}/duplicate`, { id: newId }, saved => {
        const { days, ...summary } = saved;
        upsertSummary(summary);
      });
      return trips;
    },

    deleteTrip: (tripId) => {
      const trips = store.getTrips().filter(t => t.id !== tripId);
      write(KEY_TRIPS_LIST, trips);
      const all = details();
      delete all[tripId];
      write(KEY_TRIP_DETAILS, all);
      const allBudgets = budgets();
      delete allBudgets[tripId];
      write(KEY_BUDGETS, allBudgets);
      if (localStorage.getItem(KEY_ACTIVE_ID) === tripId) {
        if (trips.length) localStorage.setItem(KEY_ACTIVE_ID, trips[0].id);
        else localStorage.removeItem(KEY_ACTIVE_ID);
      }
      push("DELETE", `/trips/${encodeURIComponent(tripId)}`);
      return trips;
    },

    // Budget (for the active trip)
    getBudget: () => {
      const trip = store.getActiveTrip();
      const b = budgets()[trip.id];
      if (b) return clone(b);
      return { tripId: trip.id, totalLimit: trip.budgetTotal || 0, estimatedTotal: 0, actualSpend: 0, items: [] };
    },

    /** Category breakdown computed from the items, using the category list in data.js. */
    getBudgetCategories: () => {
      const b = store.getBudget();
      return TRAVELMATE_DATA.budget.categories.map(cat => {
        const items = b.items.filter(i => i.category === cat.name);
        return {
          ...cat,
          estimated: items.reduce((acc, i) => acc + Number(i.amount || 0), 0),
          actual: items.filter(i => i.status === "Actual").reduce((acc, i) => acc + Number(i.amount || 0), 0)
        };
      });
    },

    setBudgetLimit: (limit) => {
      const b = store.getBudget();
      b.totalLimit = Number(limit);
      const all = budgets();
      all[b.tripId] = b;
      write(KEY_BUDGETS, all);
      push("PUT", `/budget/${encodeURIComponent(b.tripId)}`, { totalLimit: b.totalLimit });
      return b;
    },

    addExpenseItem: (item) => {
      const b = store.getBudget();
      item.id = "bgt-" + Date.now();
      b.items.unshift(item);
      recalcBudget(b);
      const all = budgets();
      all[b.tripId] = b;
      write(KEY_BUDGETS, all);
      push("POST", `/budget/${encodeURIComponent(b.tripId)}/items`, item);
      return b;
    },

    deleteExpenseItem: (itemId) => {
      const b = store.getBudget();
      b.items = b.items.filter(i => i.id !== itemId);
      recalcBudget(b);
      const all = budgets();
      all[b.tripId] = b;
      write(KEY_BUDGETS, all);
      push("DELETE", `/budget/${encodeURIComponent(b.tripId)}/items/${encodeURIComponent(itemId)}`);
      return b;
    },

    // Accounts (require the backend)
    getCurrentUser: () => read(KEY_USER, null),

    login: async (email, password) => {
      await ready;
      if (!online) throw new Error("The TravelMate server is offline, so sign-in is unavailable.");
      const user = await api("POST", "/auth/login", { email, password });
      write(KEY_USER, user);
      return user;
    },

    register: async (fullName, email, password) => {
      await ready;
      if (!online) throw new Error("The TravelMate server is offline, so registration is unavailable.");
      const user = await api("POST", "/auth/register", { fullName, email, password });
      write(KEY_USER, user);
      return user;
    },

    logout: async () => {
      localStorage.removeItem(KEY_USER);
      if (online) await api("POST", "/auth/logout");
    }
  };

  return store;
})();

// Export globally
window.TravelMateStore = TravelMateStore;
