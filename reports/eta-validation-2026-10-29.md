# ETA validation — 2026-10-29

Labels: **OBSERVED** = drop-off slide (completion) with its time · **WOLT_DISPLAY** = a time or minutes Wolt showed · **MODEL** = nothing here; no model estimates are used.

Error = completion − Wolt's target, in minutes (positive = later than the target).

## Completions vs Wolt's targets

| Completed (OBSERVED) | Stop identity | Order times on drop-off screen (WOLT_DISPLAY) | Route target first seen (WOLT_DISPLAY) | Target at first sight | Target last seen | Error vs drop-off screen | Error vs first route target | Error vs last route target | Lead | Notes |
|---|---|---|---|---|---|---|---|---|---|---|
| 20:35:50 | SINGLE_ORDER_ON_SCREEN | 20:03 / 20:29 | 20:10:39 | 20:28 | 20:29 | +6.8 | +7.2 | +5.9 | 25 min |  |
| 21:05:37 | TIME_PAIR_REMOVED | 20:44 / 21:03 | 20:38:46 | 21:02 | 21:03 | +2.6 | +2.9 | +2.4 | 27 min |  |
| 21:21:23 | SINGLE_ORDER_ON_SCREEN | 20:50 / 21:05 | 20:38:46 | 21:04 | 21:05 | +16.4 | +16.6 | +16.2 | 43 min |  |
| 21:47:18 | TIME_PAIR_REMOVED | 21:03 / 21:14 | — | — | — | +33.3 | — | — | — | target not seen on any route screen before completion (as an unclaimed stop) |
| 21:47:26 | SINGLE_ORDER_ON_SCREEN | 20:58 / 21:27 | 21:21:58 | 21:26 | 21:27 | +20.4 | +20.5 | +19.8 | 25 min |  |
| 22:11:27 | TARGET_GONE_FROM_ROUTE | 22:05 | 21:48:10 | 22:05 | 22:06 | +6.5 | +6.3 | +4.5 | 23 min |  |
| 22:33:23 | TIME_PAIR_REMOVED | 22:18 / 22:26 | 22:12:12 | 22:26 | 22:26 | +7.4 | +7.2 | +7.2 | 21 min |  |
| 22:46:38 | TARGET_GONE_FROM_ROUTE | 22:40 | 22:12:12 | 22:40 | 22:40 | +6.6 | +6.4 | +6.5 | 34 min | 1 other completion(s) between the two routes — identity less certain |
| 23:01:59 | TIME_PAIR_REMOVED | 22:34 / 22:39 | — | — | — | +23.0 | — | — | — | target not seen on any route screen before completion (as an unclaimed stop) |
| 23:09:15 | SINGLE_ORDER_ON_SCREEN | 21:27 / 23:00 | 21:48:10 | 23:00 | 22:59 | +9.3 | +9.1 | +9.4 | 81 min |  |

## Error ranges

| Against | n | Min | Median | Mean | Max |
|---|---|---|---|---|---|
| first route target (identity matched on the route) | 8 | +2.9 | +7.2 | +9.5 | +20.5 |
| drop-off screen target | 10 | +2.6 | +9.3 | +13.2 | +33.3 |

Stops with a reliable identity: 10 of 10. Small sample — these ranges describe this day only.
