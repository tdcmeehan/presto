# Phase 10: Integration Testing - Context

**Gathered:** 2026-01-10
**Status:** Ready for planning

<vision>
## How This Should Work

TPC-DS focused integration testing. Run specific TPC-DS queries (Q5, Q17, Q25) that are known to benefit from dynamic partition pruning and validate that pruning actually happens.

The key outcome is debugging visibility — tests should make it crystal clear what DPP is doing. When a test runs, you should be able to see exactly:
- Which partitions/files were examined
- Which were pruned
- What the filter values were
- That results match with DPP on vs off

This is about proving the feature works correctly and provides clear evidence of its behavior, not about performance benchmarking.

</vision>

<essential>
## What Must Be Nailed

- **Debugging visibility** — Clear metrics/logs showing exactly what DPP is doing in each query. When a test runs, you should immediately understand what happened.
- **Pruning proof** — Metrics demonstrating we actually pruned partitions/files (the >30% reduction goal from roadmap)
- **Result correctness** — Identical results with DPP enabled vs disabled — no data loss or corruption

</essential>

<boundaries>
## What's Out of Scope

- Performance benchmarks — just prove it works and prunes correctly; wall-clock perf tuning is future work (Phase 16)
- Scale testing — skip large data volumes; focus on correctness at reasonable scale
- Non-Iceberg connectors — only test Iceberg; other connectors are v1.0 work
- Regression testing for non-DPP queries — deferred to Phase 16 Production Testing

</boundaries>

<specifics>
## Specific Ideas

- Use TPC-DS Q5, Q17, Q25 as the primary test queries (per roadmap)
- Tests should output/assert on the RuntimeStats metrics we added in Phase 8 (DYNAMIC_FILTER_SPLITS_EXAMINED, DYNAMIC_FILTER_SPLITS_PRUNED, DYNAMIC_FILTER_WAIT_TIME_NANOS)
- Compare query results with session property enabled vs disabled to prove correctness

</specifics>

<notes>
## Additional Context

This is the final phase of the v0.1 Java Prototype milestone. Success here means the prototype is validated and ready for the v1.0 C++/Velox production work.

The existing unit tests in Phase 9 cover component-level behavior. This phase is about end-to-end validation with real Iceberg tables.

</notes>

---

*Phase: 10-integration-testing*
*Context gathered: 2026-01-10*
