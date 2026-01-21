# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

SigmaKEE (Sigma Knowledge Engineering Environment) is a Java-based IDE for developing and managing logical theories that extend SUMO (Suggested Upper Merged Ontology). It provides web-based ontology browsing, editing, querying, and automated theorem proving capabilities.

**Key Technologies:** Java 21+, Apache Tomcat 9, JSP, Apache Ant with Ivy

## Build Commands

```bash
# Full clean build (recommended)
ant all

# Compile only
ant compile

# Build WAR and JAR without running tests
ant dist

# Run all tests (unit + integration)
ant test

# Run only unit tests
ant test.unit

# Run only integration tests
ant test.integration

# Clean build artifacts
ant clean

# Full installation (first time setup - installs Tomcat, theorem provers, SUMO KB)
ant install
```

**Running the Application:**
```bash
startup.sh                      # Start Tomcat
# Access at http://localhost:8080/sigma/login.html
# Default credentials: admin/admin
```

## Running Individual Tests

Tests use JUnit 4 with test suites. To run a specific test class:

```bash
# Run a single test class via Ant (requires compile.test first)
ant compile.test
java -Xmx10g -cp "build/classes:build/test/classes:lib/*" org.junit.runner.JUnitCore com.articulate.sigma.YourTestClass
```

Test configuration files in `test/unit/java/resources/` and `test/integration/java/resources/` are auto-updated with user paths via the `prepare.test.configs` Ant target.

## Architecture

### Core Packages (`src/java/com/articulate/sigma/`)

- **KB.java** - Central knowledge base class; handles loading, querying, and inference. Entry point via `main()`.
- **Formula.java** - Represents logical formulas in KIF (Knowledge Interchange Format)
- **FormulaPreprocessor.java** - Type inference and formula analysis
- **KBcache.java** - Caches KB lookups for performance
- **KBmanager.java** - Manages KB instances and configuration
- **Clausifier.java** - Converts formulas to clausal normal form

### Theorem Prover Integration (`sigma/tp/`)

- **EProver.java** - E theorem prover integration
- **Vampire.java** - Vampire theorem prover (supports HOL variant)
- **LEO.java** - LEO-III higher-order prover

### Knowledge Translation (`sigma/trans/`)

- **SUMOKBtoTPTPKB.java** - Convert full KB to TPTP format
- **SUMOformulaToTPTPformula.java** - Single formula translation
- **THF.java, THFnew.java** - Typed Higher-order Form support
- **SUMOtoTFAform.java** - TFA (Typed First-order Atoms) translation
- **Modals.java** - Modal logic support

### Other Key Packages

- **sigma/nlg/** - Natural language generation
- **sigma/wordNet/** - WordNet 3.0 integration
- **sigma/parsing/** - KIF and TPTP parsing
- **delphi/** - Delphi method (expert elicitation) implementation

### Web Layer (`web/jsp/`)

JSP-based UI with key pages:
- `Browse.jsp` - KB browsing
- `AskTell.jsp` - Query and assertion interface
- `Editor.jsp` - Ontology editing
- `CCheck.jsp` - Consistency checking

## Configuration

**config.xml** - Main configuration specifying:
- KB directory paths (`kbDir`, `baseDir`)
- Theorem prover locations (`eprover`, `vampire`, `leoExecutable`)
- WordNet paths
- Server settings

**Environment Variables:**
- `SIGMA_HOME` - Runtime KB location (default: `~/.sigmakee`)
- `ONTOLOGYPORTAL_GIT` - Git workspace root
- `CATALINA_HOME` - Tomcat installation

## Caching and Serialization

SigmaKEE uses a two-tier caching system to optimize performance: (1) a Kryo-serialized binary file (`kbmanager.ser`) for fast Java application startup, and (2) TPTP text files for external theorem provers. Understanding how these work together is essential for development.

### Overview: Two Caching Mechanisms

```
┌─────────────────────────────────────────────────────────────────────┐
│                        kbmanager.ser                                │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐                  │
│  │ formulaMap  │  │  KBcache    │  │ preferences │                  │
│  │ (SUO-KIF)   │  │ (taxonomy)  │  │             │                  │
│  └──────┬──────┘  └─────────────┘  └─────────────┘                  │
│         │                                                           │
│         │  theTptpFormulas (per-formula TPTP cache for UI display)  │
└─────────┼───────────────────────────────────────────────────────────┘
          │
          │ Used by Java app: web UI browsing, type checking, queries
          │
┌─────────┴───────────────────────────────────────────────────────────┐
│                     TPTP Text Files                                 │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐                  │
│  │ SUMO.tptp   │  │  SUMO.tff   │  │ SUMO*.thf   │                  │
│  │ (FOF)       │  │  (TFF)      │  │ (THF)       │                  │
│  └─────────────┘  └─────────────┘  └─────────────┘                  │
│                                                                     │
│         Used by external ATP provers: EProver, Vampire, LEO-III     │
└─────────────────────────────────────────────────────────────────────┘
```

| Cache | Location | Purpose | Consumer |
|-------|----------|---------|----------|
| `kbmanager.ser` | `$SIGMA_HOME/KBs/` | Fast startup, runtime data for Java app | Web UI, API |
| `SUMO.tptp` | `$SIGMA_HOME/KBs/` | FOF formulas for theorem proving | EProver, Vampire |
| `SUMO.tff` | `$SIGMA_HOME/KBs/` | TFF formulas (typed first-order) | Vampire |
| `SUMO*.thf` | `$SIGMA_HOME/KBs/` | THF formulas (higher-order) | LEO-III, Vampire HOL |

### Startup and Initialization Flow

**Key file:** `KBmanager.java` (lines 816-893 for `initializeOnce()`)

```
Application Start (Prelude.jsp or main())
           │
           ▼
   KBmanager.initializeOnce()
           │
           ├─── Is kbmanager.ser fresh? ───┐
           │    (newer than config.xml     │
           │     and all .kif files)       │
           │                               │
      YES  ▼                          NO   ▼
   loadSerialized()              Parse all .kif files
   (Kryo deserialize)            Build formulaMap, indexes
   ~seconds                      Build KBcache (slow)
           │                     serialize() to kbmanager.ser
           │                               │
           └───────────┬───────────────────┘
                       ▼
            loadKBforInference()
                       │
                       ▼
           Generate TPTP files if stale
           (SUMO.tptp, SUMO.tff)
                       │
                       ▼
           Start theorem prover processes
```

**Cold start** (no cache or stale): Slow - must parse thousands of SUO-KIF formulas and compute transitive closures for taxonomy.

**Warm start** (fresh cache): Fast - binary deserialization of pre-built objects.

**Freshness check:** `serializedOld()` compares modification timestamps of `kbmanager.ser` against `config.xml` and all KIF constituent files.

**Force fresh load:** Set `loadFresh=true` in config.xml to bypass the serialized cache.

### What's in kbmanager.ser

**Key file:** `KBmanager.java` (lines 283-326 for serialization methods)

| Component | Class | Description |
|-----------|-------|-------------|
| `formulaMap` | `KB.java` | SUO-KIF formulas parsed from .kif files |
| `formulas` | `KB.java` | Formula indexes by term/predicate |
| `terms` | `KB.java` | All terms in the KB |
| `constituents` | `KB.java` | Paths to source .kif files |
| `kbCache` | `KBcache.java` | Pre-computed taxonomy data |
| `preferences` | `KBmanager.java` | Configuration from config.xml |
| `theTptpFormulas` | `Formula.java` | Per-formula TPTP translation cache |
| `tffSorts` | `Formula.java` | Per-formula TFF sort declarations |

**KBcache contains pre-computed:**
- `parents` / `children` - transitive closure maps for `subclass`, `subrelation`, `subAttribute`
- `instanceOf` / `instances` - instance relationship caches
- `signatures` - argument types for each relation
- `valences` - arity of each relation
- `disjoint` - disjointness relationships
- `relations`, `functions`, `predicates` - categorized terms

**NOT serialized (marked `transient`):**
- `EProver eprover`, `LEO leo`, `CELT celt` - theorem prover processes (recreated at runtime)

### How the Web Application Uses Serialized Data

| Data Structure | Used For | Example |
|----------------|----------|---------|
| `kb.formulaMap` | Displaying formulas, diagnostics | `Diag.jsp` |
| `kb.terms` | Term validation, auto-complete | `TestStmnt.jsp` |
| `kb.kbCache.signatures` | Type checking arguments | Throughout |
| `kb.kbCache.parents/children` | Taxonomy browsing, inheritance | `Browse.jsp` |
| `kb.kbCache.instanceOf/instances` | Instance lookups | `Browse.jsp` |
| `kb.ask()` / `kb.tell()` | Query interface | `AskTell.jsp`, `CELT.jsp` |
| `f.theTptpFormulas` | Display TPTP format in UI | `Browse.jsp` (TPTP view) |

### The theTptpFormulas Cache: Why It Exists

Each `Formula` object has a `theTptpFormulas` field (`Set<String>`) that caches its TPTP translation. This exists **in addition to** the TPTP text files.

**Why cache per-formula translations when we have TPTP files?**

| Aspect | TPTP Text Files | Formula.theTptpFormulas |
|--------|-----------------|------------------------|
| Structure | All formulas in one file | Per-formula, tied to Formula object |
| Lookup | Must parse entire file | Direct: `formulaMap.get(key).theTptpFormulas` |
| Use case | Input for ATP provers | Quick display in web UI |

**Where theTptpFormulas is used:**

1. **Web UI display** (`HTMLformatter.java:709`) - When user selects "TPTP" or "traditional logic" format:
   ```java
   if (flang.equals("TPTP") || flang.equals("traditionalLogic"))
       formattedFormula = TPTPutil.htmlTPTPFormat(f, kbHref, traditionalLogic)
   ```
   `htmlTPTPFormat()` reads from `f.theTptpFormulas` to display translations without re-translating.

2. **Consistency checking** (`CCheck.java:367`):
   ```java
   for (Formula f : allFormulas) {
       allTPTP.addAll(f.theTptpFormulas);
   }
   ```

3. **TPTP file generation** (`SUMOKBtoTPTPKB.java:457, 675`) - writes cached translations to file.

### TPTP Generation and Re-serialization

**Important:** When TPTP/TFF files are generated, `kbmanager.ser` is **re-serialized** and grows larger.

**Key file:** `SUMOKBtoTPTPKB.java` (lines 335-336)

```java
// After TPTP file generation:
KB.axiomKey = axiomKey;
KBmanager.serialize();  // Re-serializes with populated theTptpFormulas!
```

**During TPTP generation, Formula objects are modified:**
```java
f.theTptpFormulas.clear();        // Clear previous translations
f.theTptpFormulas.add(result);    // Add new TPTP translation
f.tffSorts.addAll(stfa.sorts);    // Add TFF sort declarations
```

| State | `theTptpFormulas` | `tffSorts` | kbmanager.ser Size |
|-------|-------------------|------------|-------------------|
| After initial load | empty | empty | Smaller |
| After TPTP generation | populated | populated | Larger |

### Three Translation Paths (FOF, TFF, THF)

The system supports three TPTP output formats with **different caching behaviors**:

| Format | Class | Modifies theTptpFormulas | Calls serialize() | Output File |
|--------|-------|--------------------------|-------------------|-------------|
| FOF | `SUMOKBtoTPTPKB` | Yes (clears & repopulates) | Yes | `SUMO.tptp` |
| TFF | `SUMOKBtoTPTPKB` | Yes (clears & repopulates) | Yes | `SUMO.tff` |
| THF | `THFnew` | **No** | **No** | `SUMO_modals.thf` / `SUMO_plain.thf` |

**FOF and TFF** use the same class and **overwrite each other's cached translations**:
```java
// SUMOKBtoTPTPKB._tWriteFile()
f.theTptpFormulas.clear();        // Clears previous (FOF or TFF)
f.theTptpFormulas.add(result);    // Adds new translation
KBmanager.serialize();            // Saves to kbmanager.ser
```
If you generate TFF after FOF (or vice versa), `theTptpFormulas` contains **only the most recent**.

**THF** is independent - writes directly to file without caching:
```java
// THFnew.oneTrans()
bw.write("thf(ax" + axNum++ + ",axiom," + process(...) + ").\n");
```
- No modification to `theTptpFormulas`
- No `KBmanager.serialize()` call
- Does not affect the serialized cache

### Key Code References

| File | Lines | Purpose |
|------|-------|---------|
| `KBmanager.java` | 283-326 | `encoder()`, `decoder()`, `serialize()` methods |
| `KBmanager.java` | 180-206 | `serializedOld()` freshness check |
| `KBmanager.java` | 816-893 | `initializeOnce()` startup flow |
| `SUMOKBtoTPTPKB.java` | 335-336 | Re-serialization after TPTP generation |
| `SUMOKBtoTPTPKB.java` | 374, 537 | `theTptpFormulas.clear()` |
| `SUMOKBtoTPTPKB.java` | 417, 614, 650 | `theTptpFormulas.add()` |
| `THFnew.java` | 510 | Direct file write (no caching) |
| `HTMLformatter.java` | 709 | UI usage of `theTptpFormulas` |
| `Formula.java` | 217, 220 | `theTptpFormulas`, `tffSorts` field declarations |

## Test Organization

```
test/
├── unit/java/           # Fast unit tests (UnitTestSuite.java)
├── integration/java/    # Integration tests requiring KB (IntegrationTestSuite.java)
├── corpus/java/         # Corpus tests
└── */resources/         # Test config files (auto-generated paths)
```

External ATP tests (Vampire, E, LEO-III) are enabled locally but disabled in GitHub Actions CI. Set `RUN_EXTERNAL_ATP_TESTS=true` to force enable.

## Dependencies

Project uses Apache Ivy. Key dependencies in `ivy.xml`:
- Stanford CoreNLP 4.5.7 (NLP)
- Google Guava 19.0
- ANTLR 4.9.3 (parsing)
- H2 Database 2.3.232
- Py4J 0.10.6 (Python interop)

Related repositories that must be present in parent directory:
- `../SigmaUtils` - Utility library
- `../sigmaAntlr` - ANTLR grammars
- `../TPTP-ANTLR` - TPTP parser
- `../sumo` - SUMO ontology files

## Code Style

Follow the Java style guide in `CodeFormat.pdf`. Key points:
- Add JUnit tests for new functionality
- For GUI features, create JSP interfaces
- For CLI features, add to `main()` with `showHelp()` method following Unix command conventions