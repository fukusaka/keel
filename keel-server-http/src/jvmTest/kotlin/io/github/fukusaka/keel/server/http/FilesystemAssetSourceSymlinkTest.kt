package io.github.fukusaka.keel.server.http

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * JVM-only test for [FilesystemAssetSource]'s symlink-containment check
 * (path-traversal defense layer 4): a symlink inside the served root
 * that points outside it must not be resolvable.
 */
class FilesystemAssetSourceSymlinkTest {

    private lateinit var workDir: Path
    private lateinit var rootDir: Path
    private lateinit var outsideDir: Path

    @BeforeTest
    fun setUp() {
        workDir = Files.createTempDirectory("keel-symlink-")
        rootDir = workDir.resolve("public").also { it.createDirectories() }
        outsideDir = workDir.resolve("private").also { it.createDirectories() }
        rootDir.resolve("ok.txt").writeText("inside")
        outsideDir.resolve("secret.txt").writeText("outside")
    }

    @AfterTest
    fun tearDown() {
        Files.walk(workDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    @Test
    fun `a symlink escaping the root resolves to null`() {
        // public/escape -> ../private : an in-root symlink pointing out of root.
        Files.createSymbolicLink(rootDir.resolve("escape"), Path.of(".." + java.io.File.separator + "private"))
        val source = FilesystemAssetSource(root = rootDir.toString())

        assertNotNull(source.resolve("ok.txt"), "an ordinary in-root file still resolves")
        assertNull(source.resolve("escape/secret.txt"), "a file reached via an escaping symlink must not resolve")
    }

    @Test
    fun `a symlink that stays within the root resolves`() {
        // public/alias -> ok.txt : an in-root symlink pointing within root.
        Files.createSymbolicLink(rootDir.resolve("alias"), Path.of("ok.txt"))
        val source = FilesystemAssetSource(root = rootDir.toString())

        assertNotNull(source.resolve("alias"), "a symlink confined to the root resolves")
    }
}
