package agent.spigot.scripting;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 插件配置，学 AstrBot 的「schema 定义 + 数据分离」：
 * 插件目录里的 {@code _conf_schema.json} 声明配置项与默认值，
 * 用户实际配置持久化在 {@code addons/config/<插件名>.json}，与代码分离，
 * 更新插件不会丢配置。
 *
 * <pre>
 * _conf_schema.json:
 * {
 *   "token":  {"type": "string", "description": "访问令牌", "default": ""},
 *   "groups": {"type": "list",   "description": "互通群号", "default": []}
 * }
 * </pre>
 *
 * 注入给脚本的是一个 Java 对象。Lua 用冒号 {@code config:get("token")}，
 * JS 与 Python 用点 {@code config.get("token")}。保留键 {@code _enabled=false}
 * 表示禁用该插件，加载器会跳过它。
 */
public final class ConfigStore {

    private final File file;
    private final Map<String, Object> values = new ConcurrentHashMap<>();
    private final List<String> declared = new ArrayList<>();

    private ConfigStore(File file) {
        this.file = file;
    }

    /**
     * 打开 {@code configFile}，并用 {@code schemaFile}（可为不存在的文件）里的默认值
     * 补齐缺的键。补齐结果立刻写回，让文件里始终能看到全部可配置项。
     */
    public static ConfigStore open(File configFile, File schemaFile) {
        ConfigStore store = new ConfigStore(configFile);
        if (configFile.isFile()) {
            try {
                String text = new String(Files.readAllBytes(configFile.toPath()), StandardCharsets.UTF_8);
                JSONObject root = JSON.parseObject(text);
                for (Map.Entry<String, Object> entry : root.entrySet()) {
                    store.values.put(entry.getKey(), toPlain(entry.getValue()));
                }
            } catch (Exception ignored) {
                // 配置坏了当作空配置，别让插件起不来
            }
        }
        JSONObject schema = readSchema(schemaFile);
        if (schema != null) {
            for (Map.Entry<String, Object> entry : schema.entrySet()) {
                String key = entry.getKey();
                store.declared.add(key);
                if (!store.values.containsKey(key)) {
                    // default 为 null 时不放入：ConcurrentHashMap 不接受 null，
                    // get 本来就会返回 null。
                    Object fallback = schemaDefault(entry.getValue());
                    if (fallback != null) store.values.put(key, fallback);
                }
            }
        }
        store.save();
        return store;
    }

    /** 配置项对应的 Java 值（String / Number / Boolean / List / Map）。 */
    public Object get(String key) {
        return values.get(key);
    }

    public Object get(String key, Object defaultValue) {
        Object value = values.get(key);
        return value == null ? defaultValue : value;
    }

    /** 字符串便捷形式，脚本里最常用。 */
    public String getString(String key) {
        return getString(key, "");
    }

    public String getString(String key, String defaultValue) {
        Object value = values.get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }

    /** 列表便捷形式，元素都转成字符串。 */
    public List<String> getList(String key) {
        Object value = values.get(key);
        List<String> result = new ArrayList<>();
        if (value instanceof List) {
            for (Object item : (List<?>) value) result.add(String.valueOf(item));
        }
        return result;
    }

    /** 写入并立刻落盘。 */
    public void set(String key, Object value) {
        values.put(key, value);
        save();
    }

    public void remove(String key) {
        values.remove(key);
        save();
    }

    /** 全部配置的副本。 */
    public Map<String, Object> all() {
        return new LinkedHashMap<>(values);
    }

    /** schema 里声明过的键，按声明顺序。 */
    public List<String> declaredKeys() {
        return new ArrayList<>(declared);
    }

    /** 配置是否被禁用（保留键 _enabled=false）。 */
    public boolean enabled() {
        Object value = values.get("_enabled");
        if (value instanceof Boolean) return (Boolean) value;
        if (value != null) return Boolean.parseBoolean(String.valueOf(value));
        return true;
    }

    public synchronized void save() {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            String text = JSON.toJSONString(values, JSONWriter.Feature.PrettyFormat);
            Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) {
            System.err.println("[script-config] 写入失败 " + file.getPath() + ": " + error.getMessage());
        }
    }

    private static JSONObject readSchema(File schemaFile) {
        if (schemaFile == null || !schemaFile.isFile()) return null;
        try {
            String text = new String(Files.readAllBytes(schemaFile.toPath()), StandardCharsets.UTF_8);
            return JSON.parseObject(text);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Object schemaDefault(Object item) {
        if (item instanceof JSONObject) {
            JSONObject object = (JSONObject) item;
            return object.containsKey("default") ? toPlain(object.get("default")) : null;
        }
        return toPlain(item);
    }

    private static Object toPlain(Object value) {
        if (value instanceof JSONObject) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : ((JSONObject) value).entrySet()) {
                map.put(entry.getKey(), toPlain(entry.getValue()));
            }
            return map;
        }
        if (value instanceof JSONArray) {
            List<Object> list = new ArrayList<>();
            for (Object item : (JSONArray) value) list.add(toPlain(item));
            return list;
        }
        return value;
    }
}
