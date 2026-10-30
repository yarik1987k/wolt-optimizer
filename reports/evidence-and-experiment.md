# Evidence & Experiment Plan

Goal: measurable ways to raise **net revenue per full shift hour** (accepted-offer amounts − vehicle cost, idle time included). No live recommendations.

Rules (settings): work shift ≥ 60 min · VALIDATED needs ≥ 5 independent work shifts per compared condition and a 95% interval excluding zero · coverage ≥ 80% · smallest effect worth acting on ₪5.00/h · vehicle cost ₪1.10/km.

## Observation periods

Shift boundaries, offers seen, acceptances and completions are different clocks; reports never mix periods silently.

| Period | Shift boundaries | Offers seen | Acceptances | Completions (OBSERVED slides) | Chains | Revenue | In shift economics |
|---|---|---|---|---|---|---|---|
| TEST_OR_SHORT_SESSION | 29/10/2026, 12:21:30 – 29/10/2026, 12:31:33 | 5: 29/10/2026, 12:22:25 – 29/10/2026, 12:27:51 | 0 | 0 | 0 | ₪0.00 | no |
| TEST_OR_SHORT_SESSION | 29/10/2026, 12:37:42 – 29/10/2026, 12:38:46 | 1: 29/10/2026, 12:38:22 – 29/10/2026, 12:38:22 | 0 | 0 | 0 | ₪0.00 | no |
| WORK_SHIFT | 29/10/2026, 20:09:29 – 29/10/2026, 23:14:40 | 13: 29/10/2026, 20:10:33 – 29/10/2026, 23:13:49 | 6: 29/10/2026, 20:10:39 – 29/10/2026, 22:47:00 | 10: 29/10/2026, 20:35:50 – 29/10/2026, 23:09:15 | 4 | ₪219.50 | yes |
| OUTSIDE_SHIFT_SESSIONS | — | 14: 29/10/2026, 09:40:27 – 29/10/2026, 13:47:03 | 0 | 0 | 0 | ₪0.00 | no |

## Operational constraints (per work shift)

### Shift 29/10/2026, 20:09:29 – 29/10/2026, 23:14:40 (3.09 h)

Net per full shift hour: **₪57.79–₪58.79** (OBSERVATION).

| Constraint | Observed | Share | Interpretation |
|---|---|---|---|
| Stationary time inside chains | 71 min of 185 (to pickup + waiting: 14 min · delivery: 57 min) with GPS speed < 1 m/s | 38% of shift time | Waiting at restaurants, handover at the door and traffic stops all look the same in GPS speed; which dominates is not known. The split is at the chain's first pickup, so later pickups of added offers fall in the delivery phase. |
| Vehicle cost | ₪38.06–₪41.13 for 34.6–37.4 GPS km | 18% of revenue | Proportional to km driven; GPS distance on the 500 m grid probably understates it. |
| Idle time (online, no accepted offer) | 9 min verified idle; 10 min between chains in total | 5% of shift time | Small in this shift: the next offer arrived within minutes each time. |
| Long trips relative to payment | slowest chain ₪42.16 over 43–43 min = ₪0.98–₪0.99/min; at the shift average it would have earned ≈₪11.49 more | 5% of revenue | Whether such trips can be recognised from the card before accepting is not known (one example). |
| Unproductive repositioning | 1.1 GPS km and 4 min moving between chains | 3% of GPS km | Share of the shift's km driven with no accepted offer. |
| Revenue per active minute | ₪1.26 per active minute on average; chains ranged ₪0.98–₪1.42 | — | Active time was 95% of the shift, so pay per active minute sets most of the shift result. |

| Chain | Offers | Revenue | Minutes | ₪ / active min | GPS km |
|---|---|---|---|---|---|
| 29/10/2026, 20:10:39 | 1 | ₪30.86 | 25–25 | ₪1.22–₪1.23 | 3.5–3.6 |
| 29/10/2026, 20:38:46 | 1 | ₪42.16 | 43–43 | ₪0.98–₪0.99 | 7.3–9.0 |
| 29/10/2026, 21:21:58 | 1 | ₪31.09 | 25–26 | ₪1.22–₪1.22 | 3.6–5.5 |
| 29/10/2026, 21:48:10 | 3 | ₪115.39 | 81–82 | ₪1.41–₪1.42 | 17.0–20.1 |

## Uncertainty and effective sample size

- **Primary metric** (unit = work shift): n = 1, mean ₪58.29, sd unknown, 95% interval not computable — one work shift: between-shift variation is unknown, so no interval and no comparison is possible yet.
- **Chain level**: 4 chains, Kish effective n 3.1 (weights = chain minutes), effective n for shift-level claims 1 — all chains come from one shift: they describe that evening, not shifts in general (effective n for shift-level claims = 1). Per-chain gross revenue per active hour (before vehicle cost): mean ₪72.72, sd ₪10.59 (CV 15%).
- Chain-level analysis stays separate from offer-level analysis: offers inside a chain have no duration or distance of their own.

## OBSERVATION

- **Scope:** 1 work shift(s) in scope; 2 short/test session(s) and 14 offer(s) outside any session are excluded from shift economics.  
  _Basis:_ Work shift = session ≥ 60 min. Test sessions: 12:21–12:31 (5 offers), 12:37–12:38 (1 offers).
- **Primary metric:** Net revenue per full shift hour: ₪57.79–₪58.79.  
  _Basis:_ Shift 20:09–23:14 (3.09 h): accepted offers ₪219.50 − vehicle cost of GPS km. Range = GPS distance range.
- **Stationary time inside chains:** 71 min of 185 (to pickup + waiting: 14 min · delivery: 57 min) with GPS speed < 1 m/s  
  _Basis:_ Shift 20:09–23:14; GPS speed sampled every 45–120 s.
- **Revenue per active minute:** ₪1.26 per active minute on average; chains ranged ₪0.98–₪1.42  
  _Basis:_ Shift 20:09–23:14; GPS speed sampled every 45–120 s.
- **Long trips relative to payment:** slowest chain ₪42.16 over 43–43 min = ₪0.98–₪0.99/min; at the shift average it would have earned ≈₪11.49 more  
  _Basis:_ Shift 20:09–23:14; GPS speed sampled every 45–120 s.
- **Vehicle cost:** ₪38.06–₪41.13 for 34.6–37.4 GPS km  
  _Basis:_ Shift 20:09–23:14; GPS speed sampled every 45–120 s.
- **Idle time (online, no accepted offer):** 9 min verified idle; 10 min between chains in total  
  _Basis:_ Shift 20:09–23:14; GPS speed sampled every 45–120 s.
- **Unproductive repositioning:** 1.1 GPS km and 4 min moving between chains  
  _Basis:_ Shift 20:09–23:14; GPS speed sampled every 45–120 s.
- **Wolt's target times:** 10 completions compared with Wolt's targets: all later than the target (see ETA validation).  
  _Basis:_ Used only for target-vs-completion analysis, never as a travel-time estimate.

## HYPOTHESIS

- **Largest constraint:** Stationary time inside chains is the largest controllable share of shift time (38% on the one work shift).  
  _Basis:_ One shift; a busy evening with almost no idle time. On a quieter shift idle time could dominate instead.  
  _Needs:_ The same split on ≥ 5 work shifts across different hours/days.
- **What the stationary time is:** Most stationary time inside chains is waiting at restaurants and handover, not traffic.  
  _Basis:_ Not separable from GPS speed alone.  
  _Needs:_ Stop-level split: stationary time within ~150 m of the pickup cell vs. the drop-off vs. elsewhere (needs finer GPS than the 500 m grid, or arrival markers).
- **Offers added to an active route:** Accepting offers while already carrying orders raises net revenue per shift hour.  
  _Basis:_ One chain with two added offers earned more per active hour than the standalone chains of the same evening — one example, same shift, not a comparison of policies.  
  _Needs:_ A planned comparison of shift time with and without added offers (Experiment 1).
- **Card characteristics:** Price, displayed distance, area or bundling on the card predict net per active hour.  
  _Basis:_ 3 standalone chains, all in one price band and one distance band.  
  _Needs:_ Many more standalone chains across bands and shifts; then per-shift replication of any difference.
- **Validated findings:** None yet.  
  _Basis:_ A VALIDATED_FINDING needs ≥ 5 independent work shifts per compared condition with a 95% interval excluding zero; there is 1 work shift.

## VALIDATED_FINDING

None.


## Proposed experiment EXP-1: Offers added to an active route: accept as usual vs. decline while carrying

**Hypothesis.** Accepting offers that arrive while you already carry an order (case B) raises net revenue per full shift hour, because added stops fill time you are already driving or waiting, at less extra distance than a separate trip.

**Why this one first**
- OBSERVATION: on the one work shift, chain time was 95% of the shift and between-chain time 5% — so the lever is what happens inside chains, not finding work.
- OBSERVATION: stationary time inside chains was the largest non-driving share of the shift; added offers are the main way extra revenue enters time already spent in a chain.
- OBSERVATION: 2 of 6 accepted offers were added to an active route — the policy matters often enough to measure.
- HYPOTHESIS (unproven): the one chain with added offers earned more per active hour. It is a single example from one evening; only a planned comparison can tell.
- It needs no new data source: chains, added-offer context, completions and GPS are already recorded; only the arm label is missing.

**Baseline.** ₪57.79–₪58.79 ₪ net per full shift hour — the one work shift so far (accepting added offers as usual). between-shift spread unknown (one shift) — the baseline itself is not yet reliable; the first arm-A shifts also establish it.

**Arms**
- **A (current practice):** Accept or decline offers as you normally would, including offers that arrive while carrying orders.
- **B (no add-ons):** Decline every offer that arrives while an accepted order is still on board; offers that arrive when idle are handled as usual.

**Unit.** One whole work shift per arm (shifts alternate A/B; the arm is decided before the shift, by coin flip within pairs of similar shifts — same weekday type and start hour ±1 h).

**Primary metric.** Net revenue per full shift hour = (accepted-offer amounts − vehicle cost of GPS km) ÷ shift hours, idle time included.

**Secondary metrics**
- Net revenue per active (chain) hour
- Share of shift time idle / between chains / stationary inside chains
- GPS km per ₪ of revenue
- Completed deliveries per shift hour (OBSERVED slides), reconciled with Wolt's daily statistics
- Offers received per online hour (does declining change what Wolt sends?)
- Wolt's target-vs-completion lateness (descriptive only)

**Data required**
- Shift facts, chains, completions, GPS distance (Collector export) — available
- Accept context per offer (IDLE / ADDED_TO_ACTIVE_ROUTE) and decisions — available
- Wolt's daily statistics per shift day (official count and earnings) — available
- Arm label per shift, set before the shift starts — **not yet built**
- Notes on surge/bonus banners and weather per shift — **not yet built**

**Sample size**
- Minimum before any verdict: 5 work shifts per arm (setting evidenceMinShiftsForValidation).
- Per-shift spread is unknown; after 3 shifts per arm compute sd and the required shifts per arm for the smallest effect worth acting on (₪5/h, setting evidenceSmallestEffectAgorotPerHour) — if it exceeds what is practical, stop and redesign.

**Supporting evidence.** A − B > 0 with the 95% interval of the per-shift difference above zero, after ≥ 5 shifts per arm, and A ahead in most matched pairs.

**Contradicting / no-effect evidence**
- The interval lies below zero (declining add-ons earned more per shift hour).
- The interval lies within ±₪5/h — no practically relevant difference; keep whichever is easier.
- Anything else after the planned shifts is INCONCLUSIVE: extend once, then stop.

**Confounders and mitigation**
- **Demand level (weekday, hour, weather, events):** Pair shifts by weekday type and start hour; flip a coin within each pair; record weather.
- **Wolt surge, bonuses and guarantees:** Record banners per shift; analyse with and without bonus shifts.
- **Wolt dispatch may react to declines (acceptance rate):** Track offers per online hour in both arms; check Wolt's terms before declining systematically — this is a risk you decide on.
- **Area / restaurant mix:** Start shifts from the same zone in both arms; compare courier-area distribution.
- **Fatigue and learning over the weeks:** Alternate arms over time rather than running all A first.
- **Vehicle cost assumption (₪/km) and coarse GPS distance:** Same rule in both arms; report sensitivity at ±20% cost.

**Risks and limits**
- Arm B means declining offers manually while carrying — no automation, no alerts while driving; decide before the shift, not on the road.
- Declining may affect your standing with Wolt (acceptance metrics); weigh this before running arm B.
- Per-offer economics inside chains stay UNKNOWN; the experiment compares whole shifts only.
