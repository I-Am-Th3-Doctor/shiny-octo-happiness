package io.github.iamth3doctor.searchlight.files

import io.github.iamth3doctor.searchlight.search.FuzzyMatcher
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.min

/** One file or folder in shared storage. The full path is `dirs[dir] + "/" + name`. */
class FileEntry(
    val dir: Int,
    val name: String,
    val size: Long,
    val modified: Long,
    val isDir: Boolean,
) {
    /** Lowercased, accent-stripped name, built once so each keystroke only does indexOf. */
    val folded: String = FileMatcher.fold(name)
}

/**
 * An in-memory snapshot of every file under a root (normally /storage/emulated/0).
 * Pure Kotlin + java.io so it's unit-tested on the JVM.
 */
class FileIndex(val root: String, val dirs: List<String>, val entries: List<FileEntry>, val builtAt: Long) {

    fun pathOf(e: FileEntry): String = dirs[e.dir] + "/" + e.name

    /** The folder an entry lives in, relative to the root ("" for the root itself). */
    fun relativeDirOf(e: FileEntry): String = dirs[e.dir].removePrefix(root).trimStart('/')

    fun search(rawQuery: String, limit: Int, now: Long, cancelled: () -> Boolean = { false }): List<FileEntry> {
        val q = FuzzyMatcher.normalize(rawQuery)
        if (q.length < 2) return emptyList()
        val qc = FuzzyMatcher.compact(q)
        val scored = ArrayList<Pair<FileEntry, Int>>()
        for ((i, e) in entries.withIndex()) {
            if (i and 4095 == 0 && cancelled()) return emptyList()
            val s = FileMatcher.score(q, qc, e.folded)
            if (s > 0) scored += e to s + FileMatcher.recencyBoost(e.modified, now) + if (e.isDir) 10 else 0
        }
        scored.sortWith(compareByDescending<Pair<FileEntry, Int>> { it.second }.thenBy { it.first.name.length }.thenByDescending { it.first.modified })
        return scored.asSequence().take(limit).map { it.first }.toList()
    }

    fun write(file: File) {
        val tmp = File(file.path + ".tmp")
        DataOutputStream(tmp.outputStream().buffered(1 shl 16)).use { out ->
            out.writeInt(VERSION)
            out.writeUTF(root)
            out.writeLong(builtAt)
            out.writeInt(dirs.size)
            dirs.forEach(out::writeUTF)
            out.writeInt(entries.size)
            for (e in entries) {
                out.writeInt(e.dir)
                out.writeUTF(e.name)
                out.writeLong(e.size)
                out.writeLong(e.modified)
                out.writeBoolean(e.isDir)
            }
        }
        tmp.renameTo(file)
    }

    companion object {
        private const val VERSION = 1
        const val DEFAULT_MAX_ENTRIES = 400_000

        val EMPTY = FileIndex("", emptyList(), emptyList(), 0)

        fun read(file: File): FileIndex? = try {
            DataInputStream(file.inputStream().buffered(1 shl 16)).use { input ->
                if (input.readInt() != VERSION) return null
                val root = input.readUTF()
                val builtAt = input.readLong()
                val dirs = List(input.readInt()) { input.readUTF() }
                val entries = List(input.readInt()) {
                    FileEntry(input.readInt(), input.readUTF(), input.readLong(), input.readLong(), input.readBoolean())
                }
                FileIndex(root, dirs, entries, builtAt)
            }
        } catch (e: Exception) {
            null
        }

        /**
         * Walks [root] depth-first. Skips hidden entries (".thumbnails", ".trash", ...) and
         * Android/data + Android/obb (app-private, unreadable anyway); keeps Android/media, where
         * WhatsApp and others keep your photos and documents.
         */
        fun scan(
            root: File,
            now: Long,
            maxEntries: Int = DEFAULT_MAX_ENTRIES,
            cancelled: () -> Boolean = { false },
            progress: (Int) -> Unit = {},
        ): FileIndex? {
            val rootPath = root.absolutePath.trimEnd('/')
            val skip = setOf("$rootPath/Android/data", "$rootPath/Android/obb")
            val dirs = ArrayList<String>()
            val entries = ArrayList<FileEntry>()
            val stack = ArrayDeque<String>()
            stack.addLast(rootPath)
            while (stack.isNotEmpty()) {
                if (cancelled()) return null
                val dirPath = stack.removeLast()
                val children = File(dirPath).listFiles() ?: continue
                val dirIndex = dirs.size
                dirs += dirPath
                for (child in children) {
                    val name = child.name
                    if (name.startsWith('.')) continue
                    val isDir = child.isDirectory
                    val path = "$dirPath/$name"
                    if (isDir && path in skip) continue
                    entries += FileEntry(dirIndex, name, if (isDir) 0 else child.length(), child.lastModified(), isDir)
                    if (isDir) stack.addLast(path)
                    if (entries.size >= maxEntries) return FileIndex(rootPath, dirs, entries, now)
                    if (entries.size % 5000 == 0) progress(entries.size)
                }
            }
            return FileIndex(rootPath, dirs, entries, now)
        }
    }
}

/** Substring-based scoring for file names: fast enough for hundreds of thousands of entries. */
object FileMatcher {

    fun fold(name: String): String {
        var ascii = true
        for (c in name) if (c.code >= 128) { ascii = false; break }
        if (ascii) return name.lowercase()
        return buildString(name.length) { name.forEach { append(FuzzyMatcher.fold(it)) } }
    }

    /**
     * @param q normalized query, [qc] the same with separators removed.
     *  1000 exact (with or without extension) · 900 prefix · 800 word start ("tax" in
     *  "2024_tax_return.pdf") · 600 anywhere · 550 ignoring separators ("taxreturn")
     */
    fun score(q: String, qc: String, folded: String): Int {
        if (folded == q) return 1000
        val dot = folded.lastIndexOf('.')
        if (dot > 0 && folded.length - dot <= 6 && folded.regionMatches(0, q, 0, dot) && q.length == dot) return 1000
        if (folded.startsWith(q)) return 900 + coverage(q.length, folded.length)
        var idx = folded.indexOf(q)
        var best = 0
        while (idx > 0) {
            val prev = folded[idx - 1]
            if (!prev.isLetterOrDigit()) return 800 + coverage(q.length, folded.length)
            if (best == 0) best = 600 - min(idx, 50)
            idx = folded.indexOf(q, idx + 1)
        }
        if (best > 0) return best
        if (qc.length >= 3 && qc != q) {
            // Only worth the allocation when the query itself had separators or spaces.
            if (FuzzyMatcher.compact(folded).contains(qc)) return 550
        } else if (qc.length >= 3 && q.length >= 3 && folded.length > q.length) {
            var hasSep = false
            for (c in folded) if (!c.isLetterOrDigit()) { hasSep = true; break }
            if (hasSep && FuzzyMatcher.compact(folded).contains(qc)) return 550
        }
        return 0
    }

    /** Recently touched files float up a little: +30 this week, +15 this month. */
    fun recencyBoost(modified: Long, now: Long): Int {
        val age = now - modified
        return when {
            age < 7 * DAY -> 30
            age < 30 * DAY -> 15
            else -> 0
        }
    }

    private const val DAY = 24L * 60 * 60 * 1000

    private fun coverage(matched: Int, total: Int) = if (total <= 0) 0 else min(20, 20 * matched / total)
}
