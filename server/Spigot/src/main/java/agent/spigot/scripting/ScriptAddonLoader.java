package agent.spigot.scripting;

import agent.spigot.HuHoBotSpigot;
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
import java.util.concurrent.*;
import java.lang.reflect.Field;

/** Loads trusted JS, Lua and Java directory addons using the small bundled runtimes. */
public class ScriptAddonLoader {

    private final HuHoBotSpigot plugin;
    private final File scriptsFolder;
    private final File addonStateFolder;
    private final Map<String, LoadedScript> loaded = new ConcurrentHashMap<>();
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"HuHoBot-AddonPrepare");t.setDaemon(true);return t;});
    private final java.util.Set<CompletableFuture<?>> mainCalls=ConcurrentHashMap.newKeySet();
    private volatile boolean closing;
    private <T>T onMain(Callable<T> action)throws Exception{
        if(closing)throw new CancellationException("addon loader is stopping");
        if(agent.spigot.manager.FoliaScheduler.isGlobalThread())return action.call();
        CompletableFuture<T> result=new CompletableFuture<>();mainCalls.add(result);
        try{
            plugin.submit(()->{if(result.isDone()||closing){result.completeExceptionally(new CancellationException());return;}
                try{result.complete(action.call());}catch(Throwable error){result.completeExceptionally(error);}});
            return result.get();
        }finally{mainCalls.remove(result);}
    }
    private interface Prepared{void run()throws Exception;}
    private void activate(ScriptPackage pkg,Object session,BirdScriptApi api,Prepared code)throws Exception{
        onMain(()->{
            if(closing)throw new CancellationException("addon loader is stopping");
            try{code.run();loaded.put(pkg.directory().getName().toLowerCase(),new LoadedScript(pkg.name(),pkg.entryFile(),session,api));}
            catch(Throwable error){api.unregisterAll();if(session instanceof JavaSession)((JavaSession)session).stopProgram();throw error;}
            return null;
        });
    }
    /** Heavy engine initialization, source compilation and IO run only on this serial worker. */
    public CompletableFuture<List<ScriptLoadResult>> loadAllAsync(){return CompletableFuture.supplyAsync(this::loadAll,worker);}
    public CompletableFuture<List<ScriptLoadResult>> reloadAllAsync(){return CompletableFuture.supplyAsync(this::reloadAll,worker);}
    public CompletableFuture<ScriptLoadResult> reloadOneAsync(String name){return CompletableFuture.supplyAsync(()->reloadOne(name),worker);}
    public void shutdownAsync(){
        closing=true;mainCalls.forEach(f->f.completeExceptionally(new CancellationException("plugin disabled")));
        unloadAll();worker.shutdownNow();
    }
    public ScriptAddonLoader(HuHoBotSpigot plugin) {
        this.plugin = plugin;
        this.scriptsFolder = new File(plugin.getDataFolder(), "addons");
        this.addonStateFolder = plugin.getDataDirectory();
        if (!scriptsFolder.exists()) {
            scriptsFolder.mkdirs();
        }
    }

    /** 三个受支持的脚本语言，扩展名只在这里解析一次。 */
    private enum Lang {
        LUA(".lua", "Lua"),
        JS(".js", "JavaScript"),
        JAVA(".java", "Java");

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
        return name.startsWith(".") || "config".equalsIgnoreCase(name)
                || "data".equalsIgnoreCase(name)
                || "files".equalsIgnoreCase(name)
                || "engines".equalsIgnoreCase(name);
    }

    public List<ScriptLoadResult> loadAll() {
        if(agent.spigot.manager.FoliaScheduler.isTickThread())throw new IllegalStateException("Use loadAllAsync: engine work must not block the server thread");
        List<ScriptLoadResult> results = new ArrayList<>();
        if(closing)return results;
        try {
            ModuleDownloads.applyPending(plugin.getDataDirectory(),message->plugin.getLogger().warning(message));
            warnStrayScripts();
            File[] dirs = scriptsFolder.listFiles(File::isDirectory);
            if (dirs == null || dirs.length == 0) {
                plugin.getLogger().info("addons 目录没有脚本插件（建一个目录，放 main.js / main.lua / Main.java）: "
                        + scriptsFolder.getPath());
                return results;
            }
            Arrays.sort(dirs, Comparator.comparing(File::getName));
            for (File dir : dirs) {
                if(closing)break;
                if (isReservedDir(dir.getName())) continue;
                results.add(loadDirectory(dir));
            }
        } catch (Throwable t) {
            plugin.getLogger().severe("Fatal error while scanning the addon scripts folder: " + t);
            t.printStackTrace();
        }
        return results;
    }

    /** 旧结构提示：addons/ 根上直接放 .js/.lua/.java 已不再加载。 */
    private void warnStrayScripts() {
        File[] stray = scriptsFolder.listFiles((dir, name) -> {
            String lower = name.toLowerCase();
            return lower.endsWith(".js") || lower.endsWith(".lua") || lower.endsWith(".java") || lower.endsWith(".java");
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
     * main.js、main.lua、Main.java 找第一个存在的。
     */
    public ScriptLoadResult loadDirectory(File dir) {
        if(closing)return ScriptLoadResult.error(dir.getName(),"扩展加载已取消");
        ScriptPackage pkg = ScriptPackage.open(dir, addonStateFolder);
        if (pkg == null) {
            String msg = "目录里没有 main.js / main.lua / Main.java";
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
        try{onMain(()->{plugin.registerAddon(pkg.name(),pkg.manifest().version(),pkg.manifest().description().isEmpty()?"script addon":pkg.manifest().description(),pkg.manifest().author());return null;});}
        catch(Exception stopped){return ScriptLoadResult.error(pkg.name(),"扩展加载已取消");}
        ScriptLoadResult result;
        switch (Lang.of(pkg.language())) {
            case LUA:
                result = loadLuaScript(pkg);
                break;
            case JAVA:
                result = loadJavaScript(pkg);
                break;
            default:
                result = loadBuiltinJs(pkg);
        }
        if (result.success()) {
            LoadedScript loadedScript = loaded.get(key);
            if (loadedScript != null) {
                try{onMain(()->{registerAsAddon(loadedScript.api(),pkg);return null;});}catch(Exception ignored){}
            }
        } else {
            // 脚本执行前已经登记过，失败了不能留一个空 addon 在 QQ 菜单里。
            try{onMain(()->{plugin.unregisterAddon(pkg.name());return null;});}catch(Exception ignored){}
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
            onMain(()->{api.unregisterAll();return null;});
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
        BirdScriptApi api = new BirdScriptApi(plugin, name, addonStateFolder);
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
                activate(pkg,globals,api,()->chunk.call());
            }

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

    private ScriptLoadResult loadBuiltinJs(ScriptPackage pkg) {
        BirdScriptApi api=new BirdScriptApi(plugin,pkg.name(),addonStateFolder);
        RhinoBridge session=null;
        try{
            session=new RhinoBridge();bindCommon(session,api,pkg);session.prepare(pkg.entryFile());
            final RhinoBridge ready=session;activate(pkg,ready,api,ready::runPrepared);
            plugin.getLogger().info("Loaded addon: "+pkg.name()+" (bundled JavaScript)");return ScriptLoadResult.ok(pkg.name());
        }catch(Throwable error){closeSession(session);rollback(pkg.name(),api);return ScriptLoadResult.error(pkg.name(),firstLine(error.toString()));}
    }
    private ScriptLoadResult loadJavaScript(ScriptPackage pkg) {
        BirdScriptApi api=new BirdScriptApi(plugin,pkg.name(),addonStateFolder);
        try{
            JavaSession session=JavaSession.prepare(pkg,plugin.getDescription().getVersion());
            try{activate(pkg,session,api,()->session.start(api));}catch(Throwable error){session.close();throw error;}
            plugin.getLogger().info("Loaded addon: "+pkg.name()+" (Java)");return ScriptLoadResult.ok(pkg.name());
        }catch(Throwable error){rollback(pkg.name(),api);return ScriptLoadResult.error(pkg.name(),firstLine(error.toString()));}
    }

    private static Throwable causeOf(Throwable error) {
        while((error instanceof InvocationTargetException||error instanceof ExecutionException)&&error.getCause()!=null)error=error.getCause();
        return error;
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

    public void unloadAll() {
        for (LoadedScript s : loaded.values()) {
            unload(s);
        }
        loaded.clear();
    }

    /** 撤掉一个脚本登记过的一切：命令、监听器、定时任务、QQ 群命令和 addon 元数据。 */
    private void unload(LoadedScript script) {
        Callable<Void> cleanup=()->{script.api().unregisterAll();plugin.unregisterAddon(script.name());
            if(script.session() instanceof JavaSession)((JavaSession)script.session()).stopProgram();return null;};
        try{if(closing)cleanup.call();else onMain(cleanup);}
        catch(Exception error){plugin.getLogger().warning("卸载扩展失败: "+script.name()+": "+error.getMessage());}
        closeSession(script.session());
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

    public int getLoadedCount() {
        return loaded.size();
    }

    public File getScriptsFolder() {
        return scriptsFolder;
    }
}
