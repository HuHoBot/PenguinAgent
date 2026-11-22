package agent.spigot.scripting;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code metadata.yaml}，学 AstrBot 的插件元数据声明。只解析顶层的
 * {@code key: value}，不引入 YAML 库：
 *
 * <pre>
 * name: onebot
 * version: 1.0.0
 * author: HuHoBot
 * description: OneBot v11 正向 WebSocket
 * entry: main.lua        # 可省略，默认按 main.js / main.lua / Main.java 找
 * </pre>
 *
 * 缺省时：名字与作者用目录名，版本 1.0.0，描述为空。
 */
public final class AddonManifest {

    private final String name;
    private final String version;
    private final String author;
    private final String description;
    private final String entry;

    private AddonManifest(String name, String version, String author, String description, String entry) {
        this.name = name;
        this.version = version;
        this.author = author;
        this.description = description;
        this.entry = entry;
    }

    public String name() { return name; }

    public String version() { return version; }

    public String author() { return author; }

    public String description() { return description; }

    /** 入口文件名，没有声明时返回 null。 */
    public String entry() { return entry; }

    /** 读目录下的 metadata.yaml（也认 .yml），解析失败就回退默认值。 */
    public static AddonManifest from(File dir) {
        Map<String, String> values = new LinkedHashMap<>();
        File file = new File(dir, "metadata.yaml");
        if (!file.isFile()) file = new File(dir, "metadata.yml");
        if (file.isFile()) {
            try {
                List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
                for (String line : lines) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                    int colon = trimmed.indexOf(':');
                    if (colon <= 0) continue;
                    String key = trimmed.substring(0, colon).trim();
                    String value = unquote(trimmed.substring(colon + 1).trim());
                    if (!key.isEmpty()) values.put(key, value);
                }
            } catch (Exception ignored) {
                // 坏了就用默认值，插件照常加载
            }
        }
        String dirName = dir.getName();
        return new AddonManifest(
                orDefault(values.get("name"), dirName),
                orDefault(values.get("version"), "1.0.0"),
                orDefault(values.get("author"), dirName),
                orDefault(values.get("description"), ""),
                emptyToNull(values.get("entry")));
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
