------------------------- MODULE DynamicPartitionPruning -------------------------
(*
 * Models the build-side -> coordinator collection path of distributed dynamic
 * partition pruning. Covers:
 *
 *   - Per-driver finalization within a task
 *   - Per-task finalization gate before the coordinator may merge
 *   - Cross-worker union ("UNION semantics" for partitioned joins)
 *   - All-or-nothing publishing (no probe sees a partial filter as complete)
 *   - Timeout fallback to TupleDomain.all()
 *
 * Out of scope (deliberately): scheduler deadlock prevention, Phase-2 worker
 * push, DELETE?through=N idempotency, worker restart / network partition.
 * Add those once this layer is clean.
 *)
EXTENDS Naturals, FiniteSets, TLC

CONSTANTS
    Workers,            \* e.g. {w1, w2}
    Drivers,            \* e.g. {d1, d2} -- driver IDs within each task
    Values,             \* finite value domain, e.g. {1, 2, 3}
    ExpectedPartitions  \* coordinator's gate: Cardinality(Workers) for PARTITIONED, 1 for BROADCAST

ASSUME ExpectedPartitions \in 1..Cardinality(Workers)

\* What each driver sees on its build side. Defined here (rather than as a
\* CONSTANT) because TLC .cfg files don't parse nested records reliably.
\* Edit this to explore different distributions; the spec is otherwise generic
\* in Workers/Drivers/Values. The default assumes Workers and Drivers from
\* the .cfg are {w1, w2} and {d1, d2} respectively.
BuildData == [w \in Workers |-> [d \in Drivers |->
    IF w = "w1" /\ d = "d1" THEN {1}
    ELSE IF w = "w1" /\ d = "d2" THEN {2}
    ELSE IF w = "w2" /\ d = "d1" THEN {2}
    ELSE IF w = "w2" /\ d = "d2" THEN {3}
    ELSE {}
]]

VARIABLES
    driverState,        \* [Workers -> [Drivers -> {"running", "finalized"}]]
    taskFinalized,      \* [Workers -> BOOLEAN]
    received,           \* [Workers -> BOOLEAN] -- coordinator has merged this task
    mergedFilter,       \* SUBSET Values
    filterState,        \* {"collecting", "complete", "timedOut"}
    probeView           \* {"none", "all", "filter"} -- last observation by probe

vars == <<driverState, taskFinalized, received, mergedFilter, filterState, probeView>>

TaskFilter(w) == UNION { BuildData[w][d] : d \in Drivers }
TrueGlobalFilter == UNION { TaskFilter(w) : w \in Workers }

TypeOK ==
    /\ driverState \in [Workers -> [Drivers -> {"running", "finalized"}]]
    /\ taskFinalized \in [Workers -> BOOLEAN]
    /\ received \in [Workers -> BOOLEAN]
    /\ mergedFilter \subseteq Values
    /\ filterState \in {"collecting", "complete", "timedOut"}
    /\ probeView \in {"none", "all", "filter"}

Init ==
    /\ driverState = [w \in Workers |-> [d \in Drivers |-> "running"]]
    /\ taskFinalized = [w \in Workers |-> FALSE]
    /\ received = [w \in Workers |-> FALSE]
    /\ mergedFilter = {}
    /\ filterState = "collecting"
    /\ probeView = "none"

(*--------------------------- Actions ---------------------------*)

FinalizeDriver(w, d) ==
    /\ driverState[w][d] = "running"
    /\ driverState' = [driverState EXCEPT ![w][d] = "finalized"]
    /\ UNCHANGED <<taskFinalized, received, mergedFilter, filterState, probeView>>

FinalizeTask(w) ==
    /\ ~taskFinalized[w]
    /\ \A d \in Drivers : driverState[w][d] = "finalized"
    /\ taskFinalized' = [taskFinalized EXCEPT ![w] = TRUE]
    /\ UNCHANGED <<driverState, received, mergedFilter, filterState, probeView>>

CoordinatorMerge(w) ==
    /\ filterState = "collecting"
    /\ taskFinalized[w]                 \* per-task finalization gate (242e568ff7f)
    /\ ~received[w]
    /\ received' = [received EXCEPT ![w] = TRUE]
    /\ mergedFilter' = mergedFilter \cup TaskFilter(w)   \* UNION semantics
    /\ UNCHANGED <<driverState, taskFinalized, filterState, probeView>>

CoordinatorComplete ==
    /\ filterState = "collecting"
    /\ Cardinality({w \in Workers : received[w]}) >= ExpectedPartitions
    /\ filterState' = "complete"
    /\ UNCHANGED <<driverState, taskFinalized, received, mergedFilter, probeView>>

Timeout ==
    /\ filterState = "collecting"
    /\ filterState' = "timedOut"
    /\ UNCHANGED <<driverState, taskFinalized, received, mergedFilter, probeView>>

ProbeObserve ==
    /\ \/ /\ filterState = "complete"   /\ probeView' = "filter"
       \/ /\ filterState = "timedOut"   /\ probeView' = "all"
       \/ /\ filterState = "collecting" /\ probeView' = "all"
    /\ UNCHANGED <<driverState, taskFinalized, received, mergedFilter, filterState>>

Next ==
    \/ \E w \in Workers, d \in Drivers : FinalizeDriver(w, d)
    \/ \E w \in Workers : FinalizeTask(w)
    \/ \E w \in Workers : CoordinatorMerge(w)
    \/ CoordinatorComplete
    \/ Timeout
    \/ ProbeObserve

Fairness ==
    /\ \A w \in Workers, d \in Drivers : WF_vars(FinalizeDriver(w, d))
    /\ \A w \in Workers : WF_vars(FinalizeTask(w))
    /\ \A w \in Workers : WF_vars(CoordinatorMerge(w))
    /\ WF_vars(CoordinatorComplete)
    \* Note: Timeout is intentionally NOT under fairness -- it's an adversarial action.

Spec == Init /\ [][Next]_vars /\ Fairness

(*--------------------------- Invariants ---------------------------*)

\* When the coordinator publishes complete, the merged filter covers every
\* build-side value. This is the property b316373337e was protecting:
\* if a single driver's min/max is used instead of expanding across drivers,
\* TaskFilter(w) would be incomplete and Soundness fails.
Soundness ==
    filterState = "complete" => mergedFilter = TrueGlobalFilter

\* The probe never observes "complete" with fewer than ExpectedPartitions
\* contributions. This is d9f78747308 + 6c07185b097.
AllOrNothing ==
    filterState = "complete" =>
        Cardinality({w \in Workers : received[w]}) >= ExpectedPartitions

\* A task's contribution is only merged after it has finalized.
\* This is 242e568ff7f (per-task finalization gate).
NoEarlyMerge ==
    \A w \in Workers : received[w] => taskFinalized[w]

\* A task only finalizes when all its drivers have finalized.
\* This is 0b4057e9e11 (dropping the per-driver isFinal wrapper relies on
\* the property that task-level finalization implies driver-level completion).
NoEarlyTaskFinalize ==
    \A w \in Workers :
        taskFinalized[w] => \A d \in Drivers : driverState[w][d] = "finalized"

\* A timed-out filter must look like TupleDomain.all() to the probe -- never
\* leak the partial mergedFilter.
TimeoutSafety ==
    filterState = "timedOut" => probeView /= "filter"

StutterIfTerminal ==
    filterState \in {"complete", "timedOut"} => filterState' = filterState

MonotonicTerminal ==
    [][StutterIfTerminal]_vars

(*--------------------------- Liveness ---------------------------*)

\* Without timeouts firing, the filter eventually completes.
EventuallyTerminates ==
    <>(filterState \in {"complete", "timedOut"})

(*--------------------------- Next steps ---------------------------
 *
 * Run:
 *     cd tla && java -cp ~/tla2tools.jar tlc2.TLC -workers auto DynamicPartitionPruning
 *
 * Things to try, in roughly increasing order of payoff:
 *
 * 1. Tweak BuildData (above) or grow Workers/Drivers in the .cfg to explore
 *    different distributions. TLC re-exhausts quickly at this scale.
 *
 * 2. Confirm each invariant has teeth by deliberately breaking the
 *    corresponding guard and watching TLC produce a counterexample:
 *      - Remove `taskFinalized[w]` in CoordinatorMerge   -> NoEarlyMerge fires
 *      - Remove the driver-finalized check in FinalizeTask -> NoEarlyTaskFinalize
 *      - Lower `>= ExpectedPartitions` in CoordinatorComplete -> AllOrNothing
 *      - Make TaskFilter return BuildData[w][d1] only (ignore d2) -> Soundness
 *      - Let ProbeObserve return "filter" while timedOut -> TimeoutSafety
 *    Revert after each, of course.
 *
 * 3. Add range mode: replace SUBSET Values with {min,max} pairs plus an
 *    overflow flag; merge becomes min-of-mins / max-of-maxes. Catches the
 *    same cross-driver bug class in the range path. Cheap to add.
 *
 * 4. Add scheduler / deadlock prevention: introduce taskScheduled[w] and a
 *    probe `splitSourceBlocked` flag. Build drivers can't run until scheduled;
 *    probe blocks on filterState = "collecting". Add a ForceScheduleBuild
 *    action gated on the deadlock condition, and a liveness property that
 *    the probe is never permanently blocked. This is where TLC tends to find
 *    real bugs -- the interaction of "probe waits for filter" and "build
 *    can't run until scheduled" is exactly the cycle the RFC calls out.
 *
 * 5. Add DELETE?through=N idempotency: model the fetcher as a sequence number
 *    and a separate `acked` map. Add Lossy/Retry actions. Check that
 *    double-delivery doesn't double-merge.
 *
 * 6. Add worker failure: let a task transition to `failed` mid-finalization
 *    and assert the filter still reaches complete or timedOut (never wedged).
 *
 * Resist adding everything at once. State space grows fast; the diagnostic
 * value of a counterexample drops sharply once the spec covers more than one
 * subsystem. One module per concern, INSTANCE them together later if needed.
 *)

================================================================================