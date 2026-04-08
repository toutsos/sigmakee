package com.articulate.sigma.persistence;

import com.articulate.sigma.*;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

import static org.junit.Assert.*;

/**
 * M4 round-trip test: cold-build a small KB, persist it to H2, then load it
 * back and verify that formulaMap and KBcache structures are identical to the
 * original cold-built state.
 *
 * <p>Also verifies TPTP persistence: saves synthetic FOF strings, loads them
 * back, and checks that the formula TPTP caches are restored correctly.</p>
 *
 * <p>Uses an in-memory (temp-dir) H2 database so the test is self-contained
 * and leaves no permanent files on disk.</p>
 */
public class KBPersistenceTest {

    private static final String[] KB_STMTS = {
            "(instance subclass TransitiveRelation)",
            "(instance subrelation TransitiveRelation)",
            "(instance subAttribute TransitiveRelation)",
            "(subclass TransitiveRelation Relation)",
            "(subclass BinaryRelation Relation)",
            "(subclass Relation Entity)",
            "(subclass Animal Entity)",
            "(subclass Dog Animal)",
            "(subclass Cat Animal)",
            "(subclass Mammal Animal)",
            "(subclass Dog Mammal)",
            "(instance Fido Dog)",
            "(instance Felix Cat)",
            "(instance subclass BinaryRelation)",
            "(domain subclass 1 Class)",
            "(domain subclass 2 Class)",
    };

    private static String tempDir;
    private static KB      coldKB;
    private static KBcache coldCache;

    // -----------------------------------------------------------------------
    // Setup: cold build, then persist to H2
    // -----------------------------------------------------------------------

    @BeforeClass
    public static void setUpClass() throws Exception {
        // Use a temp directory so the H2 file does not pollute the workspace
        Path tmp = Files.createTempDirectory("sigma_m4_test");
        tempDir = tmp.toAbsolutePath().toString();

        // Cold build
        KBmanager.getMgr().setPref("cacheDisjoint", "false");
        coldKB = new KB("M4TestKB");
        coldKB.kbCache = new KBcache(coldKB);
        KIF kif = new KIF();
        for (String stmt : KB_STMTS)
            kif.parseStatement(stmt);
        coldKB.merge(kif, "");
        for (Formula f : coldKB.formulaMap.values())
            f.sourceFile = "test";
        coldCache = new KBcache(coldKB);
        coldKB.kbCache = coldCache;
        coldCache.buildCaches();

        // Add synthetic FOF translations so we can test TPTP persistence
        for (Formula f : coldKB.formulaMap.values())
            f.theFofFormulas.add("fof(test_axiom,axiom," + f.getFormula() + ").");

        // Persist
        KBPersistence.saveKB(tempDir, coldKB);
        KBPersistence.saveTptp(tempDir, coldKB, "fof");
        KBPersistence.saveFingerprint(tempDir, "M4TestKB", "test-fingerprint-1");
    }

    @AfterClass
    public static void tearDown() {
        // Delete the temp H2 database files
        File dir = new File(tempDir);
        File[] files = dir.listFiles();
        if (files != null)
            for (File f : files) f.delete();
        dir.delete();
    }

    // -----------------------------------------------------------------------
    // Schema / connectivity
    // -----------------------------------------------------------------------

    @Test
    public void schema_tablesExist() throws SQLException {
        try (Connection conn = KBPersistence.openConnection(tempDir)) {
            // If we get here without exception the schema was created
            assertNotNull("connection must not be null", conn);
        }
    }

    // -----------------------------------------------------------------------
    // Fingerprint / freshness
    // -----------------------------------------------------------------------

    @Test
    public void isUpToDate_matchingFingerprint_returnsTrue() {
        assertTrue(KBPersistence.isUpToDate(tempDir, "M4TestKB", "test-fingerprint-1"));
    }

    @Test
    public void isUpToDate_differentFingerprint_returnsFalse() {
        assertFalse(KBPersistence.isUpToDate(tempDir, "M4TestKB", "different-fingerprint"));
    }

    @Test
    public void isUpToDate_unknownKB_returnsFalse() {
        assertFalse(KBPersistence.isUpToDate(tempDir, "NoSuchKB", "anything"));
    }

    // -----------------------------------------------------------------------
    // formulaMap round-trip
    // -----------------------------------------------------------------------

    @Test
    public void loadKB_formulaMap_hasSameSize() {
        KB loaded = buildEmptyKB();
        boolean ok = KBPersistence.loadKB(tempDir, "M4TestKB", loaded);
        assertTrue("loadKB must return true", ok);
        assertEquals("formulaMap size must match cold build",
                coldKB.formulaMap.size(), loaded.formulaMap.size());
    }

    @Test
    public void loadKB_formulaMap_containsAllFormulas() {
        KB loaded = buildEmptyKB();
        KBPersistence.loadKB(tempDir, "M4TestKB", loaded);
        for (String key : coldKB.formulaMap.keySet()) {
            assertTrue("formulaMap must contain: " + key,
                    loaded.formulaMap.containsKey(key));
        }
    }

    @Test
    public void loadKB_formulaMap_sourceFilePreserved() {
        KB loaded = buildEmptyKB();
        KBPersistence.loadKB(tempDir, "M4TestKB", loaded);
        for (Map.Entry<String, Formula> e : coldKB.formulaMap.entrySet()) {
            Formula loadedF = loaded.formulaMap.get(e.getKey());
            assertNotNull("formula must be present: " + e.getKey(), loadedF);
            assertEquals("sourceFile must be preserved for: " + e.getKey(),
                    e.getValue().sourceFile, loadedF.sourceFile);
        }
    }

    // -----------------------------------------------------------------------
    // KBcache round-trip
    // -----------------------------------------------------------------------

    @Test
    public void loadKB_kbcache_relationsMatch() {
        KBcache loaded = buildAndLoad();
        assertEquals("relations must match", coldCache.relations, loaded.relations);
    }

    @Test
    public void loadKB_kbcache_transRelsMatch() {
        KBcache loaded = buildAndLoad();
        assertEquals("transRels must match", coldCache.transRels, loaded.transRels);
    }

    @Test
    public void loadKB_kbcache_instsMatch() {
        KBcache loaded = buildAndLoad();
        assertEquals("insts must match", coldCache.insts, loaded.insts);
    }

    @Test
    public void loadKB_kbcache_instanceOfMatch() {
        KBcache loaded = buildAndLoad();
        assertEquals("instanceOf must match", coldCache.instanceOf, loaded.instanceOf);
    }

    @Test
    public void loadKB_kbcache_directParentTermsMatch() {
        KBcache loaded = buildAndLoad();
        assertEquals("directParentTerms must match",
                coldCache.directParentTerms, loaded.directParentTerms);
    }

    @Test
    public void loadKB_kbcache_parentsMatch() {
        KBcache loaded = buildAndLoad();
        assertEquals("parents must match", coldCache.parents, loaded.parents);
    }

    @Test
    public void loadKB_kbcache_childrenMatch() {
        KBcache loaded = buildAndLoad();
        assertEquals("children must match", coldCache.children, loaded.children);
    }

    @Test
    public void loadKB_kbcache_signaturesMatch() {
        KBcache loaded = buildAndLoad();
        assertEquals("signatures must match", coldCache.signatures, loaded.signatures);
    }

    @Test
    public void loadKB_kbcache_valencesMatch() {
        KBcache loaded = buildAndLoad();
        // valences from the cold build include logOpValences pre-loaded in the constructor;
        // compare only the entries that were explicitly written by saveKBcache
        // (i.e. the intersection key set must be identical in value)
        for (String rel : coldCache.valences.keySet()) {
            Integer coldVal   = coldCache.valences.get(rel);
            Integer loadedVal = loaded.valences.get(rel);
            assertEquals("valence mismatch for " + rel, coldVal, loadedVal);
        }
    }

    @Test
    public void loadKB_kbcache_initializedFlagSet() {
        KBcache loaded = buildAndLoad();
        assertTrue("loaded KBcache.initialized must be true", loaded.initialized);
    }

    // -----------------------------------------------------------------------
    // TPTP round-trip
    // -----------------------------------------------------------------------

    @Test
    public void loadTptp_fof_restoredForAllFormulas() {
        KB loaded = buildEmptyKB();
        KBPersistence.loadKB(tempDir, "M4TestKB", loaded);
        boolean ok = KBPersistence.loadTptp(tempDir, "M4TestKB", loaded, "fof");
        assertTrue("loadTptp must return true when data is present", ok);
        // Every formula that had a FOF translation in the cold KB must have one in loaded
        for (Map.Entry<String, Formula> e : coldKB.formulaMap.entrySet()) {
            if (e.getValue().theFofFormulas.isEmpty()) continue;
            Formula loadedF = loaded.formulaMap.get(e.getKey());
            assertNotNull("formula must exist in loaded KB: " + e.getKey(), loadedF);
            assertFalse("theFofFormulas must be non-empty after load for: " + e.getKey(),
                    loadedF.theFofFormulas.isEmpty());
            assertEquals("theFofFormulas content must match for: " + e.getKey(),
                    e.getValue().theFofFormulas, loadedF.theFofFormulas);
        }
    }

    @Test
    public void loadTptp_tff_returnsfalse_whenNoData() {
        KB loaded = buildEmptyKB();
        KBPersistence.loadKB(tempDir, "M4TestKB", loaded);
        // We only saved FOF; TFF was never saved
        boolean ok = KBPersistence.loadTptp(tempDir, "M4TestKB", loaded, "tff");
        assertFalse("loadTptp must return false when no TFF data was saved", ok);
    }

    // -----------------------------------------------------------------------
    // computeFingerprint
    // -----------------------------------------------------------------------

    @Test
    public void computeFingerprint_sameInputs_sameResult() {
        List<File> files = Arrays.asList(new File("/tmp/a.kif"), new File("/tmp/b.kif"));
        File cfg = new File("/tmp/config.xml");
        String fp1 = KBPersistence.computeFingerprint(cfg, files);
        String fp2 = KBPersistence.computeFingerprint(cfg, files);
        assertEquals("fingerprint must be deterministic", fp1, fp2);
    }

    @Test
    public void computeFingerprint_nullConfig_doesNotThrow() {
        List<File> files = Collections.singletonList(new File("/tmp/test.kif"));
        String fp = KBPersistence.computeFingerprint(null, files);
        assertNotNull("fingerprint must not be null", fp);
    }

    @Test
    public void computeFingerprint_emptyList_doesNotThrow() {
        String fp = KBPersistence.computeFingerprint(new File("/tmp/cfg.xml"), Collections.emptyList());
        assertNotNull("fingerprint must not be null for empty file list", fp);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static KB buildEmptyKB() {
        KB kb = new KB("M4TestKB");
        kb.kbCache = new KBcache(kb);
        return kb;
    }

    /** Loads into a fresh KB+KBcache and returns the KBcache. */
    private static KBcache buildAndLoad() {
        KB loaded = buildEmptyKB();
        KBPersistence.loadKB(tempDir, "M4TestKB", loaded);
        return loaded.kbCache;
    }
}
