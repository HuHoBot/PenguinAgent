package agent.spigot.manager

import java.io.File
import java.util.Properties

/** Inactive examples; a digest records what we generated, not merely an editable heading. */
object ConfigGuide {
    private val guideFiles = listOf(
        "README.txt", "permission-groups.example.yml", "timed-tasks.example.yml",
        "metadata.example.yaml", "addons-README.txt", "addons-js.example",
        "addons-lua.example", "addons-java.example", "sensitive-words.example.txt"
    )

    fun ensure(dataFolder: File) {
        val root = dataFolder.parentFile ?: dataFolder
        val manifest = dataFolder.resolve("guide/generated-files.properties")
        if (!inside(root, manifest)) return
        val generated = Properties()
        if (manifest.isFile && manifest.length() < 65536L) {
            runCatching { manifest.inputStream().use { generated.load(it) } }
        }
        val before = Properties().apply { putAll(generated) }
        install(root, dataFolder.resolve("Q&A.md"), "/Q&A.md", generated)
        install(root, dataFolder.resolve("Q&A.txt"), "/Q&A.txt", generated)
        guideFiles.forEach { name -> install(root, dataFolder.resolve("guide/$name"), "/guide/$name", generated) }
        if (dataFolder.parentFile != null) install(root, root.resolve("addons/README.txt"), "/guide/addons-README.txt", generated)
        if (before != generated || !manifest.isFile) {
            manifest.parentFile.mkdirs()
            manifest.outputStream().use { generated.store(it, "Generated guide digests only; no configuration or user content") }
        }
    }

    private fun install(root: File, file: File, resource: String, generated: Properties) {
        if (!inside(root, file)) return
        val text = javaClass.getResourceAsStream(resource)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: return
        val key = file.relativeTo(root).invariantSeparatorsPath
        val currentDigest = digest(text)
        file.parentFile?.mkdirs()
        if (!file.exists()) {
            file.writeText(text, Charsets.UTF_8)
            generated.setProperty(key, currentDigest)
            return
        }
        if (!file.isFile) return
        val old = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return
        if (old == text) { generated.setProperty(key, currentDigest); return }
        if (generated.getProperty(key) == digest(old)) {
            file.writeText(text, Charsets.UTF_8)
            generated.setProperty(key, currentDigest)
        } else {
            // An unknown or modified copy belongs to the owner, even with our original heading.
            val next = file.resolveSibling(file.name + ".new")
            if (inside(root, next)) next.writeText(text, Charsets.UTF_8)
        }
    }

    private fun inside(root: File, file: File): Boolean = runCatching {
        file.canonicalPath.startsWith(root.canonicalPath + File.separator)
    }.getOrDefault(false)

    private fun digest(text: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
}
