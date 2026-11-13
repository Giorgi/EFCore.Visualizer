package efcore.visualizer

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * The debugged app keeps the helper DLL it loaded open, and on Windows that locks the file. Rider updates a plugin in
 * place, in its own folder, so an app still running with the plugin's DLL loaded would make the update fail. The
 * helper is therefore loaded from a copy of <plugin>/netcoreapp and <plugin>/Resources, in a folder of Rider's system
 * directory named after the files' content: each plugin version gets its own folder.
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
object HelperCopy {
    private const val FOLDERS = "efcore-visualizer"
    private val log = logger<HelperCopy>()
    @Volatile private var copied: Path? = null

    /** The copy of [pluginDll] (<plugin>/netcoreapp/<dll>) to load, made if needed; the plugin's own DLL if copying fails. */
    @Synchronized
    fun of(pluginDll: Path): Path {
        copied?.takeIf { Files.isRegularFile(it) }?.let { return it }
        return runCatching { copy(pluginDll) }
            .onFailure { log.warn("Couldn't copy the helper; loading it from the plugin's folder", it) }
            .getOrDefault(pluginDll)
            .also { copied = it }
    }

    private fun copy(pluginDll: Path): Path {
        val pluginRoot = pluginDll.parent.parent
        val files = listOf("netcoreapp", "Resources").map(pluginRoot::resolve).filter { it.isDirectory() }
            .flatMap { dir -> Files.walk(dir).use { stream -> stream.filter(Files::isRegularFile).sorted().toList() } }
        val digest = MessageDigest.getInstance("SHA-256")
        for (file in files) {
            digest.update(pluginRoot.relativize(file).toString().replace('\\', '/').toByteArray())
            digest.update(Files.readAllBytes(file))
        }
        val name = digest.digest().take(8).joinToString("") { "%02x".format(it) }
        val folders = Path.of(PathManager.getSystemPath()).resolve(FOLDERS)
        val target = folders.resolve(name)
        val dll = target.resolve(pluginRoot.relativize(pluginDll))
        if (!Files.isRegularFile(dll)) {
            // Copy next to it, then rename: a half-made copy is never used.
            val temp = Files.createDirectories(folders).let { Files.createTempDirectory(it, "$name-") }
            for (file in files) {
                val to = temp.resolve(pluginRoot.relativize(file))
                Files.createDirectories(to.parent)
                Files.copy(file, to, StandardCopyOption.REPLACE_EXISTING)
            }
            runCatching { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE) }
                .onFailure { if (Files.isRegularFile(dll)) temp.deleteRecursively() else throw it } // another IDE made it meanwhile
        }
        removeOldCopies(folders, keep = name)
        return dll
    }

    /** Copies of other plugin versions, unless an app still has their DLL loaded (then it can't be deleted on Windows). */
    private fun removeOldCopies(folders: Path, keep: String) {
        for (folder in folders.listDirectoryEntries().filter { it.isDirectory() && it.name != keep && !it.name.startsWith("$keep-") }) {
            runCatching {
                val dll = folder.resolve("netcoreapp/EFCore.Visualizer.Core.dll")
                if (dll.exists()) Files.delete(dll) // fails while it's locked: leave that copy as it is
                folder.deleteRecursively()
            }
        }
    }
}
