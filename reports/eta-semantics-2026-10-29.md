# What Wolt's route numbers mean — investigation, shift 29.10.2026

**Verdict: Wolt's route numbers cannot support an incremental-offer model.** They are per-stop **target clock times** that stay fixed while you wait and did not move when work was added. Completions came **2.9–20.5 min later** than the target (median +7.2) on the 8 stops matched to the route.

- **Data:** the raw accessibility snapshots of the shift: 396 frames with route, drop-off or time nodes, and 10 completion slides.
- **Re-check:** `npm run eta:report -- 2026-10-29` regenerates the per-stop numbers. Collector `EtaValidation` (eta-validation-1) holds the logic, and `RealShiftTest.woltRouteMinutesAreTargetsNotLiveEtas` pins it.
- **Export:** the phone was locked, so the 0.9.7 export sections were recomputed on the computer from that night's export. It ran the same code as the app, over the same raw events, and gave the same 6 trips.

## Labels

| Label | Meaning |
|---|---|
| OBSERVED | Seen on screen with its own time (drop-off slide = completion) |
| WOLT_DISPLAY | A number Wolt showed: route minutes, clock times, marker minutes |
| MODEL | Not used anywhere in this investigation |

## 1. What each displayed number is

| Display | Where | What it is | Evidence |
|---|---|---|---|
| `[MIN n]` after a stop | Pickup screen's route list (`TimelineItemV3`) | **Minutes until that stop's target clock time.** Per stop, in route order; a stop already reached shows none. | Each minutes node directly follows one stop's address node. Snapshot time + n stayed within ±2 min in **40 of 45** frame pairs ≥30 s apart, including while the courier was waiting at a restaurant. |
| Map-marker minutes | Drop-off screen (`TaskMarker.PopoutLabel`) | **\|now − target\|**: counts down to the target, then up while late | Matches a shown target in 109 of 133 drop-off screens. Example: 10 → 0 at 21:03, then up to 15 by 21:20 (target 21:05). |
| Clock-time pairs | Drop-off screen | Two times per active order. The later one equals the route target for 8 of 10 orders. The earlier one's meaning is unknown (possibly when the order was placed). | When an order is completed, its pair disappears from the screen. That identifies which order the slide completed. |

**What these numbers are not:**
- **Not time to the next stop only:** every remaining stop has its own value.
- **Not a live travel ETA from your position:** the implied clock time doesn't move while you wait. For example, chain 4 at 22:12:12 and 22:20:03 showed the same 22:26 / 22:40 / 23:00.
- **Not cumulative route duration:** the values are clock targets, not sums.
- **Closest to:** an estimated or promised arrival time per stop, shown as minutes from now.

**Caveats:**
- **Re-planning:** targets do move sometimes (5 of 45 pairs), e.g. chain 3 at 21:28–21:34 and chain 4 at 22:54. Wolt re-plans occasionally.
- **Stale frames:** 5 frames showed old minutes right after the screen resumed and were corrected seconds later. They are excluded.

## 2. Predicted vs observed

Of 10 completions, identity came from the drop-off screen for 8 and from the route for 2. Both route-based identities are marked; one (22:46:38) is less certain because another completion happened in between.

| Against | n | Min | Median | Max |
|---|---|---|---|---|
| Route target, first seen | 8 | +2.9 | +7.2 | +20.5 |
| Drop-off screen target | 10 | +2.6 | +9.3 | +33.3 |

- **Every completion was later than its target.** Targets were first seen 21–81 minutes ahead.
- **Two drop-screen times never appeared as route targets:** 21:47:18 (+33) and 23:01:59 (D, +23). These are reported, not forced onto a stop.

## 3. Why the route numbers can't measure an added offer

- **B's target did not move:** it was 23:00 from 21:48 until 22:59, through both added offers (22:12 and 22:47). B was completed at 23:09:15, +9.3 min.
- **The before-route is stale and includes unrelated changes:**
  - The route seen before each added offer was 14.7 and 21.8 min old.
  - 1 and 2 completions happened in between (route change for another reason = yes).
  - The drop-off screen right before acceptance is fresh (≤20 s old), but it shows the order times, not a route.
- **The earlier "ETA delta" method was invalid and has been removed.** So have the 0–58 / 0–22 min bounds and the "at least ₪X/h" marginal figures.
- **The chain's duration and distance without an added offer are now UNKNOWN.** Only its revenue is known (exact). The extra time is not assumed to be non-negative.

## 4. What is now recorded for every offer added mid-route

- **Route lists:** the full ordered list just before and just after acceptance. Each stop keeps its position, kind, redacted shape, shown minutes and target clock time. Stale frames are marked.
- **Ages:** the age of the route snapshot before and the delay of the one after.
- **Drop-off screen before:** the last one before acceptance, with its order times and age.
- **Completions in between:** how many happened between the before-route and acceptance. "Route changed for other reasons" is set to yes if any did.
- **Carried stop target shift:** only computed when the carried stop's shape is unambiguous. In this shift it was ambiguous, so it is null.

## 5. What a justified incremental model would need

- **Fresh before/after snapshots:** the route list right before acceptance (seconds, not 15–22 min old). This depends on Wolt showing the route at that moment, which can't be forced passively.
- **A target that responds to added work, or a different ground truth:** per-stop completion times for every order (now OBSERVED via slides), across many chains with and without added offers.
- **Measured distance:** GPS finer than the 500 m grid, or the GPS distance driven between consecutive completions.

Until then, an added offer's value is not estimated.
