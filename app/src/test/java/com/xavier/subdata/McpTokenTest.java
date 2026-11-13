package com.xavier.subdata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Bearer token：生成、校验、落盘、轮换。 */
public class McpTokenTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void generateIsUrlSafeAndLongEnough() {
        for (int i = 0; i < 50; i++) {
            String t = McpToken.generate();
            assertEquals(43, t.length());
            assertTrue(McpToken.valid(t));
            assertFalse(t.contains("+"));
            assertFalse(t.contains("/"));
            assertFalse(t.contains("="));
        }
    }

    @Test
    public void generateIsRandom() {
        assertFalse(McpToken.generate().equals(McpToken.generate()));
    }

    @Test
    public void validRejectsBadInput() {
        assertFalse(McpToken.valid(null));
        assertFalse(McpToken.valid(""));
        assertFalse(McpToken.valid("short"));
        assertFalse(McpToken.valid("has space in token value here"));
        assertFalse(McpToken.valid("trailing\nnewline-value-1234567890"));
        assertFalse(McpToken.valid(repeat('a', 129)));
        assertTrue(McpToken.valid(repeat('a', 43)));
    }

    @Test
    public void matchesIsExact() {
        String t = McpToken.generate();
        assertTrue(McpToken.matches(t, t));
        assertFalse(McpToken.matches(t, t + "x"));
        assertFalse(McpToken.matches(null, t));
        assertFalse(McpToken.matches(t, null));
    }

    @Test
    public void fromAuthorizationVariants() {
        assertEquals("abc", McpToken.fromAuthorization("Bearer abc"));
        assertEquals("abc", McpToken.fromAuthorization("bearer abc"));
        assertEquals("abc", McpToken.fromAuthorization("  BEARER   abc  "));
        assertNull(McpToken.fromAuthorization(null));
        assertNull(McpToken.fromAuthorization("abc"));
        assertNull(McpToken.fromAuthorization("Basic abc"));
        assertNull(McpToken.fromAuthorization("Bearer "));
    }

    @Test
    public void writeReadRoundTrip() throws Exception {
        File dir = tmp.newFolder("files");
        String token = McpToken.generate();
        assertTrue(McpToken.write(McpToken.file(dir), token));
        assertEquals(token, McpToken.read(dir));
        assertEquals(token, new String(Files.readAllBytes(
                McpToken.file(dir).toPath()), StandardCharsets.UTF_8).trim());
    }

    @Test
    public void loadOrCreateCreatesThenReuses() throws Exception {
        File dir = tmp.newFolder("files");
        String first = McpToken.loadOrCreate(dir);
        assertNotNull(first);
        assertTrue(McpToken.file(dir).isFile());
        assertEquals(first, McpToken.loadOrCreate(dir));
    }

    @Test
    public void resetChangesToken() throws Exception {
        File dir = tmp.newFolder("files");
        String first = McpToken.loadOrCreate(dir);
        String second = McpToken.reset(dir);
        assertFalse(first.equals(second));
        assertEquals(second, McpToken.read(dir));
    }

    @Test
    public void readReturnsNullWhenMissingOrCorrupt() throws Exception {
        File dir = tmp.newFolder("files");
        assertNull(McpToken.read(dir));
        File f = McpToken.file(dir);
        Files.write(f.toPath(), "bad token with spaces".getBytes(StandardCharsets.UTF_8));
        assertNull(McpToken.read(dir));
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(c);
        }
        return sb.toString();
    }
}
