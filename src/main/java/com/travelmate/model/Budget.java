package com.travelmate.model;

import java.util.ArrayList;
import java.util.List;

/** Budget for one trip. Totals are always computed from the items. */
public class Budget {
    public String tripId;
    public double totalLimit;
    public double estimatedTotal;
    public double actualSpend;
    public String currency = "INR (₹)";
    public List<BudgetItem> items = new ArrayList<>();
}
