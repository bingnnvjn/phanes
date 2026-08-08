package com.gph.fable.app.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.gph.fable.app.session.RecentSessionStore.RecentSession;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 缝 2（JVM）：最近会话记录纯逻辑（编码/排序/上限/目录回退）+ SharedPreferences 持久化。 */
public class RecentSessionStoreTest {

    @Rule
    public TemporaryFolder mTmpDir = new TemporaryFolder();

    /** 内存版存储，绕开 Robolectric 4.8.1 + JDK 25 基线崩溃。 */
    private static final class MemoryStore implements RecentSessionStore.StringStore {
        String value;

        @Override
        public String read() {
            return value;
        }

        @Override
        public void write(String value) {
            this.value = value;
        }
    }

    private static RecentSession session(long timestamp, String workDir) {
        return new RecentSession(timestamp, workDir);
    }

    @Test
    public void encodeDecodeRoundTrip() {
        List<RecentSession> sessions = Arrays.asList(
            session(1000L, "/data/data/com.gph.fable/files/home/CODEX/Fable"),
            session(2000L, "/data/data/com.gph.fable/files/home/a+b%c/中文 目录\n换行"),
            session(3000L, "/")
        );

        String encoded = RecentSessionStore.encode(sessions);
        List<RecentSession> decoded = RecentSessionStore.decode(encoded);

        assertEquals(sessions.size(), decoded.size());
        for (int i = 0; i < sessions.size(); i++) {
            assertEquals(sessions.get(i).timestamp, decoded.get(i).timestamp);
            assertEquals(sessions.get(i).workingDirectory, decoded.get(i).workingDirectory);
        }
    }

    @Test
    public void upsertMovesExistingEntryToFrontWithNewTimestamp() {
        List<RecentSession> sessions = new ArrayList<>(Arrays.asList(
            session(100L, "/dir/a"),
            session(200L, "/dir/b"),
            session(300L, "/dir/c")
        ));

        List<RecentSession> updated = RecentSessionStore.upsert(sessions, "/dir/b", 999L);

        assertEquals(3, updated.size());
        assertEquals("/dir/b", updated.get(0).workingDirectory);
        assertEquals(999L, updated.get(0).timestamp);
        assertEquals("/dir/a", updated.get(1).workingDirectory);
        assertEquals("/dir/c", updated.get(2).workingDirectory);
    }

    @Test
    public void upsertAddsNewEntryAtFront() {
        List<RecentSession> sessions = new ArrayList<>(Arrays.asList(
            session(100L, "/dir/a")
        ));

        List<RecentSession> updated = RecentSessionStore.upsert(sessions, "/dir/b", 500L);

        assertEquals(2, updated.size());
        assertEquals("/dir/b", updated.get(0).workingDirectory);
        assertEquals("/dir/a", updated.get(1).workingDirectory);
    }

    @Test
    public void upsertCapsAtMaxEntries() {
        List<RecentSession> sessions = new ArrayList<>();
        for (int i = 0; i < RecentSessionStore.MAX_ENTRIES; i++) {
            sessions.add(session(i, "/dir/" + i));
        }

        List<RecentSession> updated = RecentSessionStore.upsert(sessions, "/dir/new", 999L);

        assertEquals(RecentSessionStore.MAX_ENTRIES, updated.size());
        assertEquals("/dir/new", updated.get(0).workingDirectory);
        // 最旧的 /dir/9 被挤出，/dir/8 成为最末。
        assertEquals("/dir/8", updated.get(updated.size() - 1).workingDirectory);
    }

    @Test
    public void decodeSkipsMalformedLines() {
        String raw = "1000\t%2Fdir%2Fa\nnot-a-timestamp\t%2Fdir%2Fb\n2000\t%2Fdir%2Fc\ngarbage\n3000\n";

        List<RecentSession> decoded = RecentSessionStore.decode(raw);

        assertEquals(2, decoded.size());
        assertEquals(1000L, decoded.get(0).timestamp);
        assertEquals("/dir/a", decoded.get(0).workingDirectory);
        assertEquals(2000L, decoded.get(1).timestamp);
        assertEquals("/dir/c", decoded.get(1).workingDirectory);
    }

    @Test
    public void resolveFallsBackToHomeWhenDirectoryInvalid() throws Exception {
        File validDir = mTmpDir.newFolder("valid");
        String home = "/data/data/com.gph.fable/files/home";

        assertEquals(validDir.getAbsolutePath(),
            RecentSessionStore.resolveWorkingDirectory(validDir.getAbsolutePath(), home));
        assertEquals(home,
            RecentSessionStore.resolveWorkingDirectory(validDir.getAbsolutePath() + "/missing", home));
        assertEquals(home,
            RecentSessionStore.resolveWorkingDirectory(null, home));
        assertEquals(home,
            RecentSessionStore.resolveWorkingDirectory("", home));
    }

    @Test
    public void recordAndLoadPersistAcrossInstances() {
        MemoryStore store = new MemoryStore();
        RecentSessionStore.record(store, "/work/dir/a");
        RecentSessionStore.record(store, "/work/dir/b");

        List<RecentSession> loaded = RecentSessionStore.load(store);

        assertEquals(2, loaded.size());
        assertEquals("/work/dir/b", loaded.get(0).workingDirectory);
        assertEquals("/work/dir/a", loaded.get(1).workingDirectory);
        assertTrue(loaded.get(0).timestamp >= loaded.get(1).timestamp);

        RecentSessionStore.remove(store, "/work/dir/a");
        assertEquals(1, RecentSessionStore.load(store).size());
    }
}
