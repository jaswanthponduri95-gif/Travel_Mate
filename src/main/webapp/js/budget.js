/**
 * TRAVELMATE — BUDGET & EXPENSES LOGIC (PAGE 8)
 * Real-time budget calculations, category expense breakdown, item insertion/deletion.
 */

document.addEventListener("DOMContentLoaded", () => {
  renderBudgetDashboard();
  document.addEventListener("tm:synced", renderBudgetDashboard);
});

// Fill the "Associated Day" dropdown from the active trip's days
function renderExpenseDayOptions() {
  const select = document.getElementById("exp-day");
  if (!select) return;
  const trip = window.TravelMateStore.getActiveTrip();
  select.innerHTML = trip.days.map(d => `<option value="Day ${d.dayNumber}">Day ${d.dayNumber}</option>`).join("") +
    `<option value="General">Entire Trip</option>`;
}

function renderBudgetDashboard() {
  const budgetData = window.TravelMateStore.getBudget();
  const trip = window.TravelMateStore.getActiveTrip();
  const travelers = Math.max(Number(trip ? trip.travelers : 1) || 1, 1);
  renderExpenseDayOptions();

  const subtitle = document.getElementById("bgt-trip-subtitle");
  if (subtitle) {
    const days = trip.days ? trip.days.length : trip.daysCount;
    subtitle.textContent = `${trip.title} (${days} ${days === 1 ? "Day" : "Days"}, ${travelers} ${travelers === 1 ? "Traveler" : "Travelers"})`;
  }

  // Header figures (an empty budget really is 0, so no "||" fallbacks here)
  const totalLimit = Number(budgetData.totalLimit) || 0;
  const estimatedTotal = Number(budgetData.estimatedTotal) || 0;
  const actualSpend = Number(budgetData.actualSpend) || 0;
  const remaining = totalLimit - estimatedTotal;
  const perPerson = Math.round(estimatedTotal / travelers);

  document.getElementById("bgt-total-limit").textContent = `₹${totalLimit.toLocaleString()}`;
  document.getElementById("bgt-estimated-total").textContent = `₹${estimatedTotal.toLocaleString()}`;
  document.getElementById("bgt-actual-spend").textContent = `₹${actualSpend.toLocaleString()}`;
  document.getElementById("bgt-remaining").textContent = `₹${remaining.toLocaleString()}`;
  document.getElementById("bgt-per-person").textContent = `₹${perPerson.toLocaleString()} / person`;

  // Status indicator
  const statusEl = document.getElementById("bgt-health-status");
  if (statusEl) {
    if (remaining >= 0) {
      statusEl.className = "tm-badge tm-badge-success";
      statusEl.innerHTML = `<i class="bi bi-shield-check"></i> Within Budget (₹${remaining.toLocaleString()} buffer)`;
    } else {
      statusEl.className = "tm-badge tm-badge-warning";
      statusEl.innerHTML = `<i class="bi bi-exclamation-triangle"></i> Over Budget by ₹${Math.abs(remaining).toLocaleString()}`;
    }
  }

  // Category breakdown progress bars
  const catContainer = document.getElementById("bgt-categories-container");
  if (catContainer) {
    catContainer.innerHTML = window.TravelMateStore.getBudgetCategories().map(cat => {
      const pct = estimatedTotal > 0 ? Math.min(Math.round((cat.estimated / estimatedTotal) * 100), 100) : 0;
      return `
        <div class="mb-3">
          <div class="d-flex justify-content-between align-items-center mb-1">
            <span class="small fw-semibold text-dark"><i class="bi ${cat.icon} me-1"></i> ${cat.name}</span>
            <span class="small text-muted fw-bold">₹${cat.estimated.toLocaleString()} (${pct}%)</span>
          </div>
          <div class="progress" style="height: 8px;">
            <div class="progress-bar" role="progressbar" style="width: ${pct}%; background-color: ${cat.color};"></div>
          </div>
        </div>
      `;
    }).join("");
  }

  // Items table
  const tableBody = document.getElementById("bgt-items-table-body");
  if (tableBody && budgetData.items) {
    tableBody.innerHTML = budgetData.items.length === 0
      ? `<tr><td colspan="6" class="text-center text-muted py-4">No expenses yet. Use "Add Expense" to start tracking.</td></tr>`
      : budgetData.items.map(item => `
      <tr>
        <td class="fw-semibold text-dark">${escapeHtml(item.name)}</td>
        <td><span class="badge bg-light text-secondary border small">${escapeHtml(item.category)}</span></td>
        <td><span class="small text-muted">${escapeHtml(item.day || 'General')}</span></td>
        <td>
          <span class="badge ${item.status === 'Actual' ? 'bg-success-subtle text-success border border-success' : 'bg-secondary-subtle text-dark'}">
            ${escapeHtml(item.status)}
          </span>
        </td>
        <td class="fw-bold text-dark">₹${Number(item.amount).toLocaleString()}</td>
        <td class="text-end">
          <button type="button" class="btn btn-sm btn-outline-danger border-0" onclick="handleDeleteBudgetItem('${escapeHtml(item.id)}')" title="Delete Expense">
            <i class="bi bi-trash3"></i>
          </button>
        </td>
      </tr>
    `).join("");
  }
}

// Add Expense Item
function handleAddNewExpense(event) {
  event.preventDefault();
  const name = document.getElementById("exp-name").value.trim();
  const category = document.getElementById("exp-category").value;
  const day = document.getElementById("exp-day").value;
  const status = document.getElementById("exp-status").value;
  const amount = Number(document.getElementById("exp-amount").value);

  const newItem = {
    name: name,
    category: category,
    day: day,
    status: status,
    amount: amount
  };

  window.TravelMateStore.addExpenseItem(newItem);
  renderBudgetDashboard();

  bootstrap.Modal.getInstance(document.getElementById("addExpenseModal")).hide();
  document.getElementById("add-expense-form").reset();
  showToast(`Added expense "${name}" (₹${amount})`, "success", "bi-wallet2");
}

function handleDeleteBudgetItem(itemId) {
  if (confirm("Are you sure you want to remove this expense item?")) {
    window.TravelMateStore.deleteExpenseItem(itemId);
    renderBudgetDashboard();
    showToast("Expense removed from budget", "warning", "bi-trash3");
  }
}

// Download the expense items as a CSV file (opens in Excel)
function handleExportBudgetCsv() {
  const b = window.TravelMateStore.getBudget();
  const trip = window.TravelMateStore.getActiveTrip();
  const cell = (v) => `"${String(v ?? "").replace(/"/g, '""')}"`;
  const rows = [["Item", "Category", "Day", "Status", "Amount (INR)"]]
    .concat(b.items.map(i => [i.name, i.category, i.day || "General", i.status, i.amount]))
    .concat([[], ["Estimated total", "", "", "", b.estimatedTotal], ["Actual spend", "", "", "", b.actualSpend],
      ["Budget limit", "", "", "", b.totalLimit]]);
  // BOM so Excel reads the UTF-8 text (₹, en dashes) correctly
  const csv = "﻿" + rows.map(r => r.map(cell).join(",")).join("\r\n");
  const link = document.createElement("a");
  link.href = URL.createObjectURL(new Blob([csv], { type: "text/csv;charset=utf-8" }));
  link.download = `${(trip.title || "trip").replace(/[^\w-]+/g, "_")}_budget.csv`;
  link.click();
  URL.revokeObjectURL(link.href);
  showToast("Exported expense ledger as CSV", "accent", "bi-file-earmark-spreadsheet");
}

window.handleExportBudgetCsv = handleExportBudgetCsv;
window.handleAddNewExpense = handleAddNewExpense;
window.handleDeleteBudgetItem = handleDeleteBudgetItem;
