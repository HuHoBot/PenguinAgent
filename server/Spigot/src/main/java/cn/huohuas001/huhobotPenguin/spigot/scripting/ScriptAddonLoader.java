package cn.huohuas001.huhobotPenguin.spigot.scripting;

import cn.huohuas001.huhobotPenguin.spigot.HuHoBotSpigot;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.jse.CoerceJavaToLua;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads {@code .js} / {@code .py} / {@code .lua} addon scripts from
 * {@code plugins/HuHoBotPenguin/addons}.
 *
 * Lua uses LuaJ, wired the way BirdLibraryApi does
 * (https://github.com/prach1121/birdlibraryapi, Apache-2.0), and that engine ships
 * inside the main jar. JavaScript uses GraalJS through JSR-223 and Python uses
 * GraalPy, but those two engines are NOT inside the main jar: they are loaded from
 * {@code plugins/HuHoBotPenguin/engines/*.jar} (built by {@code :addon-GraalJs} and
 * {@code :addon-GraalPy}). Without the matching jar, scripts of that language fail
 * to load and the plugin keeps running.
 * Each script is also registered as a HuHoBot addon.
 */
public class ScriptAddonLoader {

    private final HuHoBotSpigot plugin;
    private final File scriptsFolder;
    private final File enginesFolder;
    private final Map<String, LoadedScript> loaded = new LinkedHashMap<>();

    /**
     * GraalJS and GraalPy both live in engines/*.jar and share one classloader,
     * parented on this plugin's loader. Cached after the first script that needs it.
     */
    private volatile ClassLoader engineLoader;
    private volatile String engineLoaderError;

    /** GraalPy lives in its own jar. Null until a .py script is loaded, and stays null when the jar is absent. */
    private volatile Object pythonEngine;
    private volatile String pythonEngineError;

    public ScriptAddonLoader(HuHoBotSpigot plugin) {
        this.plugin = plugin;
        this.scriptsFolder = new File(plugin.getDataFolder(), "addons");
        if (!scriptsFolder.exists()) {
            scriptsFolder.mkdirs();
        }
        this.enginesFolder = new File(plugin.getDataFolder(), "engines");
        if (!enginesFolder.exists()) {
            enginesFolder.mkdirs();
        }
    }

    /** 三个受支持的脚本语言，扩展名只在这里解析一次。 */
    private enum Lang {
        LUA(".lua", "Lua"),
        PY(".py", "Python"),
        JS(".js", "JavaScript");

        private final String extension;
        private final String displayName;

        Lang(String extension, String displayName) {
            this.extension = extension;
            this.displayName = displayName;
        }

        static Lang of(String extension) {
            String lower = extension == null ? "" : extension.toLowerCase();
            for (Lang lang : values()) {
                if (lang.extension.equals(lower)) return lang;
            }
            return JS;
        }
    }

    /** addons/ 下被插件自己占用的目录名，不能当作脚本插件目录。 */
    private static boolean isReservedDir(String name) {
        return "config".equalsIgnoreCase(name)
                || "data".equalsIgnoreCase(name)
                || "files".equalsIgnoreCase(name)
                || "engines".equalsIgnoreCase(name);
    }

    /**
     * Every jar in {@code engines/}, loaded once. Both the GraalJS and GraalPy bridges
     * are resolved against this loader, so one directory serves both languages.
     */
    private ClassLoader engineLoader() {
        ClassLoader cached = engineLoader;
        if (cached != null) return cached;
        synchronized (this) {
            if (engineLoader != null) return engineLoader;
            File[] jars = enginesFolder.listFiles((dir, name) -> name.toLowerCase().endsWith(".jar"));
            if (jars == null || jars.length == 0) {
                engineLoaderError = "未安装脚本引擎。请把构建产物 HuHoBot-Engine-GraalJs-<版本>.jar / "
                        + "HuHoBot-Engine-GraalPy-<版本>.jar 放到 " + enginesFolder.getPath();
                return null;
            }
            try {
                warnOnVersionMismatch(jars);
                URL[] urls = new URL[jars.length];
                for (int i = 0; i < jars.length; i++) urls[i] = jars[i].toURI().toURL();
                engineLoader = new URLClassLoader(urls, ScriptAddonLoader.class.getClassLoader());
                plugin.getLogger().info("已从 " + enginesFolder.getPath() + " 加载脚本引擎（" + jars.length + " 个 jar）");
                return engineLoader;
            } catch (Throwable error) {
                engineLoaderError = "脚本引擎加载失败: " + error.getMessage();
                plugin.getLogger().severe(engineLoaderError);
                return null;
            }
        }
    }

    /**
     * 引擎 jar 的文件名带着构建版本。不一致时脚本多半会以难懂的方式失败，
     * 所以这里先提醒一句，而不是让用户去猜。
     */
    private void warnOnVersionMismatch(File[] jars) {
        String current = plugin.getDescription().getVersion();
        if (current == null || current.isEmpty()) return;
        for (File jar : jars) {
            String name = jar.getName();
            if (!name.startsWith("HuHoBot-Engine-")) continue;
            if (!name.contains(current)) {
                plugin.getLogger().warning("脚本引擎 " + name + " 的版本与当前插件（" + current
                        + "）不一致，如果脚本加载异常请重新构建引擎 jar");
            }
        }
    }

    /**
     * Builds the GraalPy engine from the jars in {@code engines/}. The result is cached.
     * The returned object is the engine jar's {@code org.graalvm.polyglot.Engine}; this
     * class never names that type, so the main jar compiles without GraalPy.
     */
    private Object pythonEngine() {
        Object cached = pythonEngine;
        if (cached != null) return cached;
        synchronized (this) {
            if (pythonEngine != null) return pythonEngine;
            ClassLoader loader = engineLoader();
            if (loader == null) {
                pythonEngineError = engineLoaderError;
                return null;
            }
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(loader);
            try {
                // GraalVM 24.1 从「Engine.newBuilder 执行期间，当前线程的 context classloader」
                // 收集语言，不看调用方的类加载器，也没有接受 ClassLoader 的 build()。
                // 主 jar 里已经没有 polyglot，所以这段时间必须把线程的加载器换成引擎 jar 的，
                // 调用结束后再换回来。语言发现本身由引擎 jar 里的 GraalPyBridge 完成。
                Class<?> bridge = Class.forName("cn.huohuas001.huhobot.graalpy.GraalPyBridge", true, loader);
                Object engine = bridge.getMethod("createEngine").invoke(null);
                boolean provides = (Boolean) bridge.getMethod("providesPython", Object.class).invoke(null, engine);
                if (!provides) {
                    engine.getClass().getMethod("close").invoke(engine);
                    pythonEngineError = "engines 目录里的 jar 没有提供 GraalPy 语言";
                    return null;
                }
                pythonEngine = engine;
                plugin.getLogger().info("已加载 GraalPy 引擎");
                return pythonEngine;
            } catch (Throwable error) {
                pythonEngineError = "GraalPy 引擎加载失败: " + causeOf(error).getMessage();
                plugin.getLogger().severe(pythonEngineError);
                return null;
            } finally {
                Thread.currentThread().setContextClassLoader(previous);
            }
        }
    }

    public List<ScriptLoadResult> loadAll() {
        List<ScriptLoadResult> results = new ArrayList<>();
        try {
            warnStrayScripts();
            // 有 .py 脚本时先把 GraalPy 的 home 解压完，否则第一个 .py 脚本会抢在解压完成前加载
            if (hasPythonAddon()) {
                warmUpPython();
            }
            File[] dirs = scriptsFolder.listFiles(File::isDirectory);
            if (dirs == null || dirs.length == 0) {
                plugin.getLogger().info("addons 目录没有脚本插件（建一个目录，放 main.lua / main.py / main.js）: "
                        + scriptsFolder.getPath());
                return results;
            }
            Arrays.sort(dirs, Comparator.comparing(File::getName));
            for (File dir : dirs) {
                if (isReservedDir(dir.getName())) continue;
                results.add(loadDirectory(dir));
            }
        } catch (Throwable t) {
            plugin.getLogger().severe("Fatal error while scanning the addon scripts folder: " + t);
            t.printStackTrace();
        }
        return results;
    }

    /** 旧结构提示：addons/ 根上直接放 .lua/.py/.js 已不再加载。 */
    private void warnStrayScripts() {
        File[] stray = scriptsFolder.listFiles((dir, name) -> {
            String lower = name.toLowerCase();
            return lower.endsWith(".js") || lower.endsWith(".lua") || lower.endsWith(".py");
        });
        if (stray == null) return;
        for (File file : stray) {
            String base = baseName(file.getName());
            String ext = file.getName().substring(file.getName().lastIndexOf('.'));
            plugin.getLogger().warning("[" + file.getName() + "] 脚本扩展已改为目录插件：请挪到 addons/"
                    + base + "/main" + ext + " 后重载");
        }
    }

    /**
     * 一个目录就是一个插件。入口优先用 metadata.yaml 的 entry，否则按
     * main.lua、main.py、main.js 找第一个存在的。
     */
    public ScriptLoadResult loadDirectory(File dir) {
        ScriptPackage pkg = ScriptPackage.open(dir, scriptsFolder);
        if (pkg == null) {
            String msg = "目录里没有 main.lua / main.py / main.js";
            plugin.getLogger().warning("[" + dir.getName() + "] " + msg + "，已跳过");
            return ScriptLoadResult.skipped(dir.getName(), msg);
        }
        if (!pkg.enabled()) {
            String reason = "已在配置里禁用（_enabled=false）";
            plugin.getLogger().info("[" + pkg.name() + "] " + reason + "，跳过加载");
            return ScriptLoadResult.skipped(pkg.name(), reason);
        }
        if (!pkg.requirements().isEmpty()) {
            plugin.getLogger().warning("[" + pkg.name() + "] 声明了依赖 "
                    + String.join(", ", pkg.requirements()) + "，不会自动安装，请自行确认运行环境已满足");
        }
        String key = dir.getName().toLowerCase();
        if (loaded.containsKey(key)) {
            return ScriptLoadResult.skipped(pkg.name(), "已加载");
        }
        for (LoadedScript existing : loaded.values()) {
            if (existing.api().addonName().equals(pkg.name())) {
                plugin.getLogger().severe("[" + dir.getName() + "] 插件名 " + pkg.name() + " 已被另一个目录使用，已跳过");
                return ScriptLoadResult.error(pkg.name(), "插件名重复");
            }
        }
        // 脚本在执行期间就会调用 registerBotCommand，而它要求扩展已经登记，
        // 所以必须先登记；下面的回滚负责在失败时把它和脚本注册的一切一起撤掉。
        plugin.registerAddon(pkg.name(), pkg.manifest().version(),
                pkg.manifest().description().isEmpty() ? "script addon" : pkg.manifest().description(),
                pkg.manifest().author());
        ScriptLoadResult result;
        switch (Lang.of(pkg.language())) {
            case LUA:
                result = loadLuaScript(pkg);
                break;
            case PY:
                result = loadPythonScript(pkg);
                break;
            default:
                result = loadJsScript(pkg);
        }
        if (result.success()) {
            LoadedScript loadedScript = loaded.get(key);
            if (loadedScript != null) {
                registerAsAddon(loadedScript.api(), pkg);
            }
        } else {
            // 脚本执行前已经登记过，失败了不能留一个空 addon 在 QQ 菜单里。
            plugin.unregisterAddon(pkg.name());
        }
        return result;
    }

    private static String baseName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? fileName : fileName.substring(0, dot);
    }

    /** 加载失败时把脚本已经登记的东西全部撤掉，避免残留命令、监听器和定时任务。 */
    private void rollback(String name, BirdScriptApi api) {
        if (api == null) return;
        try {
            api.unregisterAll();
        } catch (Throwable t) {
            plugin.getLogger().warning("[" + name + "] 回滚已注册内容时出错: " + t.getMessage());
        }
    }

    /** 用语言描述覆盖加载前登记的那份元数据。metadata.yaml 里写了描述就用它。 */
    private void registerAsAddon(BirdScriptApi api, ScriptPackage pkg) {
        try {
            String description = pkg.manifest().description().isEmpty()
                    ? Lang.of(pkg.language()).displayName + " script addon" : pkg.manifest().description();
            api.registerAddon(pkg.name(), pkg.manifest().version(), description, pkg.manifest().author());
        } catch (Throwable t) {
            plugin.getLogger().warning("[" + pkg.name() + "] Failed to register HuHoBot addon: " + t.getMessage());
        }
    }

    /** 三个引擎都注入同一组全局对象：Bird、Bukkit、server、plugin、config、kv、DATA_DIR。 */
    private void bindCommon(Object session, BirdScriptApi api, ScriptPackage pkg) throws Exception {
        Method bind = session.getClass().getMethod("bind", String.class, Object.class);
        bind.invoke(session, "Bird", api);
        bind.invoke(session, "Bukkit", org.bukkit.Bukkit.class);
        bind.invoke(session, "server", plugin.getServer());
        bind.invoke(session, "plugin", plugin);
        bind.invoke(session, "config", pkg.config());
        bind.invoke(session, "kv", pkg.kv());
        bind.invoke(session, "DATA_DIR", pkg.dataDir().getAbsolutePath());
    }

    private ScriptLoadResult loadLuaScript(ScriptPackage pkg) {
        String name = pkg.name();
        File file = pkg.entryFile();
        BirdScriptApi api = new BirdScriptApi(plugin, name, scriptsFolder);
        try {
            Globals globals = JsePlatform.standardGlobals();

            globals.set("Bird", LuaBridge.wrap(api));
            globals.set("Bukkit", CoerceJavaToLua.coerce(org.bukkit.Bukkit.class));
            globals.set("server", CoerceJavaToLua.coerce(plugin.getServer()));
            globals.set("plugin", CoerceJavaToLua.coerce(plugin));
            globals.set("config", LuaBridge.wrap(pkg.config()));
            globals.set("kv", LuaBridge.wrap(pkg.kv()));
            globals.set("DATA_DIR", pkg.dataDir().getAbsolutePath());

            try (FileInputStream in = new FileInputStream(file)) {
                LuaValue chunk = globals.load(in, file.getName(), "t", globals);
                chunk.call();
            }

            loaded.put(pkg.directory().getName().toLowerCase(), new LoadedScript(name, file, globals, api));
            plugin.getLogger().info("Loaded script addon: " + name);
            return ScriptLoadResult.ok(name);

        } catch (LuaError e) {
            rollback(name, api);
            String msg = firstLine(e.getMessage());
            plugin.getLogger().severe("[" + name + "] Lua error: " + msg);
            return ScriptLoadResult.error(name, msg);
        } catch (Throwable t) {
            rollback(name, api);
            String msg = firstLine(String.valueOf(t.getMessage() != null ? t.getMessage() : t.toString()));
            plugin.getLogger().severe("[" + name + "] Failed to load: " + msg);
            t.printStackTrace();
            return ScriptLoadResult.error(name, msg);
        }
    }

    private ScriptLoadResult loadJsScript(ScriptPackage pkg) {
        String name = pkg.name();
        File file = pkg.entryFile();
        ClassLoader loader = engineLoader();
        if (loader == null) {
            plugin.getLogger().severe("[" + name + "] " + engineLoaderError);
            return ScriptLoadResult.error(name, engineLoaderError);
        }
        Object session = null;
        BirdScriptApi api = new BirdScriptApi(plugin, name, scriptsFolder);
        // GraalVM 从当前线程的 context classloader 发现 js 语言，主 jar 里已经没有它，
        // 所以建上下文期间必须换成引擎 jar 的加载器。
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(loader);
        try {
            Class<?> bridge = Class.forName("cn.huohuas001.huhobot.graaljs.GraalJsBridge", true, loader);
            session = bridge.getMethod("open").invoke(null);
            Thread.currentThread().setContextClassLoader(previous);
            previous = null;

            bindCommon(session, api, pkg);
            session.getClass().getMethod("eval", File.class).invoke(session, file);

            loaded.put(pkg.directory().getName().toLowerCase(), new LoadedScript(name, file, session, api));
            plugin.getLogger().info("Loaded script addon: " + name);
            return ScriptLoadResult.ok(name);

        } catch (InvocationTargetException error) {
            closeSession(session);
            rollback(name, api);
            Throwable cause = causeOf(error);
            String msg = firstLine(String.valueOf(cause.getMessage() != null ? cause.getMessage() : cause.toString()));
            plugin.getLogger().severe("[" + name + "] Failed to load: " + msg);
            cause.printStackTrace();
            return ScriptLoadResult.error(name, msg);
        } catch (Throwable t) {
            closeSession(session);
            rollback(name, api);
            String msg = firstLine(String.valueOf(t.getMessage() != null ? t.getMessage() : t.toString()));
            plugin.getLogger().severe("[" + name + "] Failed to load: " + msg);
            t.printStackTrace();
            return ScriptLoadResult.error(name, msg);
        } finally {
            if (previous != null) Thread.currentThread().setContextClassLoader(previous);
        }
    }

    /**
     * 加载 .py 脚本。
     *
     * <p>GraalPy 第一次运行会把自己的 home（标准库 + 原生库，约 1250 个文件）解压到
     * {@code %LOCALAPPDATA%\org.graalvm.polyglot\python\python-home\<内容哈希>}，这个过程在
     * 第一次建 Context 时还没完成，于是 core path 探测失败、{@code import json} 之类的标准库
     * 模块全部找不到。目录最终会解压完整，所以这种情况重建一次引擎再来就能正常。
     */
    private ScriptLoadResult loadPythonScript(ScriptPackage pkg) {
        ScriptLoadResult first = loadPythonScriptOnce(pkg);
        if (first.success() || !pythonHomeLooksIncomplete(first.message())) {
            return first;
        }
        plugin.getLogger().warning("[" + pkg.name()
                + "] GraalPy 的 home 尚未解压完成（标准库不可用），重建引擎后重试一次");
        resetPythonEngine();
        ScriptLoadResult second = loadPythonScriptOnce(pkg);
        if (second.success()) {
            plugin.getLogger().info("[" + pkg.name() + "] 重试后加载成功");
        }
        return second;
    }

    /** 报错看起来像 GraalPy 自己的 home 没就绪，而不是脚本本身写错了。 */
    private static boolean pythonHomeLooksIncomplete(String message) {
        if (message == null) return false;
        String lower = message.toLowerCase();
        return lower.startsWith("modulenotfounderror")
                || lower.contains("no module named")
                || lower.contains("core path")
                || lower.contains("stdlibhome")
                || lower.contains("corehome");
    }

    /** 丢掉缓存的 GraalPy Engine，逼它重新探测一次 home。 */
    private void resetPythonEngine() {
        Object engine;
        synchronized (this) {
            engine = pythonEngine;
            pythonEngine = null;
        }
        closeSession(engine);
    }

    private ScriptLoadResult loadPythonScriptOnce(ScriptPackage pkg) {
        String name = pkg.name();
        File file = pkg.entryFile();
        Object engine = pythonEngine();
        if (engine == null) {
            plugin.getLogger().severe("[" + name + "] " + pythonEngineError);
            return ScriptLoadResult.error(name, pythonEngineError);
        }
        Object session = null;
        BirdScriptApi api = new BirdScriptApi(plugin, name, scriptsFolder);
        // PythonLanguage 初始化同样按当前线程的 context classloader 找语言 id，
        // 这里必须还是引擎 jar 的加载器，否则会报 unknown language id python。
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(engine.getClass().getClassLoader());
        try {
            Class<?> bridge = Class.forName(
                    "cn.huohuas001.huhobot.graalpy.GraalPyBridge", true, engine.getClass().getClassLoader());
            session = bridge.getMethod("open", Object.class).invoke(null, engine);
            Thread.currentThread().setContextClassLoader(previous);
            previous = null;

            bindCommon(session, api, pkg);
            precheckPythonRequirements(session, pkg);
            session.getClass().getMethod("eval", File.class).invoke(session, file);

            loaded.put(pkg.directory().getName().toLowerCase(), new LoadedScript(name, file, session, api));
            plugin.getLogger().info("Loaded script addon: " + name);
            return ScriptLoadResult.ok(name);
        } catch (InvocationTargetException error) {
            closeSession(session);
            rollback(name, api);
            Throwable cause = causeOf(error);
            plugin.getLogger().severe("[" + name + "] Python 堆栈:\n" + stackOf(cause));
            int line = sourceLine(cause);
            String msg = firstLine(cause.getMessage());
            plugin.getLogger().severe("[" + name + "] Python error: " + msg
                    + (line >= 0 ? " (line " + line + ")" : ""));
            return line >= 0 ? ScriptLoadResult.error(name, msg, line) : ScriptLoadResult.error(name, msg);
        } catch (Throwable t) {
            closeSession(session);
            rollback(name, api);
            String msg = firstLine(String.valueOf(t.getMessage() != null ? t.getMessage() : t.toString()));
            plugin.getLogger().severe("[" + name + "] Failed to load: " + msg);
            t.printStackTrace();
            return ScriptLoadResult.error(name, msg);
        } finally {
            if (previous != null) Thread.currentThread().setContextClassLoader(previous);
        }
    }

    /** GraalPy 的脚本错误带着行号，类型是引擎 jar 里的 GraalPyBridge.Failure。 */
    private static int sourceLine(Throwable error) {
        try {
            Method line = error.getClass().getMethod("line");
            Object value = line.invoke(error);
            if (value instanceof Integer) {
                return ((Integer) value).intValue();
            }
            return -1;
        } catch (ReflectiveOperationException ignored) {
            return -1;
        }
    }

    private static Throwable causeOf(Throwable error) {
        Throwable cause = error.getCause();
        return cause == null ? error : cause;
    }

    private void closeSession(Object session) {
        if (session == null) return;
        try {
            session.getClass().getMethod("close").invoke(session);
        } catch (Exception e) {
            // Lua 的 Globals 没有 close()，这里静默跳过是正常的；
            // 真正的引擎/上下文关闭失败要让人看见。
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (!(session instanceof org.luaj.vm2.Globals)) {
                plugin.getLogger().warning("关闭脚本上下文失败: " + cause);
            }
        }
    }

    private static String stackOf(Throwable error) {
        java.io.StringWriter writer = new java.io.StringWriter();
        error.printStackTrace(new java.io.PrintWriter(writer));
        String text = writer.toString();
        return text.length() > 4000 ? text.substring(0, 4000) : text;
    }

    private String firstLine(String message) {
        if (message == null) return "unknown error";
        String[] parts = message.split("\n");
        return parts[0].trim();
    }

    /**
     * 建引擎并空跑一个上下文，让 GraalPy 先把自己的 home（约 1250 个文件）解压完。
     * 首次解压不做这步的话，第一个 .py 脚本会在解压完成前 import 标准库，
     * 然后报 core path 探测失败、{@code No module named 'json'}。
     * 没有 .py 脚本时不做任何事，不给启动添延迟。
     */
    public void warmUpPython() {
        Object engine = pythonEngine();
        if (engine == null) {
            plugin.getLogger().warning("GraalPy 预热跳过: " + pythonEngineError);
            return;
        }
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(engine.getClass().getClassLoader());
        try {
            Class<?> bridge = Class.forName(
                    "cn.huohuas001.huhobot.graalpy.GraalPyBridge", true, engine.getClass().getClassLoader());
            Object ok = bridge.getMethod("warmUp", Object.class).invoke(null, engine);
            if (Boolean.TRUE.equals(ok)) {
                plugin.getLogger().info("GraalPy home 已就绪");
            } else {
                plugin.getLogger().warning("GraalPy home 预热未成功，.py 脚本加载失败时会重试一次");
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("GraalPy 预热异常: " + t);
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    /** addons/ 下是否存在 .py 入口。 */
    private boolean hasPythonAddon() {
        File[] dirs = scriptsFolder.listFiles(File::isDirectory);
        if (dirs == null) return false;
        for (File dir : dirs) {
            if (isReservedDir(dir.getName())) continue;
            if (new File(dir, "main.py").isFile()) return true;
        }
        return false;
    }

    public void unloadAll() {
        for (LoadedScript s : loaded.values()) {
            unload(s);
        }
        loaded.clear();
    }

    /** 撤掉一个脚本登记过的一切：命令、监听器、定时任务、QQ 群命令和 addon 元数据。 */
    private void unload(LoadedScript script) {
        String name = script.name();
        try {
            script.api().unregisterAll();
        } catch (Exception e) {
            plugin.getLogger().warning("Problem unloading script " + name + ": " + e.getMessage());
        }
        try {
            // 不撤 addon 的话，QQ 面板会一直留着这个脚本已经删掉的命令。
            plugin.unregisterAddon(name);
        } catch (Exception e) {
            plugin.getLogger().warning("Problem unregistering addon " + name + ": " + e.getMessage());
        }
        closeSession(script.session());
    }

    /**
     * 关掉 engines/ 里的 GraalPy Engine 和 URLClassLoader。只在插件停用时调用——
     * 它们是跨脚本共享的，中途关掉会让还在跑的脚本失去引擎。
     */
    public void closeEngines() {
        Object engine = pythonEngine;
        pythonEngine = null;
        if (engine != null) {
            closeSession(engine);
        }
        ClassLoader loader = engineLoader;
        engineLoader = null;
        if (loader instanceof URLClassLoader) {
            try {
                ((URLClassLoader) loader).close();
            } catch (IOException e) {
                plugin.getLogger().warning("关闭脚本引擎类加载器失败: " + e.getMessage());
            }
        }
    }

    public List<ScriptLoadResult> reloadAll() {
        unloadAll();
        return loadAll();
    }

    /** 重载一个目录插件。参数是目录名，大小写不敏感。 */
    public ScriptLoadResult reloadOne(String nameInput) {
        File[] dirs = scriptsFolder.listFiles(File::isDirectory);
        File target = null;
        if (dirs != null) {
            for (File dir : dirs) {
                if (dir.getName().equalsIgnoreCase(nameInput.trim())) {
                    target = dir;
                    break;
                }
            }
        }
        if (target == null) {
            return ScriptLoadResult.notFound(nameInput);
        }

        LoadedScript existing = loaded.remove(target.getName().toLowerCase());
        if (existing != null) {
            unload(existing);
        }

        return loadDirectory(target);
    }

    /** 列出 addons/ 下的插件目录名，给命令补全用。 */
    public List<String> listScriptFileNames() {
        File[] dirs = scriptsFolder.listFiles(File::isDirectory);
        List<String> names = new ArrayList<>();
        if (dirs != null) {
            for (File dir : dirs) {
                if (isReservedDir(dir.getName())) continue;
                names.add(dir.getName());
            }
        }
        return names;
    }

    /** requirements.txt 的预检：逐个 import，失败只记警告，不安装。 */
    private void precheckPythonRequirements(Object session, ScriptPackage pkg) {
        for (String requirement : pkg.requirements()) {
            String module = requirement.replaceAll("[<>=!\\[;].*$", "").trim().replace("'", "");
            if (module.isEmpty()) continue;
            try {
                session.getClass().getMethod("evalSource", String.class).invoke(session, "__import__('" + module + "')");
            } catch (Throwable missing) {
                plugin.getLogger().warning("[" + pkg.name() + "] 依赖预检: 缺少 " + module
                        + "（requirements.txt），GraalPy 默认不带第三方库");
            }
        }
    }

    public int getLoadedCount() {
        return loaded.size();
    }

    public File getScriptsFolder() {
        return scriptsFolder;
    }
}
