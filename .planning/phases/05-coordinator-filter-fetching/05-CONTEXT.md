# Phase 5: Coordinator Filter Fetching - Context

**Gathered:** 2026-01-09
**Status:** Ready for planning

<vision>
## How This Should Work

When the coordinator is polling workers for TaskStatus (which it already does), it notices when `outputsVersion` changes. When it detects a change, it fetches the new filters from that worker using the GET endpoint built in Phase 4, and passes them to DynamicFilterService for merging.

The key is piggybacking on existing infrastructure — no new polling loops or network patterns. Just extend what's already there (HttpRemoteTask, TaskInfoFetcher) to also handle filter fetching when versions change.

</vision>

<essential>
## What Must Be Nailed

- **Clean integration** - Fit naturally into existing HttpRemoteTask/TaskInfoFetcher patterns. This shouldn't feel like bolted-on functionality; it should feel like a natural extension of the status polling flow.
- **Prove the flow works** - Filters flow from workers to coordinator and get merged into DynamicFilterService, ready for connectors to consume.

</essential>

<boundaries>
## What's Out of Scope

- Worker-side filter application / pushing filters to probe workers (Phase 14)
- Cost-based optimizer decisions about which joins get filters (Phase 15)
- Connector integration / actually using filters for split pruning — that's Phase 6 (wiring) and Phase 7 (Iceberg)

This phase is purely about: coordinator detects filter availability, fetches it, merges it. The goal is proving the coordinator can collect filters from workers — actually using them for pruning comes in later phases.

</boundaries>

<specifics>
## Specific Ideas

No specific requirements — follow existing Presto patterns for HttpRemoteTask extension and TaskInfoFetcher integration. The roadmap mentions ContinuousTaskStatusFetcher as a reference pattern.

</specifics>

<notes>
## Additional Context

User's goal is to prove that splits can be filtered based on dynamic filters. This phase is part of that proof — getting filters from workers to coordinator. The full proof requires Phase 6 (wiring filters to connectors) and Phase 7 (Iceberg actually using them).

</notes>

---

*Phase: 05-coordinator-filter-fetching*
*Context gathered: 2026-01-09*
