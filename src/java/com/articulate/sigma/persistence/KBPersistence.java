package com.articulate.sigma.persistence;

import com.articulate.sigma.*;
import com.articulate.sigma.utils.StringUtil;

import java.io.File;
import java.io.PrintWriter;
import java.sql.*;
import java.util.*;

/**
 * M4 — H2-based KB persistence.
 *
 * <p>Provides a reliable alternative to the broken Kryo serialisation by
 * storing formulaMap, KBcache, and TPTP translations in an embedded H2
 * database.  All public methods are NEW — no existing code is modified.</p>
 *
 * <h3>Warm-start workflow:</h3>
 * <ol>
 *   <li>Call {@link #computeFingerprint(String, List)} to hash config + .kif mtimes.</li>
 *   <li>Call {@link #isUpToDate(String, String, String)} to check if H2 is fresh.</li>
 *   <li>If fresh, call {@link #loadKB(String, String, KB)} and
 *       {@link #loadTptp(String, String, KB, String)} to hydrate in-memory state.</li>
 *   <li>If stale, do cold build then call {@link #saveKB(String, KB)} and
 *       {@link #saveTptp(String, KB, String)} to persist.</li>
 * </ol>
 *
 * <h3>Schema (all tables keyed by kb_name):</h3>
 * <ul>
 *   <li>{@code kb_meta}           — fingerprint and other metadata</li>
 *   <li>{@code formula_store}     — formulaMap entries with sourceFile</li>
 *   <li>{@code kbc_set}           — flat string sets (relations, transRels, insts)</li>
 *   <li>{@code kbc_str_set}       — map&lt;string, set&lt;string&gt;&gt; (instanceOf, directParentTerms)</li>
 *   <li>{@code kbc_rel_term_parent} — transitive parent/child maps</li>
 *   <li>{@code kbc_signatures}    — relation argument-type signatures</li>
 *   <li>{@code kbc_valences}      — relation arities</li>
 *   <li>{@code tptp_cache}        — pre-generated FOF / TFF translations</li>
 * </ul>
 */
public class KBPersistence {

    private static final String DB_FILE = "sigma_kb";

    // -----------------------------------------------------------------------
    // Connection helpers
    // -----------------------------------------------------------------------

    /**
     * Returns the JDBC URL for the embedded H2 file-backed database stored in
     * {@code kbDir}.  The database is opened in exclusive (non-server) mode.
     */
    public static String getDbUrl(String kbDir) {
        return "jdbc:h2:file:" + kbDir + File.separator + DB_FILE
                + ";AUTO_SERVER=FALSE;TRACE_LEVEL_FILE=0;TRACE_LEVEL_SYSTEM_OUT=0";
    }

    /**
     * Opens and returns a JDBC connection to the H2 database in {@code kbDir},
     * initialising the schema if this is the first use.
     */
    public static Connection openConnection(String kbDir) throws SQLException {
        try {
            Class.forName("org.h2.Driver");
        } catch (ClassNotFoundException e) {
            throw new SQLException("H2 driver not found on classpath", e);
        }
        Connection conn = DriverManager.getConnection(getDbUrl(kbDir), "sa", "");
        initSchema(conn);
        return conn;
    }

    // -----------------------------------------------------------------------
    // Schema initialisation
    // -----------------------------------------------------------------------

    /**
     * Creates all required tables if they do not yet exist.
     * Safe to call on every open (idempotent).
     */
    public static void initSchema(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            // Note: 'key', 'value', 'type' are reserved words in H2 — use prefixed names.
            st.execute("CREATE TABLE IF NOT EXISTS kb_meta (" +
                    "  kb_name  TEXT NOT NULL," +
                    "  meta_key TEXT NOT NULL," +
                    "  meta_val TEXT NOT NULL," +
                    "  PRIMARY KEY (kb_name, meta_key)" +
                    ")");
            st.execute("CREATE TABLE IF NOT EXISTS formula_store (" +
                    "  kb_name     TEXT NOT NULL," +
                    "  formula_str TEXT NOT NULL," +
                    "  source_file TEXT," +
                    "  PRIMARY KEY (kb_name, formula_str)" +
                    ")");
            st.execute("CREATE TABLE IF NOT EXISTS kbc_set (" +
                    "  kb_name  TEXT NOT NULL," +
                    "  set_name TEXT NOT NULL," +
                    "  set_val  TEXT NOT NULL," +
                    "  PRIMARY KEY (kb_name, set_name, set_val)" +
                    ")");
            st.execute("CREATE TABLE IF NOT EXISTS kbc_str_set (" +
                    "  kb_name  TEXT NOT NULL," +
                    "  map_name TEXT NOT NULL," +
                    "  map_key  TEXT NOT NULL," +
                    "  map_val  TEXT NOT NULL," +
                    "  PRIMARY KEY (kb_name, map_name, map_key, map_val)" +
                    ")");
            st.execute("CREATE TABLE IF NOT EXISTS kbc_rel_term_parent (" +
                    "  kb_name  TEXT NOT NULL," +
                    "  map_name TEXT NOT NULL," +
                    "  rel      TEXT NOT NULL," +
                    "  term     TEXT NOT NULL," +
                    "  parent   TEXT NOT NULL," +
                    "  PRIMARY KEY (kb_name, map_name, rel, term, parent)" +
                    ")");
            st.execute("CREATE TABLE IF NOT EXISTS kbc_signatures (" +
                    "  kb_name  TEXT NOT NULL," +
                    "  rel      TEXT NOT NULL," +
                    "  pos      INTEGER NOT NULL," +
                    "  arg_type TEXT NOT NULL," +
                    "  PRIMARY KEY (kb_name, rel, pos)" +
                    ")");
            st.execute("CREATE TABLE IF NOT EXISTS kbc_valences (" +
                    "  kb_name TEXT NOT NULL," +
                    "  rel     TEXT NOT NULL," +
                    "  valence INTEGER NOT NULL," +
                    "  PRIMARY KEY (kb_name, rel)" +
                    ")");
            st.execute("CREATE TABLE IF NOT EXISTS tptp_cache (" +
                    "  kb_name     TEXT NOT NULL," +
                    "  formula_str TEXT NOT NULL," +
                    "  lang        TEXT NOT NULL," +
                    "  tptp_text   TEXT NOT NULL," +
                    "  PRIMARY KEY (kb_name, formula_str, lang)" +
                    ")");
        }
    }

    // -----------------------------------------------------------------------
    // Fingerprinting
    // -----------------------------------------------------------------------

    /**
     * Computes a fingerprint string from the last-modified timestamps of the
     * given files.  The fingerprint changes whenever any constituent file or
     * the config file is updated.
     *
     * @param configFile  the config.xml {@link File} (may be null)
     * @param kifFiles    the constituent .kif {@link File}s
     * @return a deterministic hex string
     */
    public static String computeFingerprint(File configFile, List<File> kifFiles) {
        long hash = 0L;
        if (configFile != null)
            hash = mix(hash, configFile.getAbsolutePath().hashCode(), configFile.lastModified());
        for (File f : kifFiles)
            hash = mix(hash, f.getAbsolutePath().hashCode(), f.lastModified());
        return Long.toHexString(hash);
    }

    private static long mix(long acc, long a, long b) {
        // Simple deterministic hash mix — not cryptographic, just change detection
        long x = acc ^ (a * 0x9e3779b97f4a7c15L);
        x ^= (b * 0x517cc1b727220a95L);
        return x ^ (x >>> 30);
    }

    /**
     * Returns {@code true} if the H2 database in {@code kbDir} for KB
     * {@code kbName} exists and was saved with the same fingerprint as
     * {@code currentFingerprint}.
     */
    public static boolean isUpToDate(String kbDir, String kbName, String currentFingerprint) {
        File dbFile = new File(kbDir + File.separator + DB_FILE + ".mv.db");
        if (!dbFile.exists()) return false;
        try (Connection conn = openConnection(kbDir)) {
            String stored = getMetaValue(conn, kbName, "fingerprint");
            return currentFingerprint.equals(stored);
        } catch (SQLException e) {
            System.err.println("KBPersistence.isUpToDate(): " + e.getMessage());
            return false;
        }
    }

    // -----------------------------------------------------------------------
    // Save
    // -----------------------------------------------------------------------

    /**
     * Persists the formulaMap and KBcache of {@code kb} to the H2 database in
     * {@code kbDir}.  Replaces any prior data for this KB name.
     *
     * <p>Does NOT persist TPTP translations — call {@link #saveTptp} for that.</p>
     *
     * @param kbDir directory containing the H2 database file
     * @param kb    the knowledge base to persist (must have kbCache set)
     * @throws SQLException on database error
     */
    public static void saveKB(String kbDir, KB kb) throws SQLException {
        try (Connection conn = openConnection(kbDir)) {
            conn.setAutoCommit(false);
            deleteKBData(conn, kb.name);
            saveFormulas(conn, kb);
            saveKBcache(conn, kb);
            conn.commit();
        }
    }

    /**
     * Saves the generated TPTP translations (FOF or TFF) for all formulas in
     * {@code kb} that have a non-empty translation.
     *
     * @param kbDir  directory containing the H2 database file
     * @param kb     the knowledge base whose formula TPTP translations to save
     * @param lang   {@code "fof"} or {@code "tff"}
     * @throws SQLException on database error
     */
    public static void saveTptp(String kbDir, KB kb, String lang) throws SQLException {
        try (Connection conn = openConnection(kbDir)) {
            conn.setAutoCommit(false);
            String sql = "MERGE INTO tptp_cache (kb_name, formula_str, lang, tptp_text)" +
                    " KEY (kb_name, formula_str, lang) VALUES (?, ?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                int batch = 0;
                for (Formula f : kb.formulaMap.values()) {
                    Set<String> tptpSet = lang.equals("fof") ? f.theFofFormulas : f.theTffFormulas;
                    if (tptpSet.isEmpty()) continue;
                    StringBuilder sb = new StringBuilder();
                    for (String t : tptpSet) sb.append(t).append('\n');
                    ps.setString(1, kb.name);
                    ps.setString(2, f.getFormula());
                    ps.setString(3, lang);
                    ps.setString(4, sb.toString().trim());
                    ps.addBatch();
                    if (++batch % 500 == 0) ps.executeBatch();
                }
                ps.executeBatch();
            }
            conn.commit();
        }
    }

    /**
     * Stores the {@code fingerprint} associated with {@code kbName} in the
     * H2 metadata table so that {@link #isUpToDate} can verify freshness on
     * the next warm start.
     *
     * @param kbDir       directory containing the H2 database file
     * @param kbName      name of the knowledge base
     * @param fingerprint value returned by {@link #computeFingerprint}
     * @throws SQLException on database error
     */
    public static void saveFingerprint(String kbDir, String kbName, String fingerprint)
            throws SQLException {
        try (Connection conn = openConnection(kbDir)) {
            setMetaValue(conn, kbName, "fingerprint", fingerprint);
        }
    }

    // -----------------------------------------------------------------------
    // Load
    // -----------------------------------------------------------------------

    /**
     * Loads the formulaMap and KBcache from H2 into the provided {@code KB}
     * instance.  The KB's {@code kbCache} field must already be set to a fresh
     * {@link KBcache} instance.
     *
     * @param kbDir  directory containing the H2 database file
     * @param kbName name of the knowledge base to load
     * @param kb     target KB instance (formulaMap and kbCache are populated)
     * @return {@code true} if data was found and loaded, {@code false} otherwise
     */
    public static boolean loadKB(String kbDir, String kbName, KB kb) {
        try (Connection conn = openConnection(kbDir)) {
            if (!hasData(conn, kbName)) return false;
            loadFormulas(conn, kbName, kb);
            loadKBcache(conn, kbName, kb.kbCache);
            return true;
        } catch (SQLException e) {
            System.err.println("KBPersistence.loadKB(): " + e.getMessage());
            return false;
        }
    }

    /**
     * Loads pre-generated TPTP translations from H2 and populates the
     * {@code theFofFormulas} / {@code theTffFormulas} fields on each
     * {@link Formula} in {@code kb.formulaMap}.
     *
     * @param kbDir  directory containing the H2 database file
     * @param kbName name of the knowledge base
     * @param kb     the KB whose formulas are to be populated
     * @param lang   {@code "fof"} or {@code "tff"}
     * @return {@code true} if any translations were loaded, {@code false} if none
     */
    public static boolean loadTptp(String kbDir, String kbName, KB kb, String lang) {
        String sql = "SELECT formula_str, tptp_text FROM tptp_cache" +
                " WHERE kb_name=? AND lang=?";
        try (Connection conn = openConnection(kbDir);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, kbName);
            ps.setString(2, lang);
            int count = 0;
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String formulaStr = rs.getString(1);
                    String tptpText   = rs.getString(2);
                    Formula f = kb.formulaMap.get(formulaStr);
                    if (f == null) continue;
                    Set<String> tptpSet = lang.equals("fof") ? f.theFofFormulas : f.theTffFormulas;
                    tptpSet.clear();
                    for (String line : tptpText.split("\n"))
                        if (!line.isBlank()) tptpSet.add(line);
                    count++;
                }
            }
            return count > 0;
        } catch (SQLException e) {
            System.err.println("KBPersistence.loadTptp(): " + e.getMessage());
            return false;
        }
    }

    // -----------------------------------------------------------------------
    // Private — save helpers
    // -----------------------------------------------------------------------

    private static void deleteKBData(Connection conn, String kbName) throws SQLException {
        for (String table : new String[]{
                "formula_store", "kbc_set", "kbc_str_set",
                "kbc_rel_term_parent", "kbc_signatures", "kbc_valences"}) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM " + table + " WHERE kb_name=?")) {
                ps.setString(1, kbName);
                ps.executeUpdate();
            }
        }
    }

    private static void saveFormulas(Connection conn, KB kb) throws SQLException {
        String sql = "INSERT INTO formula_store (kb_name, formula_str, source_file)" +
                " VALUES (?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int batch = 0;
            for (Formula f : kb.formulaMap.values()) {
                ps.setString(1, kb.name);
                ps.setString(2, f.getFormula());
                ps.setString(3, f.sourceFile);
                ps.addBatch();
                if (++batch % 500 == 0) ps.executeBatch();
            }
            ps.executeBatch();
        }
    }

    private static void saveKBcache(Connection conn, KB kb) throws SQLException {
        KBcache cache = kb.kbCache;
        if (cache == null) return;

        // --- flat sets ---
        saveFlatSet(conn, kb.name, "relations",  cache.relations);
        saveFlatSet(conn, kb.name, "transRels",  cache.transRels);
        saveFlatSet(conn, kb.name, "insts",       cache.insts);
        saveFlatSet(conn, kb.name, "instTransRels", cache.instTransRels);

        // --- map<string, set<string>> ---
        saveStrSet(conn, kb.name, "instanceOf",       cache.instanceOf);
        saveStrSet(conn, kb.name, "directParentTerms", cache.directParentTerms);

        // --- parents and children map<rel, map<term, set<term>>> ---
        saveRelTermParent(conn, kb.name, "parents",  cache.parents);
        saveRelTermParent(conn, kb.name, "children", cache.children);

        // --- signatures ---
        saveSignatures(conn, kb.name, cache.signatures);

        // --- valences ---
        saveValences(conn, kb.name, cache.valences);
    }

    private static void saveFlatSet(Connection conn, String kbName,
                                    String setName, Set<String> values) throws SQLException {
        String sql = "INSERT INTO kbc_set (kb_name, set_name, set_val) VALUES (?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int batch = 0;
            for (String v : values) {
                if (StringUtil.emptyString(v)) continue;
                ps.setString(1, kbName);
                ps.setString(2, setName);
                ps.setString(3, v);
                ps.addBatch();
                if (++batch % 500 == 0) ps.executeBatch();
            }
            ps.executeBatch();
        }
    }

    private static void saveStrSet(Connection conn, String kbName,
                                   String mapName, Map<String, Set<String>> map) throws SQLException {
        String sql = "INSERT INTO kbc_str_set (kb_name, map_name, map_key, map_val) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int batch = 0;
            for (Map.Entry<String, Set<String>> e : map.entrySet()) {
                String k = e.getKey();
                for (String v : e.getValue()) {
                    if (StringUtil.emptyString(k) || StringUtil.emptyString(v)) continue;
                    ps.setString(1, kbName);
                    ps.setString(2, mapName);
                    ps.setString(3, k);
                    ps.setString(4, v);
                    ps.addBatch();
                    if (++batch % 500 == 0) ps.executeBatch();
                }
            }
            ps.executeBatch();
        }
    }

    // Sentinels used to preserve empty map/set structure across save/load cycles.
    // These values cannot appear as real SUMO terms.
    private static final String SENTINEL_EMPTY_MAP = "\u0000EMPTY_MAP";
    private static final String SENTINEL_EMPTY_SET = "\u0000EMPTY_SET";

    private static void saveRelTermParent(Connection conn, String kbName,
                                          String mapName,
                                          Map<String, Map<String, Set<String>>> outerMap) throws SQLException {
        String sql = "INSERT INTO kbc_rel_term_parent" +
                " (kb_name, map_name, rel, term, parent) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int batch = 0;
            for (Map.Entry<String, Map<String, Set<String>>> relEntry : outerMap.entrySet()) {
                String rel = relEntry.getKey();
                Map<String, Set<String>> innerMap = relEntry.getValue();
                if (innerMap.isEmpty()) {
                    // Preserve the outer key even with no inner entries
                    ps.setString(1, kbName); ps.setString(2, mapName);
                    ps.setString(3, rel); ps.setString(4, SENTINEL_EMPTY_MAP);
                    ps.setString(5, SENTINEL_EMPTY_MAP);
                    ps.addBatch(); if (++batch % 500 == 0) ps.executeBatch();
                    continue;
                }
                for (Map.Entry<String, Set<String>> termEntry : innerMap.entrySet()) {
                    String term = termEntry.getKey();
                    Set<String> parents = termEntry.getValue();
                    if (parents.isEmpty()) {
                        // Preserve the inner key even with an empty parent set
                        ps.setString(1, kbName); ps.setString(2, mapName);
                        ps.setString(3, rel); ps.setString(4, term);
                        ps.setString(5, SENTINEL_EMPTY_SET);
                        ps.addBatch(); if (++batch % 500 == 0) ps.executeBatch();
                        continue;
                    }
                    for (String parent : parents) {
                        if (StringUtil.emptyString(parent)) continue;
                        ps.setString(1, kbName);
                        ps.setString(2, mapName);
                        ps.setString(3, rel);
                        ps.setString(4, term);
                        ps.setString(5, parent);
                        ps.addBatch();
                        if (++batch % 500 == 0) ps.executeBatch();
                    }
                }
            }
            ps.executeBatch();
        }
    }

    private static void saveSignatures(Connection conn, String kbName,
                                       Map<String, List<String>> signatures) throws SQLException {
        String sql = "INSERT INTO kbc_signatures (kb_name, rel, pos, arg_type) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int batch = 0;
            for (Map.Entry<String, List<String>> e : signatures.entrySet()) {
                List<String> types = e.getValue();
                for (int i = 0; i < types.size(); i++) {
                    String t = types.get(i);
                    if (t == null) t = "";
                    ps.setString(1, kbName);
                    ps.setString(2, e.getKey());
                    ps.setInt(3, i);
                    ps.setString(4, t);
                    ps.addBatch();
                    if (++batch % 500 == 0) ps.executeBatch();
                }
            }
            ps.executeBatch();
        }
    }

    private static void saveValences(Connection conn, String kbName,
                                     Map<String, Integer> valences) throws SQLException {
        String sql = "INSERT INTO kbc_valences (kb_name, rel, valence) VALUES (?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int batch = 0;
            for (Map.Entry<String, Integer> e : valences.entrySet()) {
                if (StringUtil.emptyString(e.getKey())) continue;
                ps.setString(1, kbName);
                ps.setString(2, e.getKey());
                ps.setInt(3, e.getValue());
                ps.addBatch();
                if (++batch % 500 == 0) ps.executeBatch();
            }
            ps.executeBatch();
        }
    }

    // -----------------------------------------------------------------------
    // Private — load helpers
    // -----------------------------------------------------------------------

    private static boolean hasData(Connection conn, String kbName) throws SQLException {
        String sql = "SELECT COUNT(*) FROM formula_store WHERE kb_name=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, kbName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    private static void loadFormulas(Connection conn, String kbName, KB kb) throws SQLException {
        String sql = "SELECT formula_str, source_file FROM formula_store WHERE kb_name=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, kbName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String formulaStr = rs.getString(1);
                    String sourceFile = rs.getString(2);
                    // Only restore metadata for formulas already parsed by addConstituent().
                    // Do NOT create plain Formula objects for H2 entries absent from the current
                    // constituent set — those are stale rows from a prior full-KB run and would
                    // pollute formulaMap with plain Formula objects that have no Expr tree.
                    Formula f = kb.formulaMap.get(formulaStr);
                    if (f != null)
                        f.sourceFile = sourceFile;
                }
            }
        }
    }

    private static void loadKBcache(Connection conn, String kbName, KBcache cache) throws SQLException {
        // flat sets
        loadFlatSet(conn, kbName, "relations",     cache.relations);
        loadFlatSet(conn, kbName, "transRels",     cache.transRels);
        loadFlatSet(conn, kbName, "insts",          cache.insts);
        loadFlatSet(conn, kbName, "instTransRels",  cache.instTransRels);

        // map<string, set<string>>
        loadStrSet(conn, kbName, "instanceOf",       cache.instanceOf);
        loadStrSet(conn, kbName, "directParentTerms", cache.directParentTerms);

        // parents and children
        loadRelTermParent(conn, kbName, "parents",  cache.parents);
        loadRelTermParent(conn, kbName, "children", cache.children);

        // signatures
        loadSignatures(conn, kbName, cache.signatures);

        // valences
        loadValences(conn, kbName, cache.valences);

        cache.initialized = true;
    }

    private static void loadFlatSet(Connection conn, String kbName,
                                    String setName, Set<String> target) throws SQLException {
        String sql = "SELECT set_val FROM kbc_set WHERE kb_name=? AND set_name=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, kbName);
            ps.setString(2, setName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) target.add(rs.getString(1));
            }
        }
    }

    private static void loadStrSet(Connection conn, String kbName,
                                   String mapName, Map<String, Set<String>> target) throws SQLException {
        String sql = "SELECT map_key, map_val FROM kbc_str_set WHERE kb_name=? AND map_name=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, kbName);
            ps.setString(2, mapName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String key = rs.getString(1);
                    String val = rs.getString(2);
                    target.computeIfAbsent(key, k -> new HashSet<>()).add(val);
                }
            }
        }
    }

    private static void loadRelTermParent(Connection conn, String kbName,
                                          String mapName,
                                          Map<String, Map<String, Set<String>>> target) throws SQLException {
        String sql = "SELECT rel, term, parent FROM kbc_rel_term_parent" +
                " WHERE kb_name=? AND map_name=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, kbName);
            ps.setString(2, mapName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String rel    = rs.getString(1);
                    String term   = rs.getString(2);
                    String parent = rs.getString(3);
                    if (SENTINEL_EMPTY_MAP.equals(term)) {
                        // rel key exists but inner map is empty
                        target.computeIfAbsent(rel, r -> new HashMap<>());
                    } else if (SENTINEL_EMPTY_SET.equals(parent)) {
                        // term key exists but parent set is empty
                        target.computeIfAbsent(rel, r -> new HashMap<>())
                              .computeIfAbsent(term, t -> new HashSet<>());
                    } else {
                        target.computeIfAbsent(rel,  r -> new HashMap<>())
                              .computeIfAbsent(term, t -> new HashSet<>())
                              .add(parent);
                    }
                }
            }
        }
    }

    private static void loadSignatures(Connection conn, String kbName,
                                       Map<String, List<String>> target) throws SQLException {
        String sql = "SELECT rel, pos, arg_type FROM kbc_signatures WHERE kb_name=? ORDER BY rel, pos";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, kbName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String rel  = rs.getString(1);
                    int    pos  = rs.getInt(2);
                    String type = rs.getString(3);
                    List<String> list = target.computeIfAbsent(rel, r -> new ArrayList<>());
                    while (list.size() <= pos) list.add("");
                    list.set(pos, type);
                }
            }
        }
    }

    private static void loadValences(Connection conn, String kbName,
                                     Map<String, Integer> target) throws SQLException {
        String sql = "SELECT rel, valence FROM kbc_valences WHERE kb_name=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, kbName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next())
                    target.put(rs.getString(1), rs.getInt(2));
            }
        }
    }

    // -----------------------------------------------------------------------
    // Private — metadata helpers
    // -----------------------------------------------------------------------

    private static String getMetaValue(Connection conn, String kbName, String metaKey)
            throws SQLException {
        String sql = "SELECT meta_val FROM kb_meta WHERE kb_name=? AND meta_key=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, kbName);
            ps.setString(2, metaKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static void setMetaValue(Connection conn, String kbName, String metaKey, String metaVal)
            throws SQLException {
        String sql = "MERGE INTO kb_meta (kb_name, meta_key, meta_val)" +
                " KEY (kb_name, meta_key) VALUES (?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, kbName);
            ps.setString(2, metaKey);
            ps.setString(3, metaVal);
            ps.executeUpdate();
        }
    }

    // -----------------------------------------------------------------------
    // M7 — streaming base TPTP for copy-on-write session regeneration
    // -----------------------------------------------------------------------

    /**
     * Streams all pre-translated TPTP axioms for {@code kbName}/{@code lang}
     * from the H2 database, writing each as a full axiom declaration to {@code pw}.
     *
     * <p>Each stored formula body is emitted as:
     * <pre>
     *   lang(kb_kbName_N,axiom,(body)).
     * </pre>
     * This is the M7 building block for {@code SessionTPTPManager.regenerateSessionFromH2()},
     * which streams the base KB from H2 then appends session-specific assertions.
     *
     * @param kbDir   directory containing the H2 database file
     * @param kbName  name of the knowledge base
     * @param lang    {@code "fof"} or {@code "tff"}
     * @param pw      writer to receive the axiom lines
     * @return number of axioms written (0 if no data found or on error)
     */
    public static int streamBaseTPTP(String kbDir, String kbName, String lang, PrintWriter pw) {
        // Normalize "fof" lang — the file extension is "tptp" but the stored lang tag is "fof"
        String storedLang = lang.equals("tptp") ? "fof" : lang;
        String fileTag    = storedLang.equals("fof") ? "tptp" : storedLang; // for axiom prefix
        String sql = "SELECT tptp_text FROM tptp_cache WHERE kb_name=? AND lang=? ORDER BY rownum() ASC";
        try (Connection conn = openConnection(kbDir);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, kbName);
            ps.setString(2, storedLang);
            // Use a large fetch size to avoid many round-trips for large KBs
            ps.setFetchSize(1000);
            int count = 0;
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String tptpText = rs.getString(1);
                    if (tptpText == null || tptpText.isBlank()) continue;
                    for (String body : tptpText.split("\n")) {
                        if (body.isBlank()) continue;
                        String name = "kb_" + sanitize(kbName) + "_base_" + count;
                        pw.println(storedLang + "(" + name + ",axiom,(" + body + ")).");
                        count++;
                    }
                }
            }
            return count;
        } catch (SQLException e) {
            System.err.println("KBPersistence.streamBaseTPTP(): " + e.getMessage());
            return 0;
        }
    }

    /** Sanitize a KB name for use in TPTP axiom names (replace non-word chars with _). */
    private static String sanitize(String kbName) {
        return kbName.replaceAll("[^A-Za-z0-9]", "_");
    }
}
