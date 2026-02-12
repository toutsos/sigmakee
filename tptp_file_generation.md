# TPTP File Generation Performance Improvement Plan

## Context

The TPTP generation pipeline (FOF, TFF, THF Modal, THF Plain) currently takes hours to complete,
blocking production deployment. This document catalogues identified bottlenecks and proposes
concrete optimizations as ordered, trackable milestones.

---

## Architecture Overview

| File | Role |
|------|------|
| `TPTPGenerationManager.java` | Orchestrator — 3 threads: FOF+TFF sequential, THF Modal, THF Plain |
| `SUMOKBtoTPTPKB.java` | FOF/TFF outer loop over ~40-50k formulas via `_tWriteFile()` |
| `SUMOKBtoTFAKB.java` | TFF-specific: `writeSorts()`, `printTFFNumericConstants()` |
| `SUMOformulaToTPTPformula.java` | Per-formula FOF translation (shared static `lang`, `qlist`) |
| `SUMOtoTFAform.java` | Per-formula TFF conversion — slowest component (40-60% of time) |
| `FormulaPreprocessor.java` | Per-formula preprocessing — O(n²) `winnowTypeList()` |
| `THFnew.java` | 4 full passes over formulaMap per THF file |
| `CWAUNA.java` | CWA axiom generation (only when CWA=true) |

---

## Identified Bottlenecks

### Bottleneck 1 — FOF+TFF Forced Sequential (Root Cause: Shared Static State)

**Estimated contribution: ~50% of total time**

`SUMOKBtoTPTPKB.lang` and `SUMOformulaToTPTPformula.lang` are shared static fields.
`SUMOformulaToTPTPformula.qlist` (StringBuilder) is a shared static — not thread-safe.
`SUMOtoTFAform.varmap` and `numericConstantTypes` are shared statics modified per-formula.
This forces FOF (~1h) and TFF (~1h) to run back-to-back = 2h minimum.

Relevant locations:
- `trans/SUMOKBtoTPTPKB.java:28`
- `trans/SUMOformulaToTPTPformula.java:16-17`
- `trans/SUMOtoTFAform.java:27-50`

### Bottleneck 2 — Single-Threaded Formula Loop

**Estimated contribution: ~40% of per-format time**

`_tWriteFile()` processes ~40-50k formulas in a single thread. Each formula goes through:
preProcess → renameRelations → convert → filter → write. Despite a `rapidParsing=true` flag
(line 319), the main loop remains sequential.

Relevant location:
- `trans/SUMOKBtoTPTPKB.java:536-728`

### Bottleneck 3 — `SUMOtoTFAform.process()` (40-60% of TFF time)

Called for every formula during TFF generation. Contains internal retry loops:
`elimUnitaryLogops` and `constrainFunctVars` each iterate up to 5× with string equality
checks, creating new Formula objects each iteration. `findAllTypeRestrictions()` walks the
formula tree performing KB queries.

Relevant locations:
- `trans/SUMOtoTFAform.java:2347-2417`
- Retry loops at lines 2372-2376, 2384-2388

### Bottleneck 4 — `FormulaPreprocessor.preProcess()` O(n²) (20-30% of time)

`winnowTypeList()` performs pairwise type comparison: O(v²) where v = distinct variable types.
Called for EVERY formula in EVERY format (FOF and TFF both call it independently).

Relevant location:
- `FormulaPreprocessor.java:119-144`

### Bottleneck 5 — `writeSorts()` Cartesian Product Explosion (TFF setup)

Outer loop: ~50k terms; inner: Cartesian product of numeric type suffixes. For
variable-arity numeric relations: up to 4^arity combinations generated.

Relevant location:
- `trans/SUMOKBtoTFAKB.java:619-681`

### Bottleneck 6 — 4 Full Passes Over formulaMap in THFnew

`transModalTHF()`: warm-up pass + writeTypes pass + main loop = 3 passes (~20k × 3).
`transPlainTHF()`: warm-up + types + `analyzeBadUsages()` + main loop = 4 passes.
`analyzeBadUsages()` performs recursive signature checking on every formula.

Relevant locations:
- `trans/THFnew.java:1210-1252`
- `trans/THFnew.java:1256-1297`
- `trans/THFnew.java:1149-1154`

### Bottleneck 7 — No Cross-Run Caching of Translated Formulas

`Formula.theFofFormulas` and `Formula.theTffFormulas` caches exist but are cleared at the
start of each run (`SUMOKBtoTPTPKB.java:546-551`). KB serialization exists but does not
persist formula translations across restarts.

---

## Baseline Generation Times

Measured on a full SUMO KB (~40-50k formulas) before any optimizations.

| File Type | Per-format time | Wall-clock contribution | Notes |
|-----------|-----------------|-------------------------|-------|
| FOF | ~60 min | 60 min | Runs first, single thread |
| TFF | ~60 min | 60 min | Runs after FOF completes |
| THF Modal | ~30 min | 0 min | Parallel thread, completes within FOF+TFF window |
| THF Plain | ~30 min | 0 min | Parallel thread, completes within FOF+TFF window |
| **Total wall clock** | | **~120 min** | FOF+TFF sequential is the bottleneck |

---

## Milestones

---

### Milestone 0 — Establish Profiling Baseline

**Priority: Prerequisite**
**Implements: Opt-10**

Before touching any code, confirm actual timing splits with the built-in profiler. This
prevents optimizing the wrong bottleneck.

#### Steps

- [ ] Add `-Dsigma.tff.profile=true` to the JVM arguments for the generation run
  (`trans/SUMOKBtoTPTPKB.java:844-935`)
- [ ] Execute a full SUMO KB generation run and capture the profiler output
- [ ] Record `tPreprocessNs`, `tProcessNs`, `tPrintNs` per formula and the top-20 slowest
  formulas from the summary
- [ ] Tabulate actual per-format wall-clock times (FOF, TFF, THF Modal, THF Plain)
- [ ] Compare measured times against the estimates in the Baseline table above; update any
  estimates that differ by more than 20%
- [ ] Commit the profiler output as `profiling/baseline_<date>.txt` for future comparison

#### Generation Times

| File Type | Before | After |
|-----------|--------|-------|
| FOF | ~60 min | ~60 min (no code change) |
| TFF | ~60 min | ~60 min (no code change) |
| THF Modal | ~30 min | ~30 min (no code change) |
| THF Plain | ~30 min | ~30 min (no code change) |
| **Wall clock** | **~120 min** | **~120 min** |

#### Acceptance Criteria

- Profiler output file exists with `tPreprocessNs`, `tProcessNs`, `tPrintNs` columns
- Top-20 slowest formulas are identified
- Measured total wall clock is within ±15% of the 120-minute baseline estimate, or the
  baseline table above has been updated to match actual measurements
- No code changes are introduced in this milestone

---

### Milestone 1 — Fix Thread Safety to Enable FOF/TFF Parallelism

**Priority: HIGH — unblocks all subsequent parallel work**
**Implements: Opt-1**

Shared static fields in `SUMOformulaToTPTPformula` and `SUMOtoTFAform` prevent FOF and TFF
from running concurrently. Converting them to `ThreadLocal` or method-local variables removes
the data race and allows the `GEN_LOCK` in `TPTPGenerationManager` to be dropped.

#### Steps

- [ ] In `trans/SUMOformulaToTPTPformula.java:16-17`: convert static field `lang` to a
  `ThreadLocal<Language>` or pass `lang` as a method parameter through the call chain
- [ ] In `trans/SUMOformulaToTPTPformula.java`: convert static `StringBuilder qlist` to a
  method-local variable (or `ThreadLocal<StringBuilder>`) to eliminate shared write state
- [ ] In `trans/SUMOtoTFAform.java:27-50`: convert `varmap` and `numericConstantTypes` static
  fields to `ThreadLocal` equivalents
- [ ] In `trans/SUMOKBtoTPTPKB.java:28`: audit remaining static fields; convert any that are
  written during formula translation to `ThreadLocal` or method parameters
- [ ] In `trans/TPTPGenerationManager.java`: remove or relax `GEN_LOCK` so that the FOF
  thread and TFF thread are submitted to the executor without waiting for each other
- [ ] Run `mvn test -pl . -Dtest=*TPTP*,*TFF*,*THF*` — all tests must pass
- [ ] Run a full generation with `-Dsigma.tff.profile=true` and diff FOF/TFF outputs against
  the Milestone 0 reference files to confirm identical output

#### Generation Times

| File Type | Before (M0) | After (M1) |
|-----------|-------------|------------|
| FOF | ~60 min | ~60 min (per-format time unchanged) |
| TFF | ~60 min | ~60 min (per-format time unchanged) |
| THF Modal | ~30 min | ~30 min |
| THF Plain | ~30 min | ~30 min |
| **Wall clock** | **~120 min** | **~60 min** |

FOF and TFF now run on separate threads simultaneously; total wall clock drops from 120 min
to 60 min. THF completes within that window and is no longer the bottleneck.

#### Acceptance Criteria

- `mvn test -pl . -Dtest=*TPTP*,*TFF*,*THF*` passes with zero failures
- `diff` of generated FOF and TFF files against Milestone 0 reference shows no differences
- No `synchronized` block or lock prevents FOF and TFF from starting concurrently
- Profiler confirms wall-clock time is ≤65 min (≤120 min × 0.55)
- No `ThreadLocal` leaks: each thread cleans up its `ThreadLocal` state after generation

---

### Milestone 2 — Parallelize the Formula Processing Loop

**Priority: HIGH — largest single per-format speedup**
**Implements: Opt-2**
**Prerequisite: Milestone 1 must be complete**

The inner loop in `_tWriteFile()` processes ~40-50k formulas one at a time. Each formula is
independently translatable, making this an embarrassingly parallel task. A `parallelStream()`
or `ForkJoinPool` over `orderedFormulae` will distribute work across all available cores.

#### Steps

- [ ] In `trans/SUMOKBtoTPTPKB.java:536-728`: identify the boundaries of the per-formula
  loop and confirm there are no shared mutable structures written inside it (beyond
  `fileContents`)
- [ ] Replace the sequential `for` loop over `orderedFormulae` with a `parallelStream()`
  collecting translated strings into a `ConcurrentLinkedQueue<String>` (preserving order
  with indexed mapping if needed)
- [ ] After the parallel collection phase, drain the queue into `fileContents` sequentially
  to preserve the existing single-pass file write
- [ ] Bound the thread pool to `Runtime.getRuntime().availableProcessors()` to avoid
  over-subscription with the THF threads running in parallel
- [ ] Verify that all per-formula helper objects (`SUMOformulaToTPTPformula`,
  `SUMOtoTFAform`) are instantiated inside the lambda (not shared across threads)
- [ ] Run `mvn test -pl . -Dtest=*TPTP*,*TFF*,*THF*` — all tests must pass
- [ ] Diff generated files against Milestone 1 reference output — must be identical
- [ ] Run with `-Dsigma.tff.profile=true` and record new wall-clock time

#### Generation Times

| File Type | Before (M1) | After (M2) |
|-----------|-------------|------------|
| FOF | ~60 min | ~10 min (~6× speedup from parallel processing) |
| TFF | ~60 min | ~10 min (~6× speedup from parallel processing) |
| THF Modal | ~30 min | ~30 min (not yet optimized) |
| THF Plain | ~30 min | ~30 min (not yet optimized) |
| **Wall clock** | **~60 min** | **~30 min** |

FOF and TFF each drop from ~60 min to ~10 min. THF (30 min) becomes the new wall-clock
bottleneck.

#### Acceptance Criteria

- `mvn test -pl . -Dtest=*TPTP*,*TFF*,*THF*` passes with zero failures
- `diff` of all four generated files against Milestone 1 reference shows no differences
- Profiler confirms FOF wall-clock time ≤15 min (≤60 min × 0.25)
- Profiler confirms TFF wall-clock time ≤15 min (≤60 min × 0.25)
- Total wall clock is ≤35 min
- No `OutOfMemoryError` or data-race exceptions observed across 3 consecutive runs

---

### Milestone 3 — Incremental/Delta Generation

**Priority: HIGH — eliminates redundant work for the common case**
**Implements: Opt-3**

Base SUMO KIF files change rarely; only user assertion (`ua/`) files change between sessions.
Running a full generation on every deployment is wasteful. A file-level content hash allows
the pipeline to skip formulas from unchanged files entirely.

#### Steps

- [ ] Extend the existing `infFileOld()` helper (or create a new `isSourceChanged()` utility)
  to compute a SHA-256 hash of each constituent KIF file and compare it against a stored
  manifest (`kbmanager.ser` or a separate `tptp_hash_manifest.json`)
- [ ] At the start of `_tWriteFile()`: partition `orderedFormulae` into `changedFormulae`
  (source file hash changed) and `cachedFormulae` (hash unchanged)
- [ ] For `cachedFormulae`: load the previously translated string directly from the cache
  store; skip `preProcess`, `convert`, and `filter`
- [ ] For `changedFormulae`: run the normal translation pipeline and update the cache entry
- [ ] Persist the translated formula strings alongside the KB serialization; add a cache
  invalidation step that clears all entries if the SUMO version string changes
- [ ] Add a `--full-regen` CLI flag (or system property `sigma.tptp.fullRegen=true`) to
  bypass the cache and force complete regeneration
- [ ] Run `mvn test -pl . -Dtest=*TPTP*,*TFF*,*THF*` — all tests must pass
- [ ] Validate: generate once (cold), modify one `ua/` file, generate again, and confirm only
  the affected formulas are retranslated (check via profiler formula count)

#### Generation Times

| File Type | Before (M2) | After M3 — full regen | After M3 — UA-only change |
|-----------|-------------|----------------------|--------------------------|
| FOF | ~10 min | ~10 min | ~1 min |
| TFF | ~10 min | ~10 min | ~1 min |
| THF Modal | ~30 min | ~30 min | ~3 min |
| THF Plain | ~30 min | ~30 min | ~3 min |
| **Wall clock** | **~30 min** | **~30 min** | **~3 min** |

Full regeneration time is unchanged. For the typical deploy scenario (UA-only change),
wall clock drops from ~30 min to ~3 min (~90% reduction).

#### Acceptance Criteria

- `mvn test -pl . -Dtest=*TPTP*,*TFF*,*THF*` passes with zero failures
- Cold-start full generation produces files identical to Milestone 2 reference
- After modifying a single `ua/` file: warm-start generation completes in ≤5 min
- Profiler confirms formula retranslation count on warm-start equals only the formulas from
  the changed file, not the full 40-50k
- `--full-regen` / `sigma.tptp.fullRegen=true` triggers a complete regeneration regardless
  of cache state
- Cache entries are invalidated when the SUMO version string changes

---

### Milestone 4 — Share Preprocessing Results Between FOF and TFF

**Priority: MEDIUM — low-risk, eliminates redundant work**
**Implements: Opt-4**

`preProcess()` is called independently for every formula during FOF generation and again
during TFF generation. The results are identical. Running it once and reusing the output
cuts preprocessing overhead by half.

#### Steps

- [ ] Add a `preprocessedFormulae` map (`Formula → List<Formula>`) as a field on
  `SUMOKBtoTPTPKB` (or stored on the `Formula` object itself)
- [ ] In the FOF pass of `_tWriteFile()`: after calling `preProcess()`, store the result in
  `preprocessedFormulae` keyed by the original formula
- [ ] In the TFF pass: before calling `preProcess()`, check `preprocessedFormulae`; if a
  cached result exists, use it directly and skip the `preProcess()` call
- [ ] Ensure the cache is cleared between full regeneration runs (invalidated at the start of
  each `generateProperFile()` invocation)
- [ ] Run `mvn test -pl . -Dtest=*TPTP*,*TFF*,*THF*` — all tests must pass
- [ ] Diff TFF output against Milestone 3 reference — must be identical

#### Generation Times

| File Type | Before (M3) | After (M4) |
|-----------|-------------|------------|
| FOF | ~10 min | ~8 min (~20% preprocessing overhead eliminated) |
| TFF | ~10 min | ~8 min (~20% preprocessing overhead eliminated) |
| THF Modal | ~30 min | ~30 min (unaffected) |
| THF Plain | ~30 min | ~30 min (unaffected) |
| **Wall clock** | **~30 min** | **~30 min** |

FOF and TFF each improve by ~2 min. Wall clock remains ~30 min because THF is still the
bottleneck, but each format individually is faster.

#### Acceptance Criteria

- `mvn test -pl . -Dtest=*TPTP*,*TFF*,*THF*` passes with zero failures
- `diff` of all four generated files against Milestone 3 reference shows no differences
- Profiler confirms `tPreprocessNs` for TFF is ≤10% of its Milestone 3 value (near zero,
  since the result is loaded from cache)
- FOF wall-clock time ≤9 min; TFF wall-clock time ≤9 min

---

### Milestone 5 — Merge `analyzeBadUsages()` into the THF Translation Loop

**Priority: MEDIUM — straightforward refactor for significant THF gain**
**Implements: Opt-6**

`transPlainTHF()` currently makes a dedicated pass over all formulas to populate the
`badUsages` set before the main translation loop begins. Integrating this check directly into
`oneTransNonModal()` eliminates one full traversal (~20k formulas) of the formula map.

#### Steps

- [ ] Read and understand `analyzeBadUsages()` at `trans/THFnew.java:1149-1206` — document
  what it populates and which data structure downstream code reads
- [ ] In `oneTransNonModal()` (called during the main translation loop): add the
  bad-usage detection logic inline, writing to the same `badUsages` data structure
- [ ] Remove the standalone `analyzeBadUsages()` pre-pass call at `trans/THFnew.java:1277`
- [ ] Verify that the `badUsages` set is fully populated before any formula that reads it is
  translated (if ordering matters, collect in a first sub-pass within the main loop using a
  two-phase approach within a single iteration)
- [ ] Apply the same analysis to `transModalTHF()` if it has an equivalent pre-pass
- [ ] Run `mvn test -pl . -Dtest=*TPTP*,*TFF*,*THF*` — all tests must pass
- [ ] Diff THF Modal and THF Plain outputs against Milestone 4 reference — must be identical

#### Generation Times

| File Type | Before (M4) | After (M5) |
|-----------|-------------|------------|
| FOF | ~8 min | ~8 min (unaffected) |
| TFF | ~8 min | ~8 min (unaffected) |
| THF Modal | ~30 min | ~22 min (~25% reduction; one full pass eliminated) |
| THF Plain | ~30 min | ~22 min (~25% reduction; one full pass eliminated) |
| **Wall clock** | **~30 min** | **~22 min** |

THF Modal and THF Plain each drop from ~30 min to ~22 min. Wall clock improves from ~30 min
to ~22 min as THF is now no longer waiting on the extra pass.

#### Acceptance Criteria

- `mvn test -pl . -Dtest=*TPTP*,*TFF*,*THF*` passes with zero failures
- `diff` of THF Modal and THF Plain files against Milestone 4 reference shows no differences
- Profiler confirms THF Modal wall-clock time ≤25 min (≤30 min × 0.83)
- Profiler confirms THF Plain wall-clock time ≤25 min
- Total wall clock is ≤25 min

---

### Milestone 6 — Algorithmic Fixes: `winnowTypeList()` + `writeSorts()` Cache

**Priority: MEDIUM — two independent algorithmic improvements**
**Implements: Opt-5 + Opt-7**

Two separate algorithmic inefficiencies contribute measurable overhead:
1. `winnowTypeList()` in `FormulaPreprocessor` runs O(v²) pairwise comparisons per formula
2. `writeSorts()` in `SUMOKBtoTFAKB` recomputes Cartesian products over numeric type suffixes
   for every relation on every run

#### Steps — Opt-5: Fix `winnowTypeList()`

- [ ] Read `FormulaPreprocessor.java:119-144` and document the current pairwise comparison
  logic
- [ ] Retrieve the type hierarchy from `kbCache` and sort candidate types topologically
- [ ] Replace the O(v²) nested loop with a single-pass dominated-type elimination using the
  sorted order: for each type, check only its ancestors (already seen in sorted order)
- [ ] Verify the output of `winnowTypeList()` is identical before and after the change using
  a unit test that compares results on a known formula set

#### Steps — Opt-7: Cache `writeSorts()` Cartesian Products

- [ ] Read `trans/SUMOKBtoTFAKB.java:619-681` and identify the signature of
  `processRelationSort()`
- [ ] Add a `HashMap<String, List<String>>` cache field (keyed on `relation + ":" + signature`
  string) to `SUMOKBtoTFAKB`
- [ ] Wrap the existing `processRelationSort()` body: check the cache first; compute and store
  on miss
- [ ] Restrict computation to relations that actually appear in the translated formula set
  (skip relations present in the KB but absent from any formula)
- [ ] Clear the cache at the start of each full regeneration run

#### Steps — Integration

- [ ] Run `mvn test -pl . -Dtest=*TPTP*,*TFF*,*THF*` — all tests must pass
- [ ] Diff all four generated files against Milestone 5 reference — must be identical
- [ ] Record new wall-clock times with `-Dsigma.tff.profile=true`

#### Generation Times

| File Type | Before (M5) | After (M6) |
|-----------|-------------|------------|
| FOF | ~8 min | ~7 min (winnowTypeList improvement applies) |
| TFF | ~8 min | ~5 min (both winnowTypeList + writeSorts cache apply) |
| THF Modal | ~22 min | ~22 min (unaffected) |
| THF Plain | ~22 min | ~22 min (unaffected) |
| **Wall clock** | **~22 min** | **~22 min** |

TFF drops from ~8 min to ~5 min; the `writeSorts()` Cartesian product that previously took
several minutes now completes in seconds on warm cache. FOF improves slightly from the
`winnowTypeList()` fix. Wall clock is unchanged because THF (22 min) remains the bottleneck.

#### Acceptance Criteria

- `mvn test -pl . -Dtest=*TPTP*,*TFF*,*THF*` passes with zero failures
- `diff` of all four generated files against Milestone 5 reference shows no differences
- Profiler confirms TFF wall-clock time ≤6 min (≤8 min × 0.75)
- `processRelationSort()` cache hit rate ≥95% on a warm run (loggable via a counter)
- `winnowTypeList()` unit test confirms output is identical to the pre-change implementation

---

### Milestone 7 — Reduce Object Allocation and Increase I/O Buffer Size

**Priority: LOW — final polish, diminishing returns**
**Implements: Opt-8 + Opt-9**

Two remaining low-effort improvements: reducing transient `Formula` object creation in retry
loops, and widening the `BufferedWriter` buffer to reduce system call frequency.

#### Steps — Opt-8: Reduce Object Allocation in Retry Loops

- [ ] In `trans/SUMOtoTFAform.java:2372-2376`: instrument the `elimUnitaryLogops` retry loop
  to count how many iterations occur on average per formula (add a debug counter)
- [ ] Replace the string-equality convergence check (`formula.equals(prev)`) with a
  structural/parse-tree equality check to avoid constructing intermediate `Formula` objects
  when the formula has not changed
- [ ] If the loop always converges in ≤2 iterations in practice, replace the retry loop with
  a fixed two-pass unroll to eliminate loop overhead entirely
- [ ] Apply the same analysis to the `constrainFunctVars` retry loop at lines 2384-2388

#### Steps — Opt-9: Larger I/O Buffers

- [ ] Locate the `BufferedWriter` instantiation(s) used to write the final output files in
  `trans/SUMOKBtoTPTPKB.java` and `trans/THFnew.java`
- [ ] Increase the buffer size argument from the default (8,192 bytes) to 4,194,304 bytes
  (4 MB): `new BufferedWriter(new FileWriter(path), 4 * 1024 * 1024)`
- [ ] Confirm no intermediate `flush()` calls interfere with the larger buffer (the existing
  single-batch write pattern via `fileContents` is already optimal)

#### Steps — Integration

- [ ] Run `mvn test -pl . -Dtest=*TPTP*,*TFF*,*THF*` — all tests must pass
- [ ] Diff all four generated files against Milestone 6 reference — must be identical
- [ ] Record final wall-clock times with `-Dsigma.tff.profile=true`

#### Generation Times

| File Type | Before (M6) | After (M7) |
|-----------|-------------|------------|
| FOF | ~7 min | ~6.5 min (~7% improvement from allocation + I/O) |
| TFF | ~5 min | ~4.5 min (~10% improvement) |
| THF Modal | ~22 min | ~21 min (~5% I/O improvement) |
| THF Plain | ~22 min | ~21 min (~5% I/O improvement) |
| **Wall clock** | **~22 min** | **~21 min** |

#### Acceptance Criteria

- `mvn test -pl . -Dtest=*TPTP*,*TFF*,*THF*` passes with zero failures
- `diff` of all four generated files against Milestone 6 reference shows no differences
- No `OutOfMemoryError` observed with the larger buffer across 3 consecutive runs on the
  deployment machine
- TFF wall-clock time ≤5 min; THF wall-clock time ≤22 min

---

## Cumulative Improvement Summary

| Milestone | FOF | TFF | THF Modal | THF Plain | Wall clock | Key change |
|-----------|-----|-----|-----------|-----------|-----------|------------|
| Baseline | 60 min | 60 min | 30 min | 30 min | **120 min** | — |
| M0 — Profiling | 60 min | 60 min | 30 min | 30 min | **120 min** | Measurement only |
| M1 — Thread safety | 60 min | 60 min | 30 min | 30 min | **60 min** | FOF+TFF now parallel |
| M2 — Parallel loop | 10 min | 10 min | 30 min | 30 min | **30 min** | ~6× per-format speedup |
| M3 — Incremental | 1 min* | 1 min* | 3 min* | 3 min* | **3 min*** | *UA-only change |
| M4 — Share preProcess | 8 min | 8 min | 30 min | 30 min | **30 min** | Duplicate preProcess gone |
| M5 — Merge THF pass | 8 min | 8 min | 22 min | 22 min | **22 min** | One THF pass eliminated |
| M6 — Algorithmic fixes | 7 min | 5 min | 22 min | 22 min | **22 min** | writeSorts + winnowTypeList |
| M7 — Alloc + I/O | 6.5 min | 4.5 min | 21 min | 21 min | **21 min** | Final polish |

*Incremental (UA-only) times. Full regeneration time follows the row above (M4 values).

**Overall improvement (full regen): 120 min → 21 min (~82% reduction)**
**Overall improvement (UA-only incremental): 120 min → ~3 min (~97.5% reduction)**

---

## Critical Files to Modify

| File | Change |
|------|--------|
| `trans/SUMOformulaToTPTPformula.java` | Make `lang` and `qlist` ThreadLocal or parameterized |
| `trans/SUMOtoTFAform.java` | Make `varmap`, `numericConstantTypes` ThreadLocal |
| `trans/SUMOKBtoTPTPKB.java` | Parallelize `_tWriteFile()` main loop; add preprocessing cache |
| `trans/SUMOKBtoTFAKB.java` | Cache Cartesian products in `writeSorts()` |
| `trans/THFnew.java` | Merge `analyzeBadUsages()` into main translation loop |
| `trans/TPTPGenerationManager.java` | Remove `GEN_LOCK` forcing FOF/TFF sequential |
| `FormulaPreprocessor.java` | Fix `winnowTypeList()` O(n²) → O(n log n) |

---

## Verification Plan

1. Enable `-Dsigma.tff.profile=true` before and after each milestone; compare timing summaries.
2. Validate output files are byte-for-byte identical: `diff <before> <after>` for all 4
   format types (FOF, TFF, THF Modal, THF Plain).
3. Run the existing test suite after every milestone:
   ```
   mvn test -pl . -Dtest=*TPTP*,*TFF*,*THF*
   ```
4. Save profiler output to `profiling/<milestone>_<date>.txt` for regression tracking.
5. Benchmark total generation time for a full SUMO KB run after each milestone and update
   the Cumulative Improvement Summary table with actual measured values.
