package agent.spigot.scripting;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 一个目录形式的脚本插件，对应 AstrBot 的一个 Star：
 *
 * <pre>
 * addons/&lt;插件名&gt;/
 * ├── metadata.yaml       元数据（name / version / author / description / entry）
 * ├── _conf_schema.json   配置 schema（默认值为每个键的 default）
 * ├── requirements.txt    依赖声明（仅预检提示，不自动安装）
 * └── main.js | main.lua | Main.java
 * </pre>
 *
 * 持久化路径都在 {@code addons/} 下、与代码目录分离：
 * {@code config/<名字>.json} 存配置，{@code data/<名字>/} 存数据与 kv.properties。
 */
public final class ScriptPackage {

    /** 入口探测顺序：metadata.yaml 的 entry 优先，否则按此表找第一个存在的。 */
    private static final String[] DEFAULT_ENTRIES = {"main.js", "main.lua", "Main.java", "main.py"};

    private final File directory;
    private final AddonManifest manifest;
    private final File entryFile;
    private final ConfigStore config;
    private final KvStore kv;
    private final File dataDir;
    private final List<String> requirements;

    private ScriptPackage(File directory, AddonManifest manifest, File entryFile,
                          ConfigStore config, KvStore kv, File dataDir, List<String> requirements) {
        this.directory = directory;
        this.manifest = manifest;
        this.entryFile = entryFile;
        this.config = config;
        this.kv = kv;
        this.dataDir = dataDir;
        this.requirements = requirements;
    }

    /**
     * 尝试把 {@code directory} 当成插件目录打开。目录下找不到入口脚本时返回 null。
     * {@code addonsFolder} 是加载器的 addons 目录，用来派生 config/ 与 data/ 路径。
     */
    public static ScriptPackage open(File directory, File addonsFolder) {
        AddonManifest manifest = AddonManifest.from(directory);
        File entry = findEntry(directory, manifest);
        if (entry == null) return null;
        // config/data 要落盘，metadata 里的 name 不能带路径分隔符；带了就退回目录名。
        String safeName = dataSafeName(manifest.name()) ? manifest.name() : directory.getName();
        File configFile = new File(new File(addonsFolder, "addons/config"), safeName + ".json");
        File schemaFile = new File(directory, "_conf_schema.json");
        ConfigStore config = ConfigStore.open(configFile, schemaFile);
        File dataDir = new File(new File(addonsFolder, "addons/data"), safeName);
        if (!dataDir.exists()) dataDir.mkdirs();
        KvStore kv = KvStore.open(new File(dataDir, "kv.properties"));
        return new ScriptPackage(directory, manifest, entry, config, kv, dataDir, readRequirements(directory));
    }

    private static File findEntry(File directory, AddonManifest manifest) {
        if (manifest.entry() != null) {
            File declared = new File(directory, manifest.entry());
            try { if (declared.getCanonicalPath().startsWith(directory.getCanonicalPath()+File.separator) && declared.isFile() && isScript(declared.getName())) return declared; }
            catch(IOException ignored) { return null; }
        }
        for (String candidate : DEFAULT_ENTRIES) {
            File file = new File(directory, candidate);
            if (file.isFile()) return file;
        }
        return null;
    }

    private static boolean isScript(String name) {
        String lower = name.toLowerCase();
        return lower.endsWith(".lua") || lower.endsWith(".js") || lower.endsWith(".java");
    }

    private static boolean dataSafeName(String name) {
        return name != null && !name.contains("/") && !name.contains("\\") && !name.contains("..");
    }

    private static List<String> readRequirements(File directory) {
        List<String> requirements = new ArrayList<>();
        File file = new File(directory, "requirements.txt");
        if (file.isFile()) {
            try {
                for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty() && !trimmed.startsWith("#")) requirements.add(trimmed);
                }
            } catch (Exception ignored) {
                // 读不了就当作没有
            }
        }
        return requirements;
    }

    public File directory() { return directory; }

    public AddonManifest manifest() { return manifest; }

    public File entryFile() { return entryFile; }

    /** 源文件扩展名（含点，如 ".lua"）。 */
    public String language() {
        String name = entryFile.getName().toLowerCase();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot);
    }

    public ConfigStore config() { return config; }

    public KvStore kv() { return kv; }

    public File dataDir() { return dataDir; }

    public List<String> requirements() { return requirements; }

    public boolean enabled() { return config.enabled(); }

    /** 登记 addon 用的名字：metadata 的 name，缺失时是目录名。 */
    public String name() { return manifest().name(); }
}
