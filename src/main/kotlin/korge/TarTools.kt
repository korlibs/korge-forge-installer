package korge

import io.airlift.compress.zstd.ZstdInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.util.zip.GZIPInputStream


open class TarTools(
    val removeFirstDir: Boolean = false,
    val processOutputName: (String) -> String? = {
        if (removeFirstDir) it.replace('\\', '/').substringAfter('/') else it
    },
) {
    companion object : TarTools()

    val CHUNK_SIZE = 1 * 1024 * 1024

    fun extractTarZstd(inputFile: File, outputDir: File, report: (LongRange) -> Unit = {}) {
        inputFile.inputStream().use {
            extractTarZstd(it.reporter(inputFile.length(), CHUNK_SIZE, report), outputDir)
        }
    }

    fun extractTarZstd(inputStream: InputStream, outputDir: File) {
        extractTar(inputStream.buffered().uncompressZstd(), outputDir)
    }

    fun extractTarGz(inputFile: File, outputDir: File, report: (LongRange) -> Unit = {}) {
        inputFile.inputStream().use {
            extractTarGz(it.reporter(inputFile.length(), CHUNK_SIZE, report), outputDir)
        }
    }

    fun extractTarGz(inputStream: InputStream, outputDir: File) {
        extractTar(inputStream.buffered().uncompressGzip(), outputDir)
    }

    fun extractTar(inputFile: File, outputDir: File) {
        inputFile.inputStream().use {
            extractTar(it.buffered(), outputDir)
        }
    }

    fun extractTar(tarInputStream: InputStream, outputDir: File) {
        if (!outputDir.isDirectory) {
            val tmpDir = File(outputDir.parentFile, "${outputDir.name}.tmp")
            for ((tarInput, entry) in tarInputStream.asTarSequence()) {
                val fullName = processOutputName(entry.name) ?: continue
                val outputFile = File(tmpDir, fullName)
                when {
                    entry.isDirectory -> outputFile.mkdirs()
                    else -> {
                        outputFile.parentFile?.mkdirs()
                        Files.copy(tarInput, outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        Files.setLastModifiedTime(outputFile.toPath(), entry.lastModifiedTime)

                        if (OS.CURRENT != OS.WINDOWS) {
                            val mode = "755".toInt(8)

                            // Convert mode to set of PosixFilePermission
                            val permissions = mutableSetOf<PosixFilePermission>()

                            if (mode and 0b100000000 != 0) permissions.add(PosixFilePermission.OWNER_READ)
                            if (mode and 0b010000000 != 0) permissions.add(PosixFilePermission.OWNER_WRITE)
                            if (mode and 0b001000000 != 0) permissions.add(PosixFilePermission.OWNER_EXECUTE)
                            if (mode and 0b000100000 != 0) permissions.add(PosixFilePermission.GROUP_READ)
                            if (mode and 0b000010000 != 0) permissions.add(PosixFilePermission.GROUP_WRITE)
                            if (mode and 0b000001000 != 0) permissions.add(PosixFilePermission.GROUP_EXECUTE)
                            if (mode and 0b000000100 != 0) permissions.add(PosixFilePermission.OTHERS_READ)
                            if (mode and 0b000000010 != 0) permissions.add(PosixFilePermission.OTHERS_WRITE)
                            if (mode and 0b000000001 != 0) permissions.add(PosixFilePermission.OTHERS_EXECUTE)

                            // Set the permissions
                            Files.setPosixFilePermissions(outputFile.toPath(), permissions)
                        }
                    }
                }
            }
            tmpDir.renameTo(outputDir)
        }
    }

    private fun InputStream.uncompressZstd(): InputStream {
        return ZstdInputStream(this)
    }

    private fun InputStream.uncompressGzip(): InputStream {
        return GZIPInputStream(this)
    }

    private fun InputStream.asTarSequence(): Sequence<Pair<TarArchiveInputStream, TarArchiveEntry>> = sequence {
        TarArchiveInputStream(this@asTarSequence).use { tarInput ->
            var entry: TarArchiveEntry? = tarInput.nextEntry
            while (entry != null) {
                yield(tarInput to entry)
                entry = tarInput.nextEntry
            }
        }
    }
}

fun InputStream.reporter(length: Long, chunkSize: Int = 16 * 1024, reporter: (LongRange) -> Unit): ReporterInputStream {
    return ReporterInputStream(this, length, chunkSize, reporter)
}

class ReporterInputStream(val base: InputStream, val length: Long, val chunkSize: Int = 16 * 1024, val reporter: (LongRange) -> Unit) : InputStream() {
    private var lastReport = Int.MIN_VALUE.toLong()
    var position = 0L

    private fun doReport() {
        val distance = position - lastReport
        if (distance >= chunkSize || position >= length) {
            lastReport = position
            reporter(position..length)
        }
    }

    private fun incrementPosition(len: Int) {
        if (len <= 0) return
        position += len
        doReport()
    }

    init {
        doReport()
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        return base.read(b, off, len).also {
            incrementPosition(it)
        }
    }

    override fun read(): Int {
        return base.read().also {
            if (it >= 0) incrementPosition(1)
        }
    }
}
