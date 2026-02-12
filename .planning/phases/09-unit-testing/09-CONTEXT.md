# Phase 9: Unit Testing - Context

**Gathered:** 2026-01-09
**Status:** Ready for planning

<vision>
## How This Should Work

Critical path focus rather than chasing coverage numbers. Tests should prove the implementation works correctly across three key areas:

1. **Filter merging logic** — The TupleDomain union/intersection behavior in CoordinatorDynamicFilter
2. **Timeout and degradation** — What happens when filters don't arrive in time, graceful fallback behavior
3. **End-to-end contract** — The DynamicFilter SPI working correctly from registration through completion

The tests should follow existing Presto testing patterns and be consistent with how the codebase already does things.

</vision>

<essential>
## What Must Be Nailed

- **Correctness under edge cases** — Empty filters, single partition, many partitions all behave correctly
- Edge case behavior is the priority over raw coverage percentages
- Tests should catch real bugs, not just hit line coverage targets

</essential>

<boundaries>
## What's Out of Scope

- Integration tests with actual Iceberg tables (that's Phase 10)
- Performance testing and benchmarking
- Standard unit testing scope otherwise — no special exclusions

</boundaries>

<specifics>
## Specific Ideas

- Follow existing testing patterns in presto-main-base and presto-iceberg modules
- Match the style already used in the codebase for consistency
- No specific mocking framework requirements — use what's already in use

</specifics>

<notes>
## Additional Context

This phase follows the completed implementation work (Phases 1-8). All the core functionality is in place; this phase validates it works correctly.

Three modules to test: presto-main-base, presto-main, presto-iceberg.

</notes>

---

*Phase: 09-unit-testing*
*Context gathered: 2026-01-09*
