package com.articulate.sigma.trans;

import com.articulate.sigma.*;
import com.articulate.sigma.persistence.KBPersistence;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static org.junit.Assert.*;

/**
 * M7 — Tests for {@link SessionTPTPManager#regenerateSessionFromH2} and
 * {@link KBPersistence#streamBaseTPTP}.
 *
 * <p>Uses a temp-directory H2 database (same pattern as {@code KBPersistenceTest})
 * populated with a small KB and synthetic FOF translations.  All tests run without
 * a live Tomcat or prover installation.</p>
 */
public class RegenerateFromH2Test {

    private static final String[] KB_STMTS = {
            "(subclass Dog Animal)",
            "(subclass Cat Animal)",
            "(instance Fido Dog)",
            "(instance Felix Cat)",
            "(subclass Animal Entity)",
    };

    // Synthetic FOF bodies — one per formula (mirrors what _writeFile populates)
    // The actual body content is arbitrary for persistence-round-trip tests.
    private static final Map<String, String> SYNTHETIC_FOF = new LinkedHashMap<>();
    static {
        SYNTHETIC_FOF.put("(subclass Dog Animal)",  "s__subclass(s__Dog,s__Animal)");
        SYNTHETIC_FOF.put("(subclass Cat Animal)",  "s__subclass(s__Cat,s__Animal)");
        SYNTHETIC_FOF.put("(instance Fido Dog)",    "s__instance(s__Fido,s__Dog)");
        SYNTHETIC_FOF.put("(instance Felix Cat)",   "s__instance(s__Felix,s__Cat)");
        SYNTHETIC_FOF.put("(subclass Animal Entity)","s__subclass(s__Animal,s__Entity)");
    }

    private static String    tempDir;
    private static KB        coldKB;

    @BeforeClass
    public static void setUpClass() throws Exception {
        Path tmp = Files.createTempDirectory("sigma_m7_test");
        tempDir = tmp.toAbsolutePath().toString();

        KBmanager.getMgr().setPref("cacheDisjoint", "false");
        coldKB = new KB("M7TestKB");
        coldKB.kbCache = new KBcache(coldKB);
        KIF kif = new KIF();
        for (String stmt : KB_STMTS)
            kif.parseStatement(stmt);
        coldKB.merge(kif, "");
        for (Formula f : coldKB.formulaMap.values())
            f.sourceFile = "test";
        KBcache cache = new KBcache(coldKB);
        coldKB.kbCache = cache;
        cache.buildCaches();

        // Attach synthetic FOF bodies to the in-memory formulas
        for (Map.Entry<String, String> e : SYNTHETIC_FOF.entrySet()) {
            Formula f = coldKB.formulaMap.get(e.getKey());
            if (f != null) f.theFofFormulas.add(e.getValue());
        }

        // Persist to H2
        KBPersistence.saveKB(tempDir, coldKB);
        KBPersistence.saveTptp(tempDir, coldKB, "fof");
        KBPersistence.saveFingerprint(tempDir, "M7TestKB", "m7-fingerprint");
    }

    @AfterClass
    public static void tearDown() {
        File dir = new File(tempDir);
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) f.delete();
        dir.delete();
    }

    // -----------------------------------------------------------------------
    // streamBaseTPTP — KBPersistence
    // -----------------------------------------------------------------------

    @Test
    public void streamBaseTPTP_writesAllFormulas() {
        StringWriter sw = new StringWriter();
        try (PrintWriter pw = new PrintWriter(sw)) {
            int count = KBPersistence.streamBaseTPTP(tempDir, "M7TestKB", "fof", pw);
            assertEquals("count must equal number of FOF-translated formulas",
                    SYNTHETIC_FOF.size(), count);
        }
        String output = sw.toString();
        assertFalse("output must not be empty", output.isBlank());
    }

    @Test
    public void streamBaseTPTP_eachLineLooksLikeAxiomDecl() {
        StringWriter sw = new StringWriter();
        try (PrintWriter pw = new PrintWriter(sw)) {
            KBPersistence.streamBaseTPTP(tempDir, "M7TestKB", "fof", pw);
        }
        String output = sw.toString();
        for (String line : output.split("\n")) {
            if (line.isBlank()) continue;
            assertTrue("each line must start with 'fof(' : [" + line + "]",
                    line.startsWith("fof("));
            assertTrue("each line must end with ').' : [" + line + "]",
                    line.endsWith(")."));
        }
    }

    @Test
    public void streamBaseTPTP_containsAllBodies() {
        StringWriter sw = new StringWriter();
        try (PrintWriter pw = new PrintWriter(sw)) {
            KBPersistence.streamBaseTPTP(tempDir, "M7TestKB", "fof", pw);
        }
        String output = sw.toString();
        for (String body : SYNTHETIC_FOF.values()) {
            assertTrue("output must contain FOF body: " + body,
                    output.contains(body));
        }
    }

    @Test
    public void streamBaseTPTP_tffLang_returnsZero_whenNoTffData() {
        StringWriter sw = new StringWriter();
        try (PrintWriter pw = new PrintWriter(sw)) {
            int count = KBPersistence.streamBaseTPTP(tempDir, "M7TestKB", "tff", pw);
            assertEquals("no TFF data was saved, so count must be 0", 0, count);
        }
    }

    @Test
    public void streamBaseTPTP_normalizesLangAlias() {
        // "tptp" is an alias for "fof" in file naming — stored as "fof" in H2
        StringWriter sw = new StringWriter();
        try (PrintWriter pw = new PrintWriter(sw)) {
            int count = KBPersistence.streamBaseTPTP(tempDir, "M7TestKB", "tptp", pw);
            assertEquals("'tptp' lang alias must resolve to 'fof' in H2",
                    SYNTHETIC_FOF.size(), count);
        }
    }

    // -----------------------------------------------------------------------
    // translateSessionAssertion — package-visible helper
    // -----------------------------------------------------------------------

    @Test
    public void translateSessionAssertion_fof_groundAtom() {
        String result = SessionTPTPManager.translateSessionAssertion(
                "(instance Fido Dog)", "fof", coldKB.kbCache);
        assertNotNull("FOF translation must not be null", result);
        assertFalse("FOF translation must not be empty", result.isBlank());
        assertTrue("FOF result should contain s__instance",
                result.contains("s__instance") || result.contains("instance"));
    }

    @Test
    public void translateSessionAssertion_tff_groundAtom() {
        String result = SessionTPTPManager.translateSessionAssertion(
                "(instance Fido Dog)", "tff", coldKB.kbCache);
        assertNotNull("TFF translation must not be null", result);
        assertFalse("TFF translation must not be empty", result.isBlank());
    }

    @Test
    public void translateSessionAssertion_null_returnsNull() {
        assertNull(SessionTPTPManager.translateSessionAssertion(null, "fof", coldKB.kbCache));
    }

    @Test
    public void translateSessionAssertion_blank_returnsNull() {
        assertNull(SessionTPTPManager.translateSessionAssertion("   ", "fof", coldKB.kbCache));
    }

    // -----------------------------------------------------------------------
    // regenerateSessionFromH2 — full pipeline (uses mocked kbDir + session dir)
    // -----------------------------------------------------------------------

    @Test
    public void regenerateFromH2_nullSession_returnsNull() {
        // Without touching KBmanager, just verify null guard
        Path result = SessionTPTPManager.regenerateSessionFromH2(null, coldKB, "fof", null);
        assertNull("null sessionId must return null", result);
    }

    @Test
    public void regenerateFromH2_emptySession_returnsNull() {
        Path result = SessionTPTPManager.regenerateSessionFromH2("", coldKB, "fof", null);
        assertNull("empty sessionId must return null", result);
    }

    @Test
    public void regenerateFromH2_withFofData_createsFile() throws Exception {
        // Point KBmanager kbDir at our temp dir so getSessionDir() works
        KBmanager.getMgr().setPref("kbDir", tempDir);
        String sessionId = "test-session-m7-fof";
        try {
            Path result = SessionTPTPManager.regenerateSessionFromH2(
                    sessionId, coldKB, "fof", null);
            assertNotNull("must return a non-null path", result);
            assertTrue("session file must exist", Files.exists(result));

            String content = Files.readString(result, StandardCharsets.UTF_8);
            assertTrue("file must contain base KB marker", content.contains("--- Base KB"));
            assertTrue("file must contain at least one axiom line", content.contains("fof("));
        } finally {
            SessionTPTPManager.cleanupSession(sessionId);
        }
    }

    @Test
    public void regenerateFromH2_withSessionAssertions_appendsSessionSection() throws Exception {
        KBmanager.getMgr().setPref("kbDir", tempDir);
        String sessionId = "test-session-m7-ua";
        try {
            // Write a session UA KIF file with one assertion
            Path sessionDir = SessionTPTPManager.getSessionDir(sessionId);
            Files.createDirectories(sessionDir);
            Path uaKif = SessionTPTPManager.getSessionUAPath(sessionId, coldKB.name);
            Files.writeString(uaKif, "(instance Fido Dog)\n", StandardCharsets.UTF_8);

            Path result = SessionTPTPManager.regenerateSessionFromH2(
                    sessionId, coldKB, "fof", null);
            assertNotNull("must return a non-null path when UA file present", result);
            assertTrue("session file must exist", Files.exists(result));

            String content = Files.readString(result, StandardCharsets.UTF_8);
            assertTrue("file must contain session assertions marker",
                    content.contains("--- Session assertions"));
            // The session axiom (instance Fido Dog) translation should appear
            assertTrue("file must contain at least one session-specific axiom line",
                    content.contains("_s" + Math.abs("test-session-m7-ua".hashCode())));
        } finally {
            SessionTPTPManager.cleanupSession(sessionId);
        }
    }

    @Test
    public void regenerateFromH2_producesAtomicWrite_noTmpLeftover() throws Exception {
        KBmanager.getMgr().setPref("kbDir", tempDir);
        String sessionId = "test-session-m7-atomic";
        try {
            Path result = SessionTPTPManager.regenerateSessionFromH2(
                    sessionId, coldKB, "fof", null);
            // Verify no .tmp file is left behind
            if (result != null) {
                Path tmp = result.resolveSibling(result.getFileName() + ".tmp");
                assertFalse("no .tmp file must remain after successful write",
                        Files.exists(tmp));
            }
        } finally {
            SessionTPTPManager.cleanupSession(sessionId);
        }
    }

    @Test
    public void regenerateFromH2_idempotent_secondCallOverwrites() throws Exception {
        KBmanager.getMgr().setPref("kbDir", tempDir);
        String sessionId = "test-session-m7-idem";
        try {
            Path r1 = SessionTPTPManager.regenerateSessionFromH2(sessionId, coldKB, "fof", null);
            Path r2 = SessionTPTPManager.regenerateSessionFromH2(sessionId, coldKB, "fof", null);
            assertNotNull(r1);
            assertNotNull(r2);
            assertEquals("both calls must produce the same path", r1, r2);
            assertTrue("file must still exist after second call", Files.exists(r2));
        } finally {
            SessionTPTPManager.cleanupSession(sessionId);
        }
    }
}
