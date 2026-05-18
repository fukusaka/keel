package io.github.fukusaka.keel.server.http

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readByteArray
import kotlinx.io.writeString
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Integration tests for [FilesystemAssetSource] over a real temp
 * directory on [SystemFileSystem] — resolution, metadata, and the
 * lexical path-traversal defense (layers 2, 3, 5).
 *
 * Symlink containment (layer 4) needs symlink creation and is covered by
 * the JVM-only `FilesystemAssetSourceSymlinkTest`.
 */
class FilesystemAssetSourceTest {

    private lateinit var rootDir: Path
    private lateinit var source: FilesystemAssetSource

    @BeforeTest
    fun setUp() {
        rootDir = Path(SystemTemporaryDirectory, "keel-static-${Random.nextLong()}")
        SystemFileSystem.createDirectories(rootDir)
        writeFile("hello.txt", "hello world")
        SystemFileSystem.createDirectories(Path(rootDir, "css"))
        writeFile("css/site.css", "body{}")
        SystemFileSystem.createDirectories(Path(rootDir, "sub"))
        source = FilesystemAssetSource(root = rootDir.toString())
    }

    @AfterTest
    fun tearDown() {
        deleteRecursively(rootDir)
    }

    private fun writeFile(relative: String, content: String) {
        SystemFileSystem.sink(Path(rootDir, relative)).buffered().use { it.writeString(content) }
    }

    private fun deleteRecursively(path: Path) {
        val metadata = SystemFileSystem.metadataOrNull(path) ?: return
        if (metadata.isDirectory) {
            for (child in SystemFileSystem.list(path)) deleteRecursively(child)
        }
        SystemFileSystem.delete(path, mustExist = false)
    }

    @Test
    fun `resolving an existing file returns an asset with its metadata`() {
        val asset = assertNotNull(source.resolve("hello.txt"))
        assertEquals(11L, asset.size)
        assertEquals("text/plain; charset=utf-8", asset.contentType)
        assertNotNull(asset.lastModified, "mtime should be readable on a real file")
        assertNotNull(asset.etag, "Default ETag generator should produce a tag when mtime is present")
    }

    @Test
    fun `resolving a nested file works`() {
        val asset = assertNotNull(source.resolve("css/site.css"))
        assertEquals("text/css; charset=utf-8", asset.contentType)
    }

    @Test
    fun `the resolved asset opens to its byte content`() {
        val asset = assertNotNull(source.resolve("hello.txt"))
        val bytes = asset.open().buffered().use { it.readByteArray() }
        assertEquals("hello world", bytes.decodeToString())
    }

    @Test
    fun `opening with an offset and length yields exactly that window`() {
        val asset = assertNotNull(source.resolve("hello.txt"))
        // "hello world" — bytes [6, 6+5) is "world".
        val bytes = asset.open(6, 5).buffered().use { it.readByteArray() }
        assertEquals("world", bytes.decodeToString())
    }

    @Test
    fun `opening with a length shorter than the file stops at that length`() {
        val asset = assertNotNull(source.resolve("hello.txt"))
        val bytes = asset.open(0, 5).buffered().use { it.readByteArray() }
        assertEquals("hello", bytes.decodeToString())
    }

    @Test
    fun `opening a single byte at an offset yields one byte`() {
        val asset = assertNotNull(source.resolve("hello.txt"))
        val bytes = asset.open(10, 1).buffered().use { it.readByteArray() }
        assertEquals("d", bytes.decodeToString())
    }

    @Test
    fun `a missing file resolves to null`() {
        assertNull(source.resolve("nope.txt"))
    }

    @Test
    fun `a directory resolves to null`() {
        assertNull(source.resolve("sub"))
    }

    @Test
    fun `a dot-dot traversal escaping the root resolves to null`() {
        assertNull(source.resolve("../hello.txt"))
        assertNull(source.resolve("css/../../hello.txt"))
    }

    @Test
    fun `a NUL in the path resolves to null`() {
        assertNull(source.resolve("hello.txt "))
    }

    @Test
    fun `an absolute path is confined to the root`() {
        assertNull(source.resolve("/etc/passwd"))
    }

    @Test
    fun `a source whose root does not exist resolves everything to null`() {
        val missing = FilesystemAssetSource(root = Path(SystemTemporaryDirectory, "keel-absent-x").toString())
        assertNull(missing.resolve("hello.txt"))
    }
}
