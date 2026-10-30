# Reconciliation — shift 29.10.2026 (20:09–23:14)

**Status: CONSISTENT_UNVERIFIED.** Wolt's totals fall inside what was detected, but 2 order links and 1 order count are uncertain. The shift is **not validated**.

Model: Collector `chains-1` (0.9.4) + engine reconciliation. This replaces the earlier per-trip report, which double-counted carried orders.

## Totals

| | Value |
|---|---|
| Wolt: completed deliveries (ground truth, day total) | **10** |
| Orders detected (new orders per accepted offer) | **9–10** |
| Difference (Wolt − detected) | 0 to +1 |
| Accepted offers | 6 |
| Delivery chains | 4 |
| Wolt: earnings without tips | ₪219.50 |
| Sum of accepted offers (each counted once) | ₪219.50 — exact |
| Wolt: distance | 37.9 km — definition unverified, not used for vehicle cost |

## Model

| Term | Meaning |
|---|---|
| Offer | a card on screen (13 this shift) |
| Accepted offer | may add one or more orders; its amount is attributed to it once |
| Order | one customer's order. Counted, never given an invented id or timestamp |
| Chain | delivery session: starts at an acceptance with nothing on board; offers accepted while carrying join it |
| Accept context | IDLE (case A) or ADDED_TO_ACTIVE_ROUTE (case B) |

- **Evidence:** the route on Wolt's pickup screen lists every active stop. An order's pickup stays listed until the order is delivered. A pickup that was also listed for the previous offer means a carried order.
- **New orders per offer** = drop-offs in the route at acceptance − carried orders.
- **Raw data is untouched:** accessibility events and markers are stored as captured. Chains are rebuilt from them on every export and never stored as edits.

## Reconstruction

| # | Accepted | Offer | Context | Route at acceptance | Carried | New orders | Chain |
|---|---|---|---|---|---|---|---|
| 1 | 20:10 | ₪30.86 | IDLE | 1 pickup, 1 drop-off | 0 | 1 | 1 |
| 2 | 20:38 | ₪42.16 | IDLE | 2 pickups, 2 drop-offs | 0 | 2 | 2 |
| 3 | 21:21 | ₪31.09 | IDLE | 2 pickups, 2 → 1 drop-offs | 0 | **1–2** | 3 |
| 4 | 21:48 | ₪72.19 | IDLE | 2 pickups, 2 drop-offs | 0 | 2 (A, B) | 4 |
| 5 | 22:12 | ₪25.95 | **ADDED** | 2 pickups, 3 drop-offs | 1 (B) | 2 | 4 |
| 6 | 22:47 | ₪17.25 | **ADDED** | 2 pickups, 2 drop-offs | 1 (B) | 1 | 4 |

**Chain 4, matched to your account:**
1. You accepted ₪72.19 with 2 pickups (A and B).
2. You delivered A; the drop-off screen opened at 21:57.
3. B's pickup stayed in the route at 22:12 and 22:47, so you accepted ₪25.95 and ₪17.25 while carrying B.
4. You delivered B last; the final drop-off screen ended at ~23:02–23:09.

One detail differs from your account: the ₪25.95 offer (your "C") added **2** drop-offs, and its card showed "заказы 2".

**Chain revenue:** ₪115.39 (72.19 + 25.95 + 17.25). Each offer's amount is attributed to that offer.

## Uncertain links (marked, not resolved)

- **Order B's link:** the 2 carried links (offers 5 and 6 → B) are matched by the redacted shape of the stop only (`SHAPE_MATCH`), not by an order id.
- **Offer 3 (₪31.09):** the route showed 2 drop-offs until 21:28:44 and 1 from 21:28:48. Either two orders merged into one stop (2 orders; Wolt's 10 suggests this) or one order was removed (1 order). Not confirmed.
- **Per-offer end times inside chain 4** are not completions (`trip_boundary_valid = false`).
- **Missing end times:** chain 3 has no end time. Chain 4's end (~23:09) is estimated.

## Shadow mode

- **Training:** delivery durations train only on a trip that is a proven single order, alone in its chain, and accepted while idle. This shift has **1** such trip (20:10). No incorrect trip boundary enters training.
- **Decision time:** each offer now records whether it came while a route was active (case B). Those offers are classified as INSUFFICIENT_DATA with the reason "an incremental model is needed".

**Case B is not modelled yet.** Its incremental revenue is exact (the offer amount). Its incremental time and distance relative to the existing route are **not estimated** yet: that needs more shifts with added offers.

## To verify next shift

- After each delivery, note which orders were on board.
- Screenshot any order that disappears from the route (cancelled or reassigned).
- Note whether Wolt's 37.9 km includes the drive to each pickup.
