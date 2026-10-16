# Detection Module Specification

**Derived from:** the production metric baseline table in `failure-mode-taxonomy.md`
**Runs against:** the reference pipeline in `reference-impl/transaction-pipeline/`
**Status:** v0, implemented in the reference pipeline (D1 through D5; D6 awaits a broker-backed stage)

## Purpose and scope

The taxonomy concluded that detection is mostly generic while recovery is mostly specific. This module is the generic half: a reusable detection layer that watches the metric set from the baseline table, decides when behavior has left normal, and emits a machine-readable verdict saying what diverged and where. Recovery policy consumes verdicts; it lives elsewhere.

Scope is availability signals: error rates, outcomes, latency, throughput, and saturation. Data-correctness detection (Family 7 reconciliation) is a separate concern with a different trigger model and is out of scope here.

## Design principles

Four principles, each paid for in production.

1. **Outcome-anchored.** The primary signal is transactions succeeding per client, not component health. Fleet averages hid every gray failure in Family 1; a single client's outcome ratio diverging never lied.
2. **Baseline-relative, never static.** Thresholds are computed against learned baselines, not fixed numbers someone typed in once.
3. **Time-aware.** Normal has a daily rhythm. A baseline that ignores the clock produces false alarms at exactly the moments schedules differ from the average.
4. **Verdict-producing.** A detection that says "something is wrong" still leaves a human to walk the system end to end. The output of this module is a verdict that already contains the walk: which signal tripped, which stage or client or dependency it localizes to, and the evidence.

**Field note:** my own detection loop in production was human at every step. I monitored email alerts first, then validated what they claimed against the database, and kept an eye on the client chat space for issues the alerts missed. Each of those steps is in this spec as a design principle: the chat space is why detection anchors on client outcomes, the database validation is why verdicts carry evidence, and the email inbox is why delivery latency is part of the design and not an afterthought.

**Field note:** some of our alerts triggered when nothing was wrong, because processing simply ran longer than the time that had been set up for it. Nobody re-tuned the numbers, so the alerts cried wolf on schedule. That experience is why this spec has no static thresholds anywhere: every trigger is defined relative to a baseline the module maintains itself.

## Inputs

The five metrics from the taxonomy baseline table, mapped to their sources in the reference pipeline:

| Metric | Reference pipeline source | Available today |
|---|---|---|
| Response classes (2xx / 4xx / 5xx) by service | Actuator HTTP metrics | Yes |
| Transactions per client, success vs failure | `/stats` counters, Micrometer tags | Yes |
| Throughput (arrival and completion rates) | Micrometer counter rates | Yes |
| Message broker consumer lag | Not present; the skeleton pipeline is synchronous | Future, when a broker stage is added |
| CPU and memory per service | Actuator system metrics | Yes |

## Detection primitives

Each primitive defines a signal, a baseline, a trigger policy, and verdict content. Thresholds are expressed as multiples of baseline deviation, with concrete defaults to be calibrated against the reference pipeline's known behavior.

### D1. Per-client outcome divergence

- **Signal:** per-client failure ratio over a sliding window.
- **Baseline:** each client's own historical failure ratio, learned per time-of-day bucket.
- **Trigger:** a client's ratio exceeds its own baseline by a sustained margin while the fleet ratio does not. The fleet-quiet condition is the point: this primitive exists to catch what averages hide.
- **Verdict:** the client, its current vs baseline ratio, the failing stage distribution for that client's transactions.

**Field note:** when a single client's failures diverged in production, the cause was usually specific to that client: their option configurations, or the fact that their data involved chip features handled through an external vendor's service. Different clients genuinely run different code paths and dependency chains through the same pipeline. That is why per-client divergence is a first-class primitive and why its verdict includes the failing stage rather than just the ratio: the next question is always which part of that client's particular path broke.

### D2. Error-class shape shift

- **Signal:** the mix of outcome classes (per-stage failures in the pipeline; 4xx vs 5xx at the HTTP layer).
- **Baseline:** expected mix, learned per window. The reference pipeline ships with a known 2% authorization decline rate, which makes it the calibration case: a healthy mix that must not alarm.
- **Trigger:** the mix shifts beyond baseline variance, even when total volume is flat. A rise in settlement failures against flat authorization declines is a different fault than the reverse, and the shape says so before totals move.
- **Verdict:** the class or stage whose share moved, direction, magnitude vs baseline.

### D3. Latency percentile divergence

- **Signal:** p99 and p50 latency per stage and end to end.
- **Baseline:** rolling percentile bands per time-of-day bucket.
- **Trigger:** p99 rising while p50 holds is the early warning (Family 3.1); both rising together indicates saturation already underway.
- **Verdict:** the stage where divergence originates, current vs baseline percentiles.

### D4. Arrival slope anomaly

- **Signal:** transaction arrival rate slope, and arrival rate vs unique-transaction rate.
- **Baseline:** rolling arrival rate with time-of-day awareness.
- **Trigger:** slope exceeding baseline variance (surge, Family 4); arrival diverging from unique-transaction rate (retry storm, pattern 3.2); arrival dropping toward zero while the pipeline reports healthy (traffic-path failure, Family 1.3). The drop has two rules:
  - **Zero arrivals** while the baseline expects traffic fire at once. This stays a rule of its own rather than the bottom of the band, because on a noisy baseline the band's lower edge is below zero.
  - **A partial drop** fires when arrivals fall below both mean - sigma * stddev and mean * (1 - relative-floor) for `confirm-windows` consecutive windows. A low window is neither flagged nor learned while it waits for confirmation, so a lasting drop keeps firing instead of becoming the baseline.
- **Verdict:** direction and shape of the anomaly, plus which downstream primitives it should arm.
  - **Wording:** "traffic-path failure signature" is reserved for zero. The partial-drop verdict is neutral, because the reference generator is closed-loop: a slow stage cuts arrivals, and that is back-pressure, not a dead path.
  - **Cooldowns:** they run per condition (zero, drop, surge), so a drop verdict does not mute the zero verdict of a path that degrades and then dies.
- **Limits:**
  - A drop shallower than `relative-floor` (30% at defaults) never fires.
  - A drop that flaps, low one window and in band the next, never confirms.
  - A lasting legitimate drop is never learned, so it alarms once per cooldown for as long as the old baseline stands.

### D5. Saturation trending

- **Signal:** CPU and memory per service, trend not level.
- **Baseline:** utilization bands per time-of-day bucket.
- **Trigger:** sustained trend toward a limit at a slope that reaches it within the scaling latency window. Trending is the point: an alert at 90% is an alert after the window to act has mostly closed.
- **Verdict:** the resource, the trend, projected time to saturation.

### D6. Consumer lag growth (future)

- **Signal:** broker consumer lag per consumer group.
- **Trigger:** lag growing while consumption rate is flat or falling.
- Deferred until the reference pipeline gains a broker-backed stage. The taxonomy records why it stays on the list: lag moves before user-visible latency does, and that early window is exactly what self-healing needs.

## Time-aware baselines

Every baseline above is bucketed by time of day. In production our load had a daily rhythm driven by scheduled jobs, and a threshold that fits the afternoon fits three in the morning badly in both directions: it misses real problems during quiet hours and alarms on healthy busy hours. The reference pipeline generates flat traffic today; the baseline model must still be bucketed from the start, so that scheduled-load simulation can be added without redesigning detection.

## The verdict

Every trigger emits one verdict object: primitive, window, the localizing dimension (stage, client, resource, or path), current vs baseline values, and the raw evidence counts. Verdicts are the module's only output. They go to the same event channel the deployment gate pattern uses, with paging-grade delivery for anything transaction-stopping; an email inbox is not a delivery mechanism for a verdict.

**Field note:** whenever a metric went strange, my first manual moves were always the same: read the pod logs, then walk the database tables to verify end to end where the transaction went wrong. That walk took minutes when things were calm and much longer during an incident, and it had to be repeated from scratch every time. The verdict is that walk done by the machine: by the time a human looks, the failing stage and the affected clients are already named.

## Validation against the reference pipeline

The spec is testable because the reference pipeline's behavior is fully known:

1. **Calibration.** Run at defaults: the 2% authorization decline baseline must produce zero verdicts over a sustained window.
2. **D1 check.** Add per-client failure injection to the pipeline configuration (currently failure probability is per-stage only; a per-client override is the one extension the pipeline needs for this module), raise one client's authorization failures, expect a D1 verdict naming that client and stage while fleet-level primitives stay quiet.
3. **D2 check.** Raise settlement failure probability; expect a D2 verdict on the settlement share shift.
4. **D3 check.** Raise settlement latency range; expect a D3 verdict localizing to settlement.
5. **D4 check.** Drop the generator interval sharply; expect a D4 surge verdict. Stop the generator; expect a D4 zero-arrival verdict, the Family 1.3 signature. Raise settlement latency to 300-500 ms; the closed-loop generator slows, and after two windows expect a D4 partial-drop verdict alongside D3's.

## Non-goals

- Recovery decisions and actions. Verdicts feed the policy layer; this module never restarts, reroutes, or scales anything.
- Data-correctness reconciliation (Family 7). Different trigger model, separate module.
- Alert routing infrastructure itself. The module defines delivery requirements for verdicts; the channel is platform-specific.
