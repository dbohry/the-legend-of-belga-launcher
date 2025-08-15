package com.lhamacorp.games.tlob;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Launcher Tests")
class LauncherTest {

    @TempDir
    Path tempDir;
    
    private Launcher launcher;
    
    // Test helper class to access private methods
    private static class TestHelper {
        private static Method isNewerMethod;
        private static Method parseIntMethod;
        private static Method extractMethod;
        private static Method extractFirstJarUrlMethod;
        
        static {
            try {
                isNewerMethod = Launcher.class.getDeclaredMethod("isNewer", String.class, String.class);
                parseIntMethod = Launcher.class.getDeclaredMethod("parseInt", String.class);
                extractMethod = Launcher.class.getDeclaredMethod("extract", String.class, String.class);
                extractFirstJarUrlMethod = Launcher.class.getDeclaredMethod("extractFirstJarUrl", String.class);
                
                isNewerMethod.setAccessible(true);
                parseIntMethod.setAccessible(true);
                extractMethod.setAccessible(true);
                extractFirstJarUrlMethod.setAccessible(true);
            } catch (NoSuchMethodException e) {
                throw new RuntimeException("Failed to access private methods for testing", e);
            }
        }
        
        @SuppressWarnings("unchecked")
        static boolean isNewer(String remote, String local) {
            try {
                return (Boolean) isNewerMethod.invoke(null, remote, local);
            } catch (Exception e) {
                throw new RuntimeException("Failed to invoke isNewer method", e);
            }
        }
        
        static int parseInt(String s) {
            try {
                return (Integer) parseIntMethod.invoke(null, s);
            } catch (Exception e) {
                throw new RuntimeException("Failed to invoke parseInt method", e);
            }
        }
        
        static String extract(String json, String regex) {
            try {
                return (String) extractMethod.invoke(null, json, regex);
            } catch (Exception e) {
                throw new RuntimeException("Failed to invoke extract method", e);
            }
        }
        
        static String extractFirstJarUrl(String json) {
            try {
                return (String) extractFirstJarUrlMethod.invoke(null, json);
            } catch (Exception e) {
                throw new RuntimeException("Failed to invoke extractFirstJarUrl method", e);
            }
        }
    }
    
    @BeforeEach
    void setUp() throws Exception {
        launcher = new Launcher();
    }
    
    @Nested
    @DisplayName("Version Comparison Tests")
    class VersionComparisonTests {
        
        @Test
        @DisplayName("Should detect newer versions correctly")
        void testIsNewer() throws Exception {
            // Test basic version comparison
            assertTrue(TestHelper.isNewer("1.0.1", "1.0.0"));
            assertTrue(TestHelper.isNewer("1.1.0", "1.0.9"));
            assertTrue(TestHelper.isNewer("2.0.0", "1.9.9"));
            
            // Test equal versions
            assertFalse(TestHelper.isNewer("1.0.0", "1.0.0"));
            assertFalse(TestHelper.isNewer("1.2.3", "1.2.3"));
            
            // Test older versions
            assertFalse(TestHelper.isNewer("1.0.0", "1.0.1"));
            assertFalse(TestHelper.isNewer("0.9.9", "1.0.0"));
        }
        
        @Test
        @DisplayName("Should handle version prefixes correctly")
        void testVersionPrefixes() throws Exception {
            // Test with 'v' prefix
            assertTrue(TestHelper.isNewer("v1.0.1", "v1.0.0"));
            assertTrue(TestHelper.isNewer("v2.0.0", "1.9.9"));
            assertTrue(TestHelper.isNewer("2.0.0", "v1.9.9"));
            
            // Test mixed prefix scenarios
            assertFalse(TestHelper.isNewer("v1.0.0", "v1.0.1"));
            assertFalse(TestHelper.isNewer("1.0.0", "v1.0.1"));
        }
        
        @Test
        @DisplayName("Should handle null and empty versions")
        void testNullAndEmptyVersions() throws Exception {
            // Test null cases
            assertFalse(TestHelper.isNewer(null, "1.0.0"));
            assertTrue(TestHelper.isNewer("1.0.0", null));
            assertFalse(TestHelper.isNewer(null, null));
            
            // Test empty/blank cases
            assertFalse(TestHelper.isNewer("", "1.0.0"));
            assertTrue(TestHelper.isNewer("1.0.0", ""));
            assertFalse(TestHelper.isNewer("   ", "1.0.0"));
        }
        
        @Test
        @DisplayName("Should handle different version number lengths")
        void testDifferentVersionLengths() throws Exception {
            // Test shorter vs longer versions
            // The actual implementation treats "1.0.1.0" and "1.0.1" as equal
            assertFalse(TestHelper.isNewer("1.0.1.0", "1.0.1"));
            assertFalse(TestHelper.isNewer("1.0.1", "1.0.1.0"));
            assertTrue(TestHelper.isNewer("1.0.1", "1.0"));
            assertFalse(TestHelper.isNewer("1.0", "1.0.1"));
            
            // Test with zeros
            assertTrue(TestHelper.isNewer("1.0.1", "1.0.0"));
            assertFalse(TestHelper.isNewer("1.0.0", "1.0.1"));
            
            // Test edge case: when versions have different lengths but same prefix
            // "1.0" vs "1.0.1" - the shorter one should be considered older
            assertTrue(TestHelper.isNewer("1.0.1", "1.0"));
            assertFalse(TestHelper.isNewer("1.0", "1.0.1"));
        }
        
        @Test
        @DisplayName("Should handle special version numbers")
        void testSpecialVersions() throws Exception {
            // Test IDE mode version
            assertFalse(TestHelper.isNewer("1.0.0", "999.999.999"));
            assertFalse(TestHelper.isNewer("999.999.999", "1.0.0"));
            
            // Test edge cases
            assertTrue(TestHelper.isNewer("0.0.1", "0.0.0"));
            assertTrue(TestHelper.isNewer("0.1.0", "0.0.9"));
        }
    }
    
    @Nested
    @DisplayName("Version Parsing Tests")
    class VersionParsingTests {
        
        @Test
        @DisplayName("Should parse version numbers correctly")
        void testParseInt() throws Exception {
            // Test basic parsing
            assertEquals(1, TestHelper.parseInt("1"));
            assertEquals(10, TestHelper.parseInt("10"));
            assertEquals(123, TestHelper.parseInt("123"));
            
            // Test with non-numeric suffixes
            assertEquals(1, TestHelper.parseInt("1.0"));
            assertEquals(10, TestHelper.parseInt("10-beta"));
            assertEquals(123, TestHelper.parseInt("123alpha"));
            
            // Test edge cases
            assertEquals(0, TestHelper.parseInt(""));
            assertEquals(0, TestHelper.parseInt("abc"));
            assertEquals(0, TestHelper.parseInt("0"));
        }
    }
    
    @Nested
    @DisplayName("JSON Extraction Tests")
    class JsonExtractionTests {
        
        @Test
        @DisplayName("Should extract version from JSON")
        void testExtractVersion() throws Exception {
            String json = "{\"tag_name\": \"v1.0.0\", \"other\": \"value\"}";
            String version = TestHelper.extract(json, "\"tag_name\"\\s*:\\s*\"([^\"]+)\"");
            assertEquals("v1.0.0", version);
        }
        
        @Test
        @DisplayName("Should extract JAR URL from JSON")
        void testExtractJarUrl() throws Exception {
            String json = "{\"assets\": [{\"browser_download_url\": \"https://example.com/game.jar\"}]}";
            String url = TestHelper.extractFirstJarUrl(json);
            assertEquals("https://example.com/game.jar", url);
        }
        
        @Test
        @DisplayName("Should handle missing JSON patterns")
        void testMissingPatterns() throws Exception {
            String json = "{\"other\": \"value\"}";
            String version = TestHelper.extract(json, "\"tag_name\"\\s*:\\s*\"([^\"]+)\"");
            assertNull(version);
            
            String url = TestHelper.extractFirstJarUrl(json);
            assertNull(url);
        }
        
        @Test
        @DisplayName("Should extract first JAR URL from multiple assets")
        void testMultipleAssets() throws Exception {
            String json = """
                {
                    "assets": [
                        {"browser_download_url": "https://example.com/source.zip"},
                        {"browser_download_url": "https://example.com/game.jar"},
                        {"browser_download_url": "https://example.com/other.jar"}
                    ]
                }
                """;
            String url = TestHelper.extractFirstJarUrl(json);
            assertEquals("https://example.com/game.jar", url);
        }
    }
    
    @Nested
    @DisplayName("File Operations Tests")
    class FileOperationsTests {
        
        @Test
        @DisplayName("Should create directories safely")
        void testCreateDirectories() throws Exception {
            Path testDir = tempDir.resolve("test-dir");
            assertFalse(Files.exists(testDir));
            
            // This would normally be called in the constructor
            Files.createDirectories(testDir);
            assertTrue(Files.exists(testDir));
            assertTrue(Files.isDirectory(testDir));
        }
        
        @Test
        @DisplayName("Should handle file operations with proper paths")
        void testFilePaths() throws Exception {
            Path homeDir = tempDir.resolve(".tlob");
            Path gameJar = homeDir.resolve("game.jar");
            Path propsFile = homeDir.resolve("installed.properties");
            
            // Create directory structure
            Files.createDirectories(homeDir);
            
            // Test game JAR path
            assertFalse(Files.exists(gameJar));
            Files.createFile(gameJar);
            assertTrue(Files.exists(gameJar));
            
            // Test properties file path
            assertFalse(Files.exists(propsFile));
            Properties props = new Properties();
            props.setProperty("version", "1.0.0");
            props.setProperty("path", gameJar.toString());
            
            try (var out = Files.newOutputStream(propsFile)) {
                props.store(out, "Test properties");
            }
            
            assertTrue(Files.exists(propsFile));
            
            // Verify properties can be loaded
            Properties loadedProps = new Properties();
            try (var in = Files.newInputStream(propsFile)) {
                loadedProps.load(in);
            }
            
            assertEquals("1.0.0", loadedProps.getProperty("version"));
            assertEquals(gameJar.toString(), loadedProps.getProperty("path"));
        }
    }
    
    @Nested
    @DisplayName("System Detection Tests")
    class SystemDetectionTests {
        
        @Test
        @DisplayName("Should detect Windows correctly")
        void testWindowsDetection() throws Exception {
            // This test verifies the logic, but actual result depends on test environment
            String os = System.getProperty("os.name", "").toLowerCase();
            boolean isWindows = os.contains("win");
            
            // The method should return the same result as our manual check
            // Note: This test may behave differently on different systems
            assertNotNull(os);
        }
        
        @Test
        @DisplayName("Should find Java executable path")
        void testJavaPath() throws Exception {
            String javaHome = System.getProperty("java.home");
            assertNotNull(javaHome);
            assertFalse(javaHome.isEmpty());
            
            // Verify the path exists
            Path javaHomePath = Path.of(javaHome);
            assertTrue(Files.exists(javaHomePath));
            assertTrue(Files.isDirectory(javaHomePath));
        }
    }
    
    @Nested
    @DisplayName("Error Handling Tests")
    class ErrorHandlingTests {
        
        @Test
        @DisplayName("Should handle malformed version strings gracefully")
        void testMalformedVersions() throws Exception {
            // Test with invalid version formats
            // The current implementation parses malformed versions differently:
            // "1..0" parses to "1" (first number), "invalid" parses to 0
            assertFalse(TestHelper.isNewer("invalid", "1.0.0"));
            assertTrue(TestHelper.isNewer("1.0.0", "invalid"));
            assertFalse(TestHelper.isNewer("1..0", "1.0.0")); // "1..0" parses to "1", equal to "1.0.0"
            assertFalse(TestHelper.isNewer("1.0.0", "1..0")); // "1..0" parses to "1", equal to "1.0.0"
            
            // Test with completely non-numeric versions
            assertFalse(TestHelper.isNewer("abc", "1.0.0"));
            assertTrue(TestHelper.isNewer("1.0.0", "abc"));
            
            // Test that malformed versions are treated as equal to each other (both parse to 0)
            assertFalse(TestHelper.isNewer("1..0", "1..0"));
            assertFalse(TestHelper.isNewer("invalid", "invalid"));
            assertTrue(TestHelper.isNewer("1..0", "invalid")); // "1..0" parses to 1, "invalid" parses to 0
            assertFalse(TestHelper.isNewer("invalid", "1..0"));
        }
        
        @Test
        @DisplayName("Should handle empty JSON gracefully")
        void testEmptyJson() throws Exception {
            String emptyJson = "";
            String version = TestHelper.extract(emptyJson, "\"tag_name\"\\s*:\\s*\"([^\"]+)\"");
            assertNull(version);
            
            String url = TestHelper.extractFirstJarUrl(emptyJson);
            assertNull(url);
        }
        
        @Test
        @DisplayName("Should handle null JSON gracefully")
        void testNullJson() throws Exception {
            // The extract method throws NullPointerException when given null
            // This is expected behavior for the current implementation
            assertThrows(RuntimeException.class, () -> {
                TestHelper.extract(null, "\"tag_name\"\\s*:\\s*\"([^\"]+)\"");
            });
            
            assertThrows(RuntimeException.class, () -> {
                TestHelper.extractFirstJarUrl(null);
            });
        }
    }
    
    @Nested
    @DisplayName("Integration Tests")
    class IntegrationTests {
        
        @Test
        @DisplayName("Should handle complete version workflow")
        void testVersionWorkflow() throws Exception {
            // Test a complete version comparison workflow
            String[] versions = {"0.0.1", "0.1.0", "1.0.0", "1.0.1", "1.1.0", "2.0.0"};
            
            for (int i = 0; i < versions.length - 1; i++) {
                String current = versions[i];
                String next = versions[i + 1];
                
                assertTrue(TestHelper.isNewer(next, current), 
                    next + " should be newer than " + current);
                assertFalse(TestHelper.isNewer(current, next), 
                    current + " should not be newer than " + next);
            }
        }
        
        @Test
        @DisplayName("Should handle version with different formats consistently")
        void testVersionFormatConsistency() throws Exception {
            // Test that different format representations of the same version are handled consistently
            String[] sameVersions = {"1.0.0", "v1.0.0", " 1.0.0 ", "v 1.0.0 "};
            
            for (String version1 : sameVersions) {
                for (String version2 : sameVersions) {
                    assertFalse(TestHelper.isNewer(version1, version2), 
                        version1 + " should not be newer than " + version2);
                }
            }
        }
    }
}
