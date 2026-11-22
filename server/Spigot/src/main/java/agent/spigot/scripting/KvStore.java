package agent.spigot.scripting;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 插件级键值持久化，对应 AstrBot 的 PluginKVStore：每个插件一个独立命名空间，
 * 落在 {@code addons/data/<插件名>/kv.properties}。适合存小型运行状态；
 * 大文件约定放在同一目录下（脚本可读 {@code DATA_DIR}）。
 *
 * 注入脚本是 Java 对象：Lua 冒号 {@code kv:put("k", "v")}，JS / Python 点。
 */
public final class KvStore {

    private final File file;
    private final Properties values = new Properties();

    private KvStore(File file) {
        this.file = file;
    }

    public static KvStore open(File file) {
        KvStore store = new KvStore(file);
        if (file.isFile()) {
            try (InputStream in = Files.newInputStream(file.toPath())) {
                store.values.load(in);
            } catch (Exception ignored) {
                // 坏了当作空表
            }
        }
        return store;
    }

    public String get(String key) {
        return values.getProperty(key);
    }

    public String get(String key, String defaultValue) {
        return values.getProperty(key, defaultValue);
    }

    /** 写入并立刻落盘。 */
    public void put(String key, String value) {
        if (value == null) {
            values.remove(key);
        } else {
            values.setProperty(key, value);
        }
        save();
    }

    public void delete(String key) {
        values.remove(key);
        save();
    }

    public Map<String, String> all() {
        Map<String, String> result = new LinkedHashMap<>();
        for (String key : values.stringPropertyNames()) {
            result.put(key, values.getProperty(key));
        }
        return result;
    }

    private synchronized void save() {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (OutputStream out = Files.newOutputStream(file.toPath())) {
                values.store(out, "kv store");
            }
        } catch (Exception error) {
            System.err.println("[script-kv] 写入失败 " + file.getPath() + ": " + error.getMessage());
        }
    }
}
