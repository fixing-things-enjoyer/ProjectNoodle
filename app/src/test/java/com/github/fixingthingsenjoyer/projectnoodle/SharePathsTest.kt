package com.github.fixingthingsenjoyer.projectnoodle

import org.junit.Assert.*
import org.junit.Test

class SharePathsTest {
    @Test
    fun normalizesRootAndRepeatedSeparators() {
        assertEquals("/", SharePaths.normalize("///"))
        assertEquals("/Camera/Trip", SharePaths.normalize("//Camera///Trip/"))
    }

    @Test
    fun preservesLiteralNames() {
        listOf("budget + 10%.csv", "50%2F complete", "写真.jpg", "hello#world.txt", " notes ")
            .forEach { name ->
                assertEquals("/$name", SharePaths.child("/", name))
                assertEquals("/$name", SharePaths.normalize("/$name"))
            }
    }

    @Test
    fun rejectsTraversalAndControlCharacters() {
        listOf("/../secret", "/hello/./file", "/hello\\world", "/file\u0000", "/file\n").forEach {
            path ->
            assertThrows(IllegalArgumentException::class.java) { SharePaths.normalize(path) }
        }
    }

    @Test
    fun validatesSingleNames() {
        listOf("", " ", ".", "..", "a/b", "a\\b", "a\u007f", "x".repeat(256)).forEach {
            assertFalse(SharePaths.validName(it))
        }
        listOf("..notes", ".hidden", "a+b", "100%", "résumé").forEach {
            assertTrue(SharePaths.validName(it))
        }
    }

    @Test
    fun joinsNestedPathsWithoutEncodingTwice() {
        assertEquals(
            "/Camera/Trip/photo + 1%.jpg",
            SharePaths.child("/Camera/Trip/", "photo + 1%.jpg"),
        )
    }

    @Test
    fun formatsConnectionAddresses() {
        assertEquals("http://192.168.1.2:8080", shareAddress("192.168.1.2", 8080, false))
        assertEquals("https://[fd00::1]:8080", shareAddress("fd00::1", 8080, true))
        assertEquals("http://[fe80::1%25wlan0]:8080", shareAddress("fe80::1%wlan0", 8080, false))
        assertNull(shareAddress(null, 8080, false))
        assertNull(shareAddress("192.168.1.2", -1, false))
    }
}
