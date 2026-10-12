# Self-Healing Transaction Patterns

[![CI](https://github.com/pawanpandey407/self-healing-transaction-patterns/actions/workflows/ci.yml/badge.svg)](https://github.com/pawanpandey407/self-healing-transaction-patterns/actions/workflows/ci.yml)

A standardization framework and reference implementation for self-healing financial transaction architectures.

**Author:** Pawan Pandey

## Quick start

See detection and recovery work end to end in about three minutes. Requires Java 17+ and Maven.

```
cd reference-impl/transaction-pipeline
mvn spring-boot:run -Dspring-boot.run.arguments="--pipeline.generator.interval-ms=50 \
  --pipeline.stages.validation.min-latency-ms=1 --pipeline.stages.validation.max-latency-ms=3 \
  --pipeline.stages.authorization.min-latency-ms=4 --pipeline.stages.authorization.max-latency-ms=12 \
  --pipeline.stages.settlement.min-latency-ms=2 --pipeline.stages.settlement.max-latency-ms=6 \
  --detection.window-ms=5000 --detection.warmup-windows=6 --recovery.probe-interval-ms=1000"
```

In a second terminal, wait about a minute for detection to learn a healthy baseline, then break one client's authorization path:

```
curl -X POST "localhost:8080/inject/stage/authorization/client/client-3?probability=0.5"
curl localhost:8080/recovery
```

Within a couple of windows, detection names client-3 and recovery isolates it: its new transactions are held in order while the other four clients keep flowing. Now heal the path:

```
curl -X DELETE "localhost:8080/inject/stage/authorization/client/client-3"
curl localhost:8080/recovery
```

Probes confirm the path is healthy, the held backlog replays in order, and the episode shows `RELEASED` with `duplicateCompletions` at zero. `curl localhost:8080/verdicts` shows what detection saw, and `curl localhost:8080/stats` shows live traffic, probes, and replays counted separately. The pipeline README explains each piece: `reference-impl/transaction-pipeline/README.md`.

## The problem

Most financial transaction infrastructure operates on a reactive model: a component fails, transactions are lost or delayed, humans get paged, and the fix happens after the damage. Payment systems are critical infrastructure. As instant payment rails (FedNow, RTP) remove the overnight maintenance window entirely, "detect and recover faster than a human can intervene" stops being a nice-to-have and becomes the design requirement.

This project documents and implements architecture patterns that let transaction pipelines:

1. **Detect** anomalies in transaction flow (node failures, latency degradation, volume surges) from telemetry, before they become outages
2. **Decide** using codified policies instead of a runbook and a pager
3. **Recover** automatically: re-route around failing nodes, shed or buffer load, roll back bad deployments, restore steady state without manual intervention

## Repository layout

```
docs/
  failure-mode-taxonomy.md    Taxonomy of failure modes in card transaction pipelines
  detection-module.md         Detection primitives, learned baselines, and verdicts
  recovery-module.md          Recovery actions and autonomy tiers (R2 and R5 implemented)
  patterns/                   One doc per self-healing pattern (detection signal -> decision -> recovery action)
  lessons/                    Write-ups of what building the reference pipeline taught, starting with D1's false positives
reference-impl/
  transaction-pipeline/       Transaction pipeline (Java / Spring Boot): synthetic generator, three
                              stages, per-client failure injection, the detection module, and
                              client isolation with safe replay
```

## Status and roadmap

This is an active work in progress.

- [x] Failure-mode taxonomy for card transaction pipelines (v0, see `docs/failure-mode-taxonomy.md`)
- [ ] Pattern catalog: one document per failure mode family
- [x] Reference pipeline skeleton (Spring Boot, see `reference-impl/transaction-pipeline/`)
- [x] Detection module: latency and error-rate anomaly signals (spec in `docs/detection-module.md`, running in the reference pipeline)
- [ ] Recovery module: automated re-route and failover demonstration (partly implemented: R2 client isolation and R5 safe replay run in the reference pipeline, see `docs/recovery-module.md`)
- [ ] Failure injection scenarios reproducing each taxonomy entry

## Scope and non-goals

Everything here is original work based on general, publicly known engineering principles. It contains no proprietary code, data, or architecture details from any employer. The reference implementation is a demonstration system, not a production payment processor: it processes synthetic transactions only.

## License

Apache License 2.0
