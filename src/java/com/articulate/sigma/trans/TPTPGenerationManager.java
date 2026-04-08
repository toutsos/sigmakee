/* This code is copyright Articulate Software (c) 2003-2025.
This software is released under the GNU Public License <http://www.gnu.org/copyleft/gpl.html>.
Users of this code also consent, by use of this code, to credit Articulate Software
in any writings, briefings, publications, presentations, or other representations
of any software which incorporates, builds on, or uses this code.

Manages background generation of TPTP translation files (FOF, TFF, THF).
This prevents on-demand delays when users first request inference by
pre-generating all formats at startup.
*/

package com.articulate.sigma.trans;

import com.articulate.sigma.*;
import com.articulate.sigma.parsing.Expr;
import com.articulate.sigma.parsing.ExprToTFF;
import com.articulate.sigma.parsing.ExprToTPTP;
import com.articulate.sigma.parsing.FormulaAST;
import com.articulate.sigma.persistence.KBPersistence;
import com.articulate.sigma.utils.StringUtil;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Coordinates background generation of TPTP translation files.
 * Provides synchronization for inference requests that arrive before
 * background generation completes.
 */
public class TPTPGenerationManager {

    private static final AtomicBoolean fofGenerating = new AtomicBoolean(false);
    private static final AtomicBoolean tffGenerating = new AtomicBoolean(false);
    private static final AtomicBoolean thfModalGenerating = new AtomicBoolean(false);
    private static final AtomicBoolean thfPlainGenerating = new AtomicBoolean(false);

    private static volatile CountDownLatch fofLatch = new CountDownLatch(1);
    private static volatile CountDownLatch tffLatch = new CountDownLatch(1);
    private static volatile CountDownLatch thfModalLatch = new CountDownLatch(1);
    private static volatile CountDownLatch thfPlainLatch = new CountDownLatch(1);

    private static final AtomicBoolean fofReady = new AtomicBoolean(false);
    private static final AtomicBoolean tffReady = new AtomicBoolean(false);
    private static final AtomicBoolean thfModalReady = new AtomicBoolean(false);
    private static final AtomicBoolean thfPlainReady = new AtomicBoolean(false);

    private static ExecutorService executor = null;

    private static final Object GEN_LOCK = new Object();

    /*********************************************************************************
     * When true, {@link #startBackgroundGeneration()} returns immediately without
     * spawning any threads.  All latches are counted down so {@code waitFor*()} calls
     * do not block.  Intended for tests that drive generation directly via
     * {@link #generateFOFToPath} / {@link #generateTFFToPath}.
     */
    private static final AtomicBoolean skipBackgroundGeneration = new AtomicBoolean(false);

    public static void setSkipBackgroundGeneration(boolean skip) {
        skipBackgroundGeneration.set(skip);
    }


    /*********************************************************************************
     * Start background generation of all TPTP formats for all KBs.
     * This should be called after KBmanager initialization is complete.
     *
     * FOF and TFF now run in PARALLEL on separate threads since the shared
     * static lang/hideNumbers/qlist/varmap/numericConstantTypes/filterMessage
     * fields have been converted to ThreadLocal.
     */
    public static void startBackgroundGeneration() {

        System.out.println("TPTPGenerationManager: Starting background TPTP generation");

        // Reset all latches and flags for fresh generation
        fofLatch = new CountDownLatch(1);
        tffLatch = new CountDownLatch(1);
        thfModalLatch = new CountDownLatch(1);
        thfPlainLatch = new CountDownLatch(1);

        fofReady.set(false);
        tffReady.set(false);
        thfModalReady.set(false);
        thfPlainReady.set(false);

        if (skipBackgroundGeneration.get()) {
            System.out.println("TPTPGenerationManager: Background generation suppressed (skipBackgroundGeneration=true)");
            fofLatch.countDown();
            tffLatch.countDown();
            thfModalLatch.countDown();
            thfPlainLatch.countDown();
            return;
        }

        // Use 4 threads: FOF, TFF, THF Modal, THF Plain all in parallel
        executor = Executors.newFixedThreadPool(4);

        for (KB kb : KBmanager.getMgr().kbs.values()) {
            // FOF on its own thread
            executor.submit(() -> {
                String kbDir = KBmanager.getMgr().getPref("kbDir");
                String infFilename = kbDir + File.separator + kb.name + ".tptp";
                File infFile = new File(infFilename);
                if (infFile.exists() && !KBmanager.getMgr().infFileOld()) {
                    System.out.println("TPTPGenerationManager: FOF file is current: " + infFilename +
                            "; rebuilding axiomKey in background for incremental patching");
                    // Mark FOF file ready immediately (file is current for prover use).
                    // Rebuild axiomKey asynchronously — patchSessionTPTP degrades gracefully
                    // (no stale-axiom commenting-out) if a tell() arrives before rebuild completes.
                    new Thread(() -> rebuildAxiomKey(kb), "axiomKey-rebuild-" + kb.name).start();
                    fofReady.set(true);
                    fofLatch.countDown();
                } else {
                    generateH2FOF(kb);
                }
            });

            // TFF on its own thread (parallel with FOF)
            executor.submit(() -> {
                String kbDir = KBmanager.getMgr().getPref("kbDir");
                String infFilename = kbDir + File.separator + kb.name + ".tff";
                File infFile = new File(infFilename);
                if (infFile.exists() && !KBmanager.getMgr().infFileOld()) {
                    System.out.println("TPTPGenerationManager: TFF file already exists and is current: " + infFilename);
                    tffReady.set(true);
                    tffLatch.countDown();
                } else {
                    generateH2TFF(kb);
                }
            });

            // THF can run in parallel (different code path)
            executor.submit(() -> generateTHFModal(kb));
            executor.submit(() -> generateTHFPlain(kb));
        }
        executor.shutdown();
    }

    /*********************************************************************************
     */
    public static void generateProperFile(KB kb, String lang) {

        if (skipBackgroundGeneration.get()) return;
        synchronized (GEN_LOCK) {
            if ("fof".equals(lang) || "tptp".equals(lang)) {
                generateH2FOF(kb);
            } else if ("tff".equals(lang)) {
                generateH2TFF(kb);
            }
        }
    }

    /*********************************************************************************
     * Generate FOF (First-Order Form) TPTP file for a KB.
     */
    public static void generateFOF(KB kb) {

        if (!fofGenerating.compareAndSet(false, true)) {
            return; // Already generating
        }

        String kbDir = KBmanager.getMgr().getPref("kbDir");
        String infFilename = kbDir + File.separator + kb.name + ".tptp";

        Path target = java.nio.file.Paths.get(infFilename);
        Path tmp    = java.nio.file.Paths.get(infFilename + ".tmp");

        try {
            System.out.println("===== TPTPGenerationManager: Generating FOF file: " + infFilename);
            long startTime = System.currentTimeMillis();

            // Ensure we don't leave a stale tmp around
            try { java.nio.file.Files.deleteIfExists(tmp); } catch (Exception ignore) {}

            // Set BOTH static language fields to FOF
            SUMOKBtoTPTPKB.setLang("fof");
            SUMOformulaToTPTPformula.setLang("fof");
            System.out.println("TPTPGenerationManager.generateFOF(): setHideNumbers true");
            SUMOformulaToTPTPformula.setHideNumbers(true);

            // IMPORTANT: write to tmp, not to target
            try (java.io.PrintWriter pw = new java.io.PrintWriter(
                    java.nio.file.Files.newBufferedWriter(tmp, java.nio.charset.StandardCharsets.UTF_8))) {

                SUMOKBtoTPTPKB skb = new SUMOKBtoTPTPKB();
                skb.kb = kb;

                // Keep passing the "real" filename for stable file(...) metadata, but write into pw(tmp)
                skb.writeFile(infFilename, null, false, pw);
            }

            // Atomic replace (or fallback)
            try {
                Files.move(tmp, target,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            }
            catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target,
                        StandardCopyOption.REPLACE_EXISTING);
            }

            long elapsed = System.currentTimeMillis() - startTime;
            System.out.println("==== TPTPGenerationManager: FOF generation complete in " + (elapsed / 1000.0) + "s");
            fofReady.set(true);
        }
        catch (Exception e) {
            System.err.println("TPTPGenerationManager: Error generating FOF: " + e.getMessage());
            e.printStackTrace();
            // best effort cleanupT
            try { java.nio.file.Files.deleteIfExists(tmp); } catch (Exception ignore) {}
        }
        finally {
            // Clean up ThreadLocal state to prevent leaks in thread pools
            SUMOformulaToTPTPformula.clearThreadLocal();
            SUMOKBtoTPTPKB.clearThreadLocal();
            SUMOtoTFAform.clearThreadLocal();
            fofGenerating.set(false);
            fofLatch.countDown();
        }
    }

    /*********************************************************************************
     * Generate FOF (First-Order Form) TPTP file using the new Expr-direct path
     * (M1/M6 fast path) and persist the translations to the H2 database (M4).
     *
     * <p>Difference from {@link #generateFOF}:</p>
     * <ul>
     *   <li>Iterates {@code kb.formulaMap} directly — no {@link com.articulate.sigma.FormulaPreprocessor}
     *       preprocessing, no ANTLR re-parse per formula.</li>
     *   <li>Uses {@link ExprToTPTP#translate(com.articulate.sigma.parsing.Expr, boolean, String)}
     *       when the formula has a pre-built {@link FormulaAST#expr}; falls back to
     *       {@link ExprToTPTP#translateKifString} otherwise.</li>
     *   <li>Populates {@link Formula#theFofFormulas} for each translated formula so
     *       {@link KBPersistence#saveTptp} can persist them to H2 in one batch pass
     *       after the file is written.</li>
     * </ul>
     *
     * <p>Uses the same {@code fofGenerating}/{@code fofReady}/{@code fofLatch}
     * infrastructure as {@link #generateFOF}, so it can serve as a drop-in
     * replacement in {@link #startBackgroundGeneration}.</p>
     */
    public static void generateH2FOF(KB kb) {

        if (!fofGenerating.compareAndSet(false, true)) {
            return; // Already generating
        }

        String kbDir = KBmanager.getMgr().getPref("kbDir");
        String infFilename = kbDir + File.separator + kb.name + ".tptp";

        Path target = Paths.get(infFilename);
        Path tmp    = Paths.get(infFilename + ".tmp");

        try {
            System.out.println("===== TPTPGenerationManager: Generating FOF (H2/Expr path): " + infFilename);
            long startTime = System.currentTimeMillis();

            try { Files.deleteIfExists(tmp); } catch (Exception ignore) {}

            int count                = 0;
            int idx                  = 0;
            int cacheHitCount        = 0;
            int noExprCount          = 0;
            int holSkippedCount      = 0;
            int preProcessEmptyCount = 0;
            int predVarSkippedCount  = 0;
            int translateBlankCount  = 0;
            int skippedCount         = 0;
            String safe = kb.name.replaceAll("[^A-Za-z0-9_]", "_");
            FormulaPreprocessor fp = new FormulaPreprocessor();

            // Pre-capture the relations set so shouldAddMention() can do a single O(1)
            // HashSet.contains() per atom instead of the per-atom isRelationInAnyKB() walk.
            if (kb.kbCache != null && kb.kbCache.relations != null)
                ExprToTPTP.relationsThreadLocal.set(kb.kbCache.relations);

            try (PrintWriter pw = new PrintWriter(
                    Files.newBufferedWriter(tmp, StandardCharsets.UTF_8))) {

                for (Formula f : kb.formulaMap.values()) {
                    // Skip documentation/termFormat/format — same blocklist as the old path.
                    if (SUMOKBtoTPTPKB.isNonReasoningForATP(f.getFormula())) { skippedCount++; continue; }

                    // Skip UA formulas from all sessions — the base SUMO.tptp contains only
                    // base-KB content; session-specific formulas go in session TPTP files only.
                    if (f.uaSessionId != null) { skippedCount++; continue; }

                    // Warm-start fast path: theFofFormulas was loaded from H2 tptp_cache on
                    // startup — reuse the pre-translated bodies directly instead of re-running
                    // the full Expr→TPTP pipeline.  Only safe here (not in ToPath variants)
                    // because generateH2FOF() always uses the shared kbCache, which is the
                    // same cache that produced the stored translations.
                    if (!f.theFofFormulas.isEmpty()) {
                        for (String body : f.theFofFormulas) {
                            String axiomName = "kb_" + safe + "_" + idx++;
                            pw.println("fof(" + axiomName + ",axiom,(" + body + ")).");
                            SUMOKBtoTPTPKB.putAxiom(axiomName, f);
                            count++;
                        }
                        cacheHitCount++;
                        continue;
                    }

                    if (!(f instanceof FormulaAST fa) || fa.expr == null) {
                        // No Expr tree — fall back to re-parse from string
                        noExprCount++;
                        if (noExprCount <= FALLBACK_LOG_LIMIT) {
                            String bucket = (f instanceof FormulaAST) ? "FormulaAST(expr=null)" : "plain-Formula(" + f.getClass().getSimpleName() + ")";
                            System.out.println("[FOF H2/Expr] fallback no-expr [" + bucket + "]: " + abbrev(f.getFormula()));
                        }
                        String body = ExprToTPTP.translateKifString(f.getFormula(), false, "fof");
                        if (body != null && !body.isBlank()) {
                            f.theFofFormulas.add(body);
                            String axiomName = "kb_" + safe + "_" + idx++;
                            pw.println("fof(" + axiomName + ",axiom,(" + body + ")).");
                            SUMOKBtoTPTPKB.putAxiom(axiomName, f);
                            count++;
                        } else { skippedCount++; }
                        continue;
                    }

                    // HOL check: formulas that use a formula as a term argument (e.g.
                    // holdsDuring(T, (exists (?X) ...))) are invalid FOF.  The old path
                    // writes "% is higher order" and skips them when removeHOL=true.
                    if (SUMOKBtoTPTPKB.removeHOL && fa.isHigherOrder(kb)) {
                        holSkippedCount++;
                        if (holSkippedCount <= FALLBACK_LOG_LIMIT)
                            System.out.println("[FOF H2/Expr] skipped higher-order: " + abbrev(fa.getFormula()));
                        continue;
                    }

                    // New path: run preProcessExpr on the pre-built Expr tree.
                    // This expands pred-vars and row-vars — skipping it leaves variables
                    // in predicate position which is invalid FOF (Vampire rejects such files).
                    Set<Expr> expanded = fp.preProcessExpr(fa, false, kb);

                    if (expanded == null || expanded.isEmpty()) {
                        preProcessEmptyCount++;
                        if (preProcessEmptyCount <= FALLBACK_LOG_LIMIT)
                            System.out.println("[FOF H2/Expr] fallback preprocess-empty: " + abbrev(f.getFormula()));
                        String body = ExprToTPTP.translateKifString(f.getFormula(), false, "fof");
                        if (body != null && !body.isBlank()) {
                            f.theFofFormulas.add(body);
                            String axiomName = "kb_" + safe + "_" + idx++;
                            pw.println("fof(" + axiomName + ",axiom,(" + body + ")).");
                            SUMOKBtoTPTPKB.putAxiom(axiomName, f);
                            count++;
                        } else { skippedCount++; }
                        continue;
                    }

                    for (Expr exprI : expanded) {
                        if (SUMOKBtoTPTPKB.hasUnresolvedPredVar(exprI)) {
                            predVarSkippedCount++;
                            if (predVarSkippedCount <= FALLBACK_LOG_LIMIT)
                                System.out.println("[FOF H2/Expr] skipped unresolved pred-var: " + abbrev(exprI.toKifString()));
                            continue;
                        }
                        String body = ExprToTPTP.translate(exprI, false, "fof");
                        if (body == null || body.isBlank()) {
                            translateBlankCount++;
                            if (translateBlankCount <= FALLBACK_LOG_LIMIT)
                                System.out.println("[FOF H2/Expr] fallback translate-blank: " + abbrev(exprI.toKifString()));
                            body = ExprToTPTP.translateKifString(exprI.toKifString(), false, "fof");
                        }
                        if (body == null || body.isBlank()) { skippedCount++; continue; }
                        f.theFofFormulas.add(body);
                        String axiomName = "kb_" + safe + "_" + idx++;
                        pw.println("fof(" + axiomName + ",axiom,(" + body + ")).");
                        SUMOKBtoTPTPKB.putAxiom(axiomName, f);
                        count++;
                    }
                }
            }

            boolean allFromCache = (cacheHitCount > 0 && noExprCount == 0 &&
                    preProcessEmptyCount == 0 && translateBlankCount == 0);
            if (!allFromCache)
                logFallbackSummary("FOF H2/Expr", noExprCount, holSkippedCount,
                        preProcessEmptyCount, predVarSkippedCount, translateBlankCount, skippedCount);

            // Atomic replace
            try {
                Files.move(tmp, target,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }

            long elapsed = System.currentTimeMillis() - startTime;
            System.out.println("===== TPTPGenerationManager: FOF (H2/Expr) complete in " +
                    (elapsed / 1000.0) + "s  axioms=" + count +
                    (allFromCache ? "  [warm-start cache hit]" : "  axiomKey=" + SUMOKBtoTPTPKB.axiomKey.size()));

            // Persist to H2 asynchronously — skip if all translations came from the cache
            // (data is already in H2, no point rewriting the same rows).
            if (!KBmanager.skipSerialization && !allFromCache) {
                final String kbDirFinal = kbDir;
                final int countFinal = count;
                new Thread(() -> {
                    try {
                        KBPersistence.saveTptp(kbDirFinal, kb, "fof");
                        System.out.println("TPTPGenerationManager: FOF translations persisted to H2 (" +
                                countFinal + " axioms)");
                    } catch (Exception e) {
                        System.err.println("TPTPGenerationManager: H2 save failed (non-fatal): " + e.getMessage());
                    }
                }, "H2-save-fof-" + kb.name).start();
            }

            fofReady.set(true);
        }
        catch (Exception e) {
            System.err.println("TPTPGenerationManager: Error in generateH2FOF: " + e.getMessage());
            e.printStackTrace();
            try { Files.deleteIfExists(tmp); } catch (Exception ignore) {}
        }
        finally {
            ExprToTPTP.relationsThreadLocal.remove();
            SUMOformulaToTPTPformula.clearThreadLocal();
            SUMOKBtoTPTPKB.clearThreadLocal();
            SUMOtoTFAform.clearThreadLocal();
            fofGenerating.set(false);
            fofLatch.countDown();
        }
    }

    /*********************************************************************************
     * Wrapper around {@link #generateH2TFFToPath} that manages the
     * {@code tffGenerating}/{@code tffReady}/{@code tffLatch} lifecycle — same
     * structure as {@link #generateH2FOF} so it can serve as a drop-in
     * replacement for {@link #generateTFF} in {@link #startBackgroundGeneration}.
     */
    private static void generateH2TFF(KB kb) {

        if (!tffGenerating.compareAndSet(false, true)) {
            return; // Already generating
        }

        String kbDir     = KBmanager.getMgr().getPref("kbDir");
        String tffFile   = kbDir + File.separator + kb.name + ".tff";
        Path   target    = Paths.get(tffFile);
        Path   tmp       = Paths.get(tffFile + ".tmp");

        try {
            try { Files.deleteIfExists(tmp); } catch (Exception ignore) {}
            generateH2TFFToPath(kb, tmp, true, null);
            // Atomic replace
            try {
                Files.move(tmp, target,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            tffReady.set(true);
        }
        catch (Exception e) {
            System.err.println("TPTPGenerationManager: Error in generateH2TFF: " + e.getMessage());
            e.printStackTrace();
            try { Files.deleteIfExists(tmp); } catch (Exception ignore) {}
        }
        finally {
            tffGenerating.set(false);
            tffLatch.countDown();
        }
    }

    /*********************************************************************************
     * Generate TFF (Typed First-order Form) TPTP file for a KB.
     */
    private static void generateTFF(KB kb) {

        if (!tffGenerating.compareAndSet(false, true)) {
            return; // Already generating
        }

        String kbDir = KBmanager.getMgr().getPref("kbDir");
        String infFilename = kbDir + File.separator + kb.name + ".tff";

        Path target = Paths.get(infFilename);
        Path tmp    = Paths.get(infFilename + ".tmp");

        try {
            System.out.println("==== TPTPGenerationManager: Generating TFF file: " + infFilename);
            SUMOKBtoTPTPKB.resetPathCounters();
            long startTime = System.currentTimeMillis();

            // Ensure we don't leave a stale tmp around
            try { Files.deleteIfExists(tmp); } catch (Exception ignore) {}

            // Set BOTH static language fields to TFF
            SUMOKBtoTPTPKB.setLang("tff");
            SUMOformulaToTPTPformula.setLang("tff");

            // IMPORTANT: write to tmp, not target
            try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(tmp, StandardCharsets.UTF_8))) {
                if (!kb.formulaMap.isEmpty()) {
                    SUMOKBtoTFAKB stff = new SUMOKBtoTFAKB();
                    stff.kb = kb;

                    SUMOtoTFAform.initOnce();

                    stff.writeSorts(pw);
                    // Keep passing the "real" filename for metadata, but write into pw(tmp)
                    stff.writeFile(infFilename, null, false, pw);

                    if (SUMOKBtoTPTPKB.CWA)
                        pw.println(StringUtil.arrayListToCRLFString(CWAUNA.run(kb)));

                    stff.printTFFNumericConstants(pw);
                }
            }

            // Atomic replace (or fallback)
            try {
                Files.move(tmp, target,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            }
            catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target,
                        StandardCopyOption.REPLACE_EXISTING);
            }

            long elapsed = System.currentTimeMillis() - startTime;
            System.out.println("==== TPTPGenerationManager: TFF generation complete in " + (elapsed / 1000.0) + "s");
            SUMOKBtoTPTPKB.logPathCounters();
            tffReady.set(true);

        }
        catch (Exception e) {
            System.err.println("TPTPGenerationManager: Error generating TFF: " + e.getMessage());
            e.printStackTrace();
            // best-effort cleanup
            try { Files.deleteIfExists(tmp); } catch (Exception ignore) {}
        }
        finally {
            // Clean up ThreadLocal state to prevent leaks in thread pools
            SUMOformulaToTPTPformula.clearThreadLocal();
            SUMOKBtoTPTPKB.clearThreadLocal();
            SUMOtoTFAform.clearThreadLocal();
            tffGenerating.set(false);
            tffLatch.countDown();
        }
    }

    /*********************************************************************************
     * Generate THF Modal (Higher-order Form with modals) file for a KB.
     */
    private static void generateTHFModal(KB kb) {

        if (!thfModalGenerating.compareAndSet(false, true)) {
            return; // Already generating
        }

        try {
            String kbDir = KBmanager.getMgr().getPref("kbDir");
            String thfFilename = kbDir + File.separator + kb.name + "_modals.thf";
            File thfFile = new File(thfFilename);

            // Check if file already exists and is not stale
            if (thfFile.exists() && !KBmanager.getMgr().infFileOld()) {
                System.out.println("TPTPGenerationManager: THF Modal file already exists and is current: " + thfFilename);
                thfModalReady.set(true);
                return;
            }

            System.out.println("==== TPTPGenerationManager: Generating THF Modal file: " + thfFilename);
            long startTime = System.currentTimeMillis();

            THFnew.transModalTHF(kb);

            long elapsed = System.currentTimeMillis() - startTime;
            System.out.println("==== TPTPGenerationManager: THF Modal generation complete in " + (elapsed / 1000.0) + "s");
            thfModalReady.set(true);
        }
        catch (Exception e) {
            System.err.println("TPTPGenerationManager: Error generating THF Modal: " + e.getMessage());
            e.printStackTrace();
        }
        finally {
            thfModalGenerating.set(false);
            thfModalLatch.countDown();
        }
    }

    /*********************************************************************************
     * Generate THF Plain (Higher-order Form without modals) file for a KB.
     */
    private static void generateTHFPlain(KB kb) {

        if (!thfPlainGenerating.compareAndSet(false, true)) {
            return; // Already generating
        }
        try {
            String kbDir = KBmanager.getMgr().getPref("kbDir");
            String thfFilename = kbDir + File.separator + kb.name + "_plain.thf";
            File thfFile = new File(thfFilename);

            // Check if file already exists and is not stale
            if (thfFile.exists() && !KBmanager.getMgr().infFileOld()) {
                System.out.println("TPTPGenerationManager: THF Plain file already exists and is current: " + thfFilename);
                thfPlainReady.set(true);
                return;
            }

            System.out.println("==== TPTPGenerationManager: Generating THF Plain file: " + thfFilename);
            long startTime = System.currentTimeMillis();

            THFnew.transPlainTHF(kb);

            long elapsed = System.currentTimeMillis() - startTime;
            System.out.println("==== TPTPGenerationManager: THF Plain generation complete in " + (elapsed / 1000.0) + "s");
            thfPlainReady.set(true);
        }
        catch (Exception e) {
            System.err.println("TPTPGenerationManager: Error generating THF Plain: " + e.getMessage());
            e.printStackTrace();
        }
        finally {
            thfPlainGenerating.set(false);
            thfPlainLatch.countDown();
        }
    }

    /*********************************************************************************
     * Wait for FOF generation to complete.
     * @param timeoutSec Maximum time to wait in seconds
     * @return true if generation completed successfully, false if timed out
     */
    public static boolean waitForFOF(int timeoutSec) {

        if (fofReady.get()) return true;
        try {
            System.out.println("TPTPGenerationManager: Waiting for FOF generation (timeout: " + timeoutSec + "s)...");
            boolean completed = fofLatch.await(timeoutSec, TimeUnit.SECONDS);
            if (completed) {
                System.out.println("TPTPGenerationManager: FOF generation wait completed");
            } else {
                System.out.println("TPTPGenerationManager: FOF generation wait timed out");
            }
            return completed && fofReady.get();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /*********************************************************************************
     * Wait for TFF generation to complete.
     * @param timeoutSec Maximum time to wait in seconds
     * @return true if generation completed successfully, false if timed out
     */
    public static boolean waitForTFF(int timeoutSec) {

        if (tffReady.get()) return true;
        try {
            System.out.println("TPTPGenerationManager: Waiting for TFF generation (timeout: " + timeoutSec + "s)...");
            boolean completed = tffLatch.await(timeoutSec, TimeUnit.SECONDS);
            if (completed) {
                System.out.println("TPTPGenerationManager: TFF generation wait completed");
            } else {
                System.out.println("TPTPGenerationManager: TFF generation wait timed out");
            }
            return completed && tffReady.get();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /*********************************************************************************
     * Wait for THF Modal generation to complete.
     * @param timeoutSec Maximum time to wait in seconds
     * @return true if generation completed successfully, false if timed out
     */
    public static boolean waitForTHFModal(int timeoutSec) {

        if (thfModalReady.get()) return true;
        try {
            System.out.println("TPTPGenerationManager: Waiting for THF Modal generation (timeout: " + timeoutSec + "s)...");
            boolean completed = thfModalLatch.await(timeoutSec, TimeUnit.SECONDS);
            if (completed) {
                System.out.println("TPTPGenerationManager: THF Modal generation wait completed");
            } else {
                System.out.println("TPTPGenerationManager: THF Modal generation wait timed out");
            }
            return completed && thfModalReady.get();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /*********************************************************************************
     * Wait for THF Plain generation to complete.
     * @param timeoutSec Maximum time to wait in seconds
     * @return true if generation completed successfully, false if timed out
     */
    public static boolean waitForTHFPlain(int timeoutSec) {

        if (thfPlainReady.get()) return true;
        try {
            System.out.println("TPTPGenerationManager: Waiting for THF Plain generation (timeout: " + timeoutSec + "s)...");
            boolean completed = thfPlainLatch.await(timeoutSec, TimeUnit.SECONDS);
            if (completed) {
                System.out.println("TPTPGenerationManager: THF Plain generation wait completed");
            } else {
                System.out.println("TPTPGenerationManager: THF Plain generation wait timed out");
            }
            return completed && thfPlainReady.get();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /*********************************************************************************
     * Check if FOF generation is ready.
     */
    public static boolean isFOFReady() {
        return fofReady.get();
    }

    /*********************************************************************************
     * Check if TFF generation is ready.
     */
    public static boolean isTFFReady() {
        return tffReady.get();
    }

    /*********************************************************************************
     * Check if THF Modal generation is ready.
     */
    public static boolean isTHFModalReady() {
        return thfModalReady.get();
    }

    /*********************************************************************************
     * Check if THF Plain generation is ready.
     */
    public static boolean isTHFPlainReady() {
        return thfPlainReady.get();
    }

    /*********************************************************************************
     * Check if FOF generation is currently in progress.
     */
    public static boolean isFOFGenerating() {
        return fofGenerating.get();
    }

    /*********************************************************************************
     * Check if TFF generation is currently in progress.
     */
    public static boolean isTFFGenerating() {
        return tffGenerating.get();
    }

    /*********************************************************************************
     * Check if THF Modal generation is currently in progress.
     */
    public static boolean isTHFModalGenerating() {
        return thfModalGenerating.get();
    }

    /*********************************************************************************
     * Check if THF Plain generation is currently in progress.
     */
    public static boolean isTHFPlainGenerating() {
        return thfPlainGenerating.get();
    }

    /*********************************************************************************
     * Check if any background generation is currently in progress.
     * Used to prevent concurrent serialization during background generation.
     */
    public static boolean isBackgroundGenerating() {
        return fofGenerating.get() || tffGenerating.get() ||
               thfModalGenerating.get() || thfPlainGenerating.get();
    }

    /*********************************************************************************
     * Shutdown the executor service gracefully.
     */
    public static void shutdown() {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /*********************************************************************************
     * Generate FOF (First-Order Form) TPTP to a custom output path.
     * This is used for session-specific TPTP generation.
     *
     * @param kb The knowledge base
     * @param outputPath The path to write the TPTP file
     * @throws IOException if file operations fail
     */
    public static void generateFOFToPath(KB kb, Path outputPath) throws IOException {

        try {
            System.out.println("TPTPGenerationManager: Generating FOF to custom path: " + outputPath);
            long startTime = System.currentTimeMillis();

            // Set ThreadLocal language fields to FOF
            SUMOKBtoTPTPKB.setLang("fof");
            SUMOformulaToTPTPformula.setLang("fof");
            System.out.println("TPTPGenerationManager.generateFOFToPath(): setHideNumbers true");
            SUMOformulaToTPTPformula.setHideNumbers(true);

            // Redirect axiomKey writes to a session-local map so this session-specific
            // generation does not overwrite the global SUMOKBtoTPTPKB.axiomKey, which
            // must only track shared base-KB axiom names.
            SUMOKBtoTPTPKB.localAxiomKeyOverride.set(new HashMap<>());
            try {
                try (PrintWriter pw = new PrintWriter(
                        Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8))) {

                    SUMOKBtoTPTPKB skb = new SUMOKBtoTPTPKB();
                    skb.kb = kb;
                    skb.writeFile(kb.name, null, false, pw);
                }
            } finally {
                SUMOKBtoTPTPKB.localAxiomKeyOverride.remove();
            }

            long elapsed = System.currentTimeMillis() - startTime;
            System.out.println("TPTPGenerationManager: FOF generation to custom path complete in " +
                               (elapsed / 1000.0) + "s");

        }
        finally {
            SUMOformulaToTPTPformula.clearThreadLocal();
            SUMOKBtoTPTPKB.clearThreadLocal();
            SUMOtoTFAform.clearThreadLocal();
        }
    }

    /*********************************************************************************
     * Re-runs the FOF translation pipeline against the shared KB, writing to a
     * null {@link java.io.PrintWriter} (no disk I/O), solely to populate
     * {@link SUMOKBtoTPTPKB#axiomKey} in memory.
     *
     * <p>Called on warm starts when {@code SUMO.tptp} already exists and is current,
     * so normal {@link #generateFOF} is skipped.  Without this, {@code axiomKey}
     * would stay empty until the server is restarted with changed KIF files, causing
     * every first {@code tell()} in a session to fall back to full TPTP regeneration.
     *
     * <p>Runs on a background thread; {@link #fofReady} is already {@code true} by
     * the time this is launched.  {@link SessionTPTPManager#patchSessionTPTP} degrades
     * gracefully (no stale-axiom commenting-out) if {@code tell()} arrives before
     * this completes.
     *
     * @param kb the shared knowledge base (must contain only base formulas, no user assertions)
     */
    private static void rebuildAxiomKey(KB kb) {

        try {
            System.out.println("TPTPGenerationManager: Rebuilding axiomKey (H2/Expr path, no I/O)...");
            long start = System.currentTimeMillis();

            // Mirror the generateH2FOF formula iteration exactly so axiom names match
            // the names written into the SUMO.tptp file on the last cold start.
            String safe = kb.name.replaceAll("[^A-Za-z0-9_]", "_");
            int idx = 0;
            FormulaPreprocessor fp = new FormulaPreprocessor();

            if (kb.kbCache != null && kb.kbCache.relations != null)
                ExprToTPTP.relationsThreadLocal.set(kb.kbCache.relations);

            for (Formula f : kb.formulaMap.values()) {
                if (SUMOKBtoTPTPKB.isNonReasoningForATP(f.getFormula())) continue;
                if (f.uaSessionId != null) continue;  // UA formulas are not in the base axiomKey

                if (!(f instanceof FormulaAST fa) || fa.expr == null) {
                    String body = ExprToTPTP.translateKifString(f.getFormula(), false, "fof");
                    if (body != null && !body.isBlank())
                        SUMOKBtoTPTPKB.putAxiom("kb_" + safe + "_" + idx++, f);
                    continue;
                }

                if (SUMOKBtoTPTPKB.removeHOL && fa.isHigherOrder(kb)) continue;

                Set<Expr> expanded = fp.preProcessExpr(fa, false, kb);
                if (expanded == null || expanded.isEmpty()) {
                    String body = ExprToTPTP.translateKifString(f.getFormula(), false, "fof");
                    if (body != null && !body.isBlank())
                        SUMOKBtoTPTPKB.putAxiom("kb_" + safe + "_" + idx++, f);
                    continue;
                }

                for (Expr exprI : expanded) {
                    if (SUMOKBtoTPTPKB.hasUnresolvedPredVar(exprI)) continue;
                    String body = ExprToTPTP.translate(exprI, false, "fof");
                    if (body == null || body.isBlank())
                        body = ExprToTPTP.translateKifString(exprI.toKifString(), false, "fof");
                    if (body != null && !body.isBlank())
                        SUMOKBtoTPTPKB.putAxiom("kb_" + safe + "_" + idx++, f);
                }
            }

            long elapsed = System.currentTimeMillis() - start;
            System.out.println("TPTPGenerationManager: axiomKey rebuilt in " +
                    (elapsed / 1000.0) + "s — " + SUMOKBtoTPTPKB.axiomKey.size() + " entries");
        }
        catch (Exception e) {
            System.err.println("TPTPGenerationManager: Failed to rebuild axiomKey: " + e.getMessage());
            e.printStackTrace();
        }
        finally {
            ExprToTPTP.relationsThreadLocal.remove();
        }
    }

    /*********************************************************************************
     * Generate TFF (Typed First-order Form) TPTP to a custom output path.
     * This is used for session-specific TPTP generation.
     *
     * @param kb The knowledge base
     * @param outputPath The path to write the TPTP file
     * @throws IOException if file operations fail
     */
    public static void generateTFFToPath(KB kb, Path outputPath) throws IOException {

        try {
            System.out.println("TPTPGenerationManager: Generating TFF to custom path: " + outputPath);
            SUMOKBtoTPTPKB.resetPathCounters();
            long startTime = System.currentTimeMillis();

            // Set ThreadLocal language fields to TFF
            SUMOKBtoTPTPKB.setLang("tff");
            SUMOformulaToTPTPformula.setLang("tff");

            try (PrintWriter pw = new PrintWriter(
                    Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8))) {

                if (!kb.formulaMap.isEmpty()) {
                    SUMOKBtoTFAKB stff = new SUMOKBtoTFAKB();
                    stff.kb = kb;

                    SUMOtoTFAform.initOnce();

                    stff.writeSorts(pw);
                    stff.writeFile(kb.name + ".tff", null, false, pw);

                    if (SUMOKBtoTPTPKB.CWA) {
                        pw.println(StringUtil.arrayListToCRLFString(CWAUNA.run(kb)));
                    }

                    stff.printTFFNumericConstants(pw);
                }
            }

            long elapsed = System.currentTimeMillis() - startTime;
            System.out.println("TPTPGenerationManager: TFF generation to custom path complete in " +
                               (elapsed / 1000.0) + "s");
            SUMOKBtoTPTPKB.logPathCounters();

        } finally {
            SUMOformulaToTPTPformula.clearThreadLocal();
            SUMOKBtoTPTPKB.clearThreadLocal();
            SUMOtoTFAform.clearThreadLocal();
        }
    }

    /*********************************************************************************
     * Generate FOF using the new Expr-direct path (M1) to a custom output path.
     *
     * <p>Path-based companion to {@link #generateH2FOF}: same Expr-direct
     * translation but writes to {@code outputPath} instead of the standard
     * {@code kbDir/kb.name.tptp}.  Non-fatally attempts H2 persistence after
     * the file is written.</p>
     *
     * @param kb           the knowledge base
     * @param outputPath   the path to write the FOF file
     * @param forSessionId session being generated for, or {@code null} for base generation.
     *                     Formulas tagged with a different {@code uaSessionId} are excluded.
     * @throws IOException if file operations fail
     */
    /** Backward-compatible overload used by tests and non-session callers. */
    public static void generateH2FOFToPath(KB kb, Path outputPath) throws IOException {
        generateH2FOFToPath(kb, outputPath, null);
    }

    public static void generateH2FOFToPath(KB kb, Path outputPath, String forSessionId) throws IOException {

        // Redirect putAxiom() calls to a local map so session/test generation does not
        // pollute the global SUMOKBtoTPTPKB.axiomKey (which tracks only base-KB axioms).
        SUMOKBtoTPTPKB.localAxiomKeyOverride.set(new java.util.HashMap<>());
        try {
            System.out.println("TPTPGenerationManager: Generating FOF (H2/Expr path) to: " + outputPath);
            long startTime = System.currentTimeMillis();

            String safe = kb.name.replaceAll("[^A-Za-z0-9_]", "_");
            int count                = 0;
            int idx                  = 0;
            int noExprCount          = 0;
            int holSkippedCount      = 0;
            int preProcessEmptyCount = 0;
            int predVarSkippedCount  = 0;
            int translateBlankCount  = 0;
            int skippedCount         = 0;
            FormulaPreprocessor fp = new FormulaPreprocessor();

            try (PrintWriter pw = new PrintWriter(
                    Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8))) {

                for (Formula f : kb.formulaMap.values()) {
                    // Skip documentation/termFormat/format — same blocklist as the old path.
                    if (SUMOKBtoTPTPKB.isNonReasoningForATP(f.getFormula())) { skippedCount++; continue; }

                    // Session isolation: include base-KB formulas always, UA formulas only for
                    // their own session.  forSessionId==null means base generation (no UA at all).
                    if (f.uaSessionId != null) {
                        if (!f.uaSessionId.equals(forSessionId)) { skippedCount++; continue; }
                    }

                    // forSessionId != null means session generation: the kb.kbCache may be a
                    // session-specific copy whose type guards differ from the base.  Do NOT
                    // write those translations back to the shared f.theFofFormulas — that would
                    // corrupt the base cache used by other sessions and the warm-start fast path.
                    boolean updateCache = (forSessionId == null);

                    if (!(f instanceof FormulaAST fa) || fa.expr == null) {
                        noExprCount++;
                        if (noExprCount <= FALLBACK_LOG_LIMIT)
                            System.out.println("[FOF H2/Expr] fallback no-expr: " + abbrev(f.getFormula()));
                        String body = ExprToTPTP.translateKifString(f.getFormula(), false, "fof");
                        if (body != null && !body.isBlank()) {
                            if (updateCache) f.theFofFormulas.add(body);
                            String axiomName = "kb_" + safe + "_" + idx++;
                            pw.println("fof(" + axiomName + ",axiom,(" + body + ")).");
                            SUMOKBtoTPTPKB.putAxiom(axiomName, f);
                            count++;
                        } else { skippedCount++; }
                        continue;
                    }

                    // HOL check: formulas that use a formula as a term argument (e.g.
                    // holdsDuring(T, (exists (?X) ...))) are invalid FOF.  Use the same
                    // string-based isHigherOrder() that the old path uses.
                    if (SUMOKBtoTPTPKB.removeHOL && fa.isHigherOrder(kb)) {
                        holSkippedCount++;
                        if (holSkippedCount <= FALLBACK_LOG_LIMIT)
                            System.out.println("[FOF H2/Expr] skipped higher-order: " + abbrev(fa.getFormula()));
                        continue;
                    }

                    Set<Expr> expanded = fp.preProcessExpr(fa, false, kb);

                    if (expanded == null || expanded.isEmpty()) {
                        preProcessEmptyCount++;
                        if (preProcessEmptyCount <= FALLBACK_LOG_LIMIT)
                            System.out.println("[FOF H2/Expr] fallback preprocess-empty: " + abbrev(f.getFormula()));
                        String body = ExprToTPTP.translateKifString(f.getFormula(), false, "fof");
                        if (body != null && !body.isBlank()) {
                            if (updateCache) f.theFofFormulas.add(body);
                            String axiomName = "kb_" + safe + "_" + idx++;
                            pw.println("fof(" + axiomName + ",axiom,(" + body + ")).");
                            SUMOKBtoTPTPKB.putAxiom(axiomName, f);
                            count++;
                        } else { skippedCount++; }
                        continue;
                    }

                    for (Expr exprI : expanded) {
                        if (SUMOKBtoTPTPKB.hasUnresolvedPredVar(exprI)) {
                            predVarSkippedCount++;
                            if (predVarSkippedCount <= FALLBACK_LOG_LIMIT)
                                System.out.println("[FOF H2/Expr] skipped unresolved pred-var: " + abbrev(exprI.toKifString()));
                            continue;
                        }
                        String body = ExprToTPTP.translate(exprI, false, "fof");
                        if (body == null || body.isBlank()) {
                            translateBlankCount++;
                            if (translateBlankCount <= FALLBACK_LOG_LIMIT)
                                System.out.println("[FOF H2/Expr] fallback translate-blank: " + abbrev(exprI.toKifString()));
                            body = ExprToTPTP.translateKifString(exprI.toKifString(), false, "fof");
                        }
                        if (body == null || body.isBlank()) { skippedCount++; continue; }
                        if (updateCache) f.theFofFormulas.add(body);
                        String axiomName = "kb_" + safe + "_" + idx++;
                        pw.println("fof(" + axiomName + ",axiom,(" + body + ")).");
                        SUMOKBtoTPTPKB.putAxiom(axiomName, f);
                        count++;
                    }
                }
            }

            logFallbackSummary("FOF H2/Expr", noExprCount, holSkippedCount,
                    preProcessEmptyCount, predVarSkippedCount, translateBlankCount, skippedCount);

            long elapsed = System.currentTimeMillis() - startTime;
            System.out.println("TPTPGenerationManager: FOF (H2/Expr) to custom path complete in " +
                    (elapsed / 1000.0) + "s  axioms=" + count);

            // Only persist to H2 for base generation (forSessionId==null).
            // Session generation uses a session-specific kbCache so translations may differ
            // from the base; writing them to H2 would corrupt the shared warm-start cache.
            if (forSessionId == null && !KBmanager.skipSerialization) {
                new Thread(() -> {
                    try {
                        String kbDir2 = KBmanager.getMgr().getPref("kbDir");
                        KBPersistence.saveTptp(kbDir2, kb, "fof");
                    } catch (Exception e) {
                        System.err.println("TPTPGenerationManager: H2 FOF save failed (non-fatal): " + e.getMessage());
                    }
                }, "H2-save-fof-path-" + kb.name).start();
            }
        }
        finally {
            SUMOKBtoTPTPKB.localAxiomKeyOverride.remove();
            SUMOformulaToTPTPformula.clearThreadLocal();
            SUMOKBtoTPTPKB.clearThreadLocal();
            SUMOtoTFAform.clearThreadLocal();
        }
    }

    /*********************************************************************************
     * Generate TFF using the new Expr-direct path (M6) to a custom output path.
     *
     * <p>Uses {@link ExprToTFF#translate} when a pre-built {@link FormulaAST#expr}
     * is available; falls back to {@link ExprToTPTP#translateKifString} with
     * {@code "tff"} as the language for formulas without an Expr tree.  Non-fatally
     * attempts H2 persistence after the file is written.</p>
     *
     * @param kb         the knowledge base
     * @param outputPath the path to write the TFF file
     * @throws IOException if file operations fail
     */
    /**
     * Base-generation entry point (no session context).
     * Always re-translates; no UA formulas included.
     */
    public static void generateH2TFFToPath(KB kb, Path outputPath) throws IOException {
        generateH2TFFToPath(kb, outputPath, false, null);
    }

    /**
     * Session-generation entry point — always re-translates using the current kbCache
     * (which may be a session-specific copy with schema tells applied).
     *
     * @param forSessionId the session to generate for; only UA formulas belonging to this
     *                     session are included alongside base-KB formulas.
     */
    public static void generateH2TFFToPath(KB kb, Path outputPath, String forSessionId) throws IOException {
        generateH2TFFToPath(kb, outputPath, false, forSessionId);
    }

    /**
     * @param useCached    {@code true} to use pre-translated strings from
     *                     {@link Formula#theTffFormulas} (warm-start fast path);
     *                     {@code false} to always re-translate via Expr/KIF (session path).
     * @param forSessionId session being generated for, or {@code null} for base generation.
     *                     Formulas tagged with a different {@code uaSessionId} are excluded.
     */
    private static void generateH2TFFToPath(KB kb, Path outputPath, boolean useCached,
                                            String forSessionId) throws IOException {

        try {
            System.out.println("TPTPGenerationManager: Generating TFF (H2/Expr path) to: " + outputPath +
                    (useCached ? " [warm-start mode]" : ""));
            long startTime = System.currentTimeMillis();

            String safe = kb.name.replaceAll("[^A-Za-z0-9_]", "_");
            int count               = 0;
            int idx                 = 0;
            int cacheHitCount       = 0;
            int noExprCount         = 0;
            int translateBlankCount = 0;
            int skippedCount        = 0;

            try (PrintWriter pw = new PrintWriter(
                    Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8))) {

                for (Formula f : kb.formulaMap.values()) {
                    // Skip documentation/termFormat/format — same blocklist as the old path.
                    if (SUMOKBtoTPTPKB.isNonReasoningForATP(f.getFormula())) { skippedCount++; continue; }

                    // Session isolation: same logic as FOF path.
                    if (f.uaSessionId != null) {
                        if (!f.uaSessionId.equals(forSessionId)) { skippedCount++; continue; }
                    }

                    // Warm-start fast path: theTffFormulas was loaded from H2 tptp_cache on
                    // startup — reuse the pre-translated bodies directly.  Only enabled when
                    // useCached=true (base TFF generation) so that session generation, which
                    // may use a different kbCache, always re-translates for correctness.
                    if (useCached && !f.theTffFormulas.isEmpty()) {
                        for (String body : f.theTffFormulas) {
                            pw.println("tff(kb_" + safe + "_" + idx++ + ",axiom,(" + body + ")).");
                            count++;
                        }
                        cacheHitCount++;
                        continue;
                    }

                    String body     = null;
                    boolean hadExpr = f instanceof FormulaAST ast0 && ast0.expr != null;

                    if (hadExpr) {
                        body = ExprToTFF.translate(((FormulaAST) f).expr, false, kb);
                        if (body == null || body.isBlank()) {
                            translateBlankCount++;
                            if (translateBlankCount <= FALLBACK_LOG_LIMIT)
                                System.out.println("[TFF H2/Expr] fallback translate-blank: " + abbrev(f.getFormula()));
                            body = ExprToTPTP.translateKifString(f.getFormula(), false, "tff");
                        }
                    } else {
                        noExprCount++;
                        if (noExprCount <= FALLBACK_LOG_LIMIT)
                            System.out.println("[TFF H2/Expr] fallback no-expr: " + abbrev(f.getFormula()));
                        body = ExprToTPTP.translateKifString(f.getFormula(), false, "tff");
                    }

                    if (body == null || body.isBlank()) {
                        skippedCount++;
                        if (skippedCount <= FALLBACK_LOG_LIMIT)
                            System.out.println("[TFF H2/Expr] skipped both-paths-failed: " + abbrev(f.getFormula()));
                        continue;
                    }

                    // Same as FOF: don't write session-translated bodies back to shared cache.
                    if (forSessionId == null) f.theTffFormulas.add(body);
                    pw.println("tff(kb_" + safe + "_" + idx++ + ",axiom,(" + body + ")).");
                    count++;
                }
            }

            boolean allFromCache = (cacheHitCount > 0 && noExprCount == 0 && translateBlankCount == 0);
            if (!allFromCache)
                logFallbackSummary("TFF H2/Expr", noExprCount, 0, 0, 0, translateBlankCount, skippedCount);

            long elapsed = System.currentTimeMillis() - startTime;
            System.out.println("TPTPGenerationManager: TFF (H2/Expr) to custom path complete in " +
                    (elapsed / 1000.0) + "s  axioms=" + count +
                    (allFromCache ? "  [warm-start cache hit]" : ""));

            // Only persist to H2 for base generation; skip for session generation.
            if (forSessionId == null && !KBmanager.skipSerialization && !allFromCache) new Thread(() -> {
                try {
                    String kbDir2 = KBmanager.getMgr().getPref("kbDir");
                    KBPersistence.saveTptp(kbDir2, kb, "tff");
                } catch (Exception e) {
                    System.err.println("TPTPGenerationManager: H2 TFF save failed (non-fatal): " + e.getMessage());
                }
            }, "H2-save-tff-path-" + kb.name).start();
        }
        finally {
            SUMOformulaToTPTPformula.clearThreadLocal();
            SUMOKBtoTPTPKB.clearThreadLocal();
            SUMOtoTFAform.clearThreadLocal();
        }
    }

    /** Maximum number of individual fallback lines logged per category before switching
     *  to the summary-only mode.  Prevents log flooding on large KBs. */
    private static final int FALLBACK_LOG_LIMIT = 5;

    /** Truncates {@code s} to 100 characters for readable fallback log lines. */
    private static String abbrev(String s) {
        if (s == null) return "<null>";
        s = s.replace('\n', ' ').replace('\r', ' ');
        return s.length() > 100 ? s.substring(0, 100) + "…" : s;
    }

    /** Prints a one-line fallback summary; also notes how many lines were suppressed
     *  above the {@link #FALLBACK_LOG_LIMIT} threshold.
     *
     * @param noExpr          formulas with no pre-built Expr tree (re-parsed from string)
     * @param preProcessEmpty formulas where preProcessExpr returned nothing (re-parsed from string)
     * @param predVarSkipped  Expr results still containing a pred-var after expansion (dropped)
     * @param translateBlank  Expr results where translate() returned blank (fell back to translateKifString)
     * @param skipped         formulas dropped entirely (both paths failed)
     */
    /**
     * @param noExpr          formulas with no pre-built Expr tree (re-parsed from string)
     * @param holSkipped      higher-order formulas skipped because they are invalid FOF
     * @param preProcessEmpty formulas where preProcessExpr returned nothing (re-parsed from string)
     * @param predVarSkipped  Expr results still containing a pred-var after expansion (dropped)
     * @param translateBlank  Expr results where translate() returned blank (fell back to translateKifString)
     * @param skipped         formulas dropped entirely (both paths failed)
     */
    private static void logFallbackSummary(String tag,
                                           int noExpr, int holSkipped, int preProcessEmpty,
                                           int predVarSkipped,
                                           int translateBlank, int skipped) {
        if (noExpr > FALLBACK_LOG_LIMIT)
            System.out.println("[" + tag + "] … and " + (noExpr - FALLBACK_LOG_LIMIT) + " more no-expr fallbacks suppressed");
        if (holSkipped > FALLBACK_LOG_LIMIT)
            System.out.println("[" + tag + "] … and " + (holSkipped - FALLBACK_LOG_LIMIT) + " more higher-order skipped suppressed");
        if (preProcessEmpty > FALLBACK_LOG_LIMIT)
            System.out.println("[" + tag + "] … and " + (preProcessEmpty - FALLBACK_LOG_LIMIT) + " more preprocess-empty fallbacks suppressed");
        if (predVarSkipped > FALLBACK_LOG_LIMIT)
            System.out.println("[" + tag + "] … and " + (predVarSkipped - FALLBACK_LOG_LIMIT) + " more pred-var-skipped suppressed");
        if (translateBlank > FALLBACK_LOG_LIMIT)
            System.out.println("[" + tag + "] … and " + (translateBlank - FALLBACK_LOG_LIMIT) + " more translate-blank fallbacks suppressed");
        if (skipped > FALLBACK_LOG_LIMIT)
            System.out.println("[" + tag + "] … and " + (skipped - FALLBACK_LOG_LIMIT) + " more skipped formulas suppressed");
        System.out.printf("[%s] fallback summary — no-expr: %d  hol-skipped: %d  preprocess-empty: %d  pred-var-skipped: %d  translate-blank: %d  skipped: %d%n",
                tag, noExpr, holSkipped, preProcessEmpty, predVarSkipped, translateBlank, skipped);
    }

    /*********************************************************************************
     * Get the generation lock for external synchronization.
     * Used by SessionTPTPManager to coordinate with background generation.
     */
    public static Object getGenerationLock() {
        return GEN_LOCK;
    }
}
