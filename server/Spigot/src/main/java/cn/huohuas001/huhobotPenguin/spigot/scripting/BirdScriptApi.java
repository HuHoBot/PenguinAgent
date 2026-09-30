package cn.huohuas001.huhobotPenguin.spigot.scripting;

import cn.huohuas001.huhobotPenguin.spigot.HuHoBotSpigot;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Server;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandMap;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * Scripting facade exposed to JS/Python/Lua addons as the global {@code Bird}.
 *
 * Ported from BirdLibraryApi's {@code BirdAPI}
 * (https://github.com/prach1121/birdlibraryapi, Apache-2.0) and adapted for
 * Spigot 1.16+ (no Adventure API) plus HuHoBot addon registration.
 */
public class BirdScriptApi {

    private final HuHoBotSpigot plugin;
    private final String scriptName;
    /** Graal 的两个引擎桥，按顺序尝试；GraalJS / GraalPy 的 adapt 签名一致。 */
    private static final String[] GRAAL_BRIDGES = {
            "cn.huohuas001.huhobot.graaljs.GraalJsBridge",
            "cn.huohuas001.huhobot.graalpy.GraalPyBridge"
    };

    /** Graal 引擎把脚本函数以原始 value 交出来，这个字段把它转成方法声明的接口。 */
    private volatile java.lang.reflect.Method jsAdapter;
    private final Listener dummyListener = new Listener() {};
    private final List<Runnable> cleanupTasks = new ArrayList<>();
    /** QQ bot commands this script registered; dropped on reload so keys do not leak. */
    private final java.util.Set<String> botCommandKeys = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static CommandMap commandMap;

    private static final ConcurrentHashMap<String, Long> cooldowns = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, CopyOnWriteArrayList<Consumer<Object>>> customEventBus = new ConcurrentHashMap<>();

    private final File dataFile;
    private final Properties data = new Properties();
    private boolean dataLoaded = false;

    private final File filesFolder;

    private static final Set<String> BLOCKED_EXTENSIONS = new HashSet<>(Arrays.asList(
            "exe", "bat", "cmd", "dll", "so", "autorun", "ps1", "ps2", "psm1",
            "php", "sh", "bash", "vbs", "vbe", "wsf", "wsh", "jse", "jar", "msi", "scr"
    ));

    public BirdScriptApi(HuHoBotSpigot plugin, String scriptName, File addonsFolder) {
        this.plugin = plugin;
        this.scriptName = scriptName;

        String baseName = baseName(scriptName);
        File dataFolder = new File(addonsFolder, "data");
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
        this.dataFile = new File(dataFolder, baseName + ".properties");
        this.filesFolder = new File(new File(addonsFolder, "files"), baseName);
    }

    /** File name without the language extension, used as the HuHoBot addon name. */
    public String addonName() {
        return baseName(scriptName);
    }

    private static String baseName(String scriptName) {
        String lower = scriptName.toLowerCase();
        if (lower.endsWith(".js") || lower.endsWith(".lua") || lower.endsWith(".py")) {
            return scriptName.substring(0, scriptName.lastIndexOf('.'));
        }
        return scriptName;
    }

    /**
     * 把脚本回调适配成目标函数式接口。
     *
     * <p>JavaScript：GraalJS 会把脚本函数保留成自己的值对象（它的默认转换只会产出
     * {@code java.util.function.Function}），由引擎 jar 里的 {@code GraalJsBridge.adapt} 转换。
     *
     * <p>Lua：LuaJ 在方法参数超过 2 个且其中含函数式接口时无法自动转换，会抛
     * {@code no coercible public method}。因此这里用 {@link java.lang.reflect.Proxy} 手动桥接：
     * Java 参数转成 LuaValue 调用脚本函数，返回值再转回 Java。
     *
     * <p>Python：GraalPy 直接给出目标接口的实例，原样返回。
     */
    private Object adapt(Object value, Class<?> type) {
        if (value == null || type == null || !type.isInterface() || type.isInstance(value)) return value;

        if (value instanceof org.luaj.vm2.LuaValue) {
            return luaProxy((org.luaj.vm2.LuaValue) value, type);
        }

        java.lang.reflect.Method adapter = jsAdapter;
        if (adapter == null) {
            adapter = resolveAdapter(value.getClass().getClassLoader());
            if (adapter == null) return value;
            jsAdapter = adapter;
        }
        try {
            return adapter.invoke(null, value, type);
        } catch (ReflectiveOperationException error) {
            warn("无法适配 Graal 回调: " + error.getMessage());
            return value;
        }
    }

    /**
     * 找 Graal 引擎的 adapt 实现。两个引擎的 host access 都把脚本函数以原始
     * {@code Value} 交出来（目标类型是 {@code Object}，没有接口可按目标类型转换），
     * 所以桥接类必须自己 proxy。两个引擎的桥都可能在也可能只有一个，
     * 因此依次尝试，找到哪个算哪个。
     */
    private java.lang.reflect.Method resolveAdapter(ClassLoader loader) {
        for (String bridge : GRAAL_BRIDGES) {
            try {
                Class<?> type = Class.forName(bridge, false, loader);
                return type.getMethod("adapt", Object.class, Class.class);
            } catch (ClassNotFoundException | NoSuchMethodException absent) {
                // 试下一个
            } catch (Throwable error) {
                warn("无法加载 " + bridge + ": " + error);
            }
        }
        return null;
    }

    /** 用动态代理把 LuaJ 的 LuaFunction 桥接成 Java 函数式接口。 */
    private Object luaProxy(org.luaj.vm2.LuaValue function, Class<?> type) {
        return java.lang.reflect.Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        String name = method.getName();
                        if ("toString".equals(name)) return "LuaFunctionProxy";
                        if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                        if ("equals".equals(name)) return proxy == args[0];
                        return null;
                    }
                    org.luaj.vm2.LuaValue[] luaArgs = new org.luaj.vm2.LuaValue[args == null ? 0 : args.length];
                    for (int i = 0; i < luaArgs.length; i++) {
                        luaArgs[i] = org.luaj.vm2.lib.jse.CoerceJavaToLua.coerce(args[i]);
                    }
                    // LuaJ 的可变参数入口是 invoke(LuaValue[])，call() 只有固定元数。
                    org.luaj.vm2.LuaValue returned = function.invoke(luaArgs).arg1();
                    return luaToJava(returned, method.getReturnType());
                });
    }

    /** 把 Lua 返回值转回 Java：LuaTable→List、LuaString→String、数字→Number、LuaBoolean→boolean、nil→null。 */
    private static Object luaToJava(org.luaj.vm2.LuaValue value, Class<?> returnType) {
        if (value == null || value.isnil()) return null;
        if (value instanceof org.luaj.vm2.LuaTable) {
            org.luaj.vm2.LuaTable table = (org.luaj.vm2.LuaTable) value;
            List<Object> list = new ArrayList<>();
            int length = table.length();
            for (int i = 1; i <= length; i++) {
                list.add(luaToJava(table.get(i), Object.class));
            }
            return list;
        }
        if (value instanceof org.luaj.vm2.LuaString) return value.tojstring();
        if (value instanceof org.luaj.vm2.LuaNumber) {
            double d = value.todouble();
            if (returnType == int.class || returnType == Integer.class) return (int) d;
            if (returnType == long.class || returnType == Long.class) return (long) d;
            if (returnType == double.class || returnType == Double.class) return d;
            if (returnType == float.class || returnType == Float.class) return (float) d;
            return (int) d;
        }
        if (value instanceof org.luaj.vm2.LuaBoolean) return value.toboolean();
        return value.tojstring();
    }

    /** 把任意脚本回调转成 Runnable（Lua/JS 走 Proxy，Java 侧本来就是 Runnable）。 */
    private Runnable toRunnable(Object callback) {
        if (callback instanceof Runnable) return (Runnable) callback;
        Object adapted = adapt(callback, Runnable.class);
        if (adapted instanceof Runnable) return (Runnable) adapted;
        return () -> warn("任务回调不是可调用的函数: " + callback);
    }

    public void log(Object message) {
        plugin.getLogger().info("[" + scriptName + "] " + message);
    }

    public void warn(Object message) {
        plugin.getLogger().warning("[" + scriptName + "] " + message);
    }

    // ---------------------------------------------------------------- events

    public void onEvent(String eventClassName, String priority, Object callback) {
        Object adapted = adapt(callback, EventCallback.class);
        if (!(adapted instanceof EventCallback)) {
            warn("事件回调不是可调用的函数: " + eventClassName);
            return;
        }
        EventCallback handler = (EventCallback) adapted;
        try {
            @SuppressWarnings("unchecked")
            Class<? extends Event> eventClass = (Class<? extends Event>) Class.forName(eventClassName);

            EventPriority p;
            try {
                p = EventPriority.valueOf(priority.toUpperCase());
            } catch (Exception ex) {
                p = EventPriority.NORMAL;
            }

            EventExecutor executor = (listener, event) -> {
                if (eventClass.isInstance(event)) {
                    try {
                        handler.execute(event);
                    } catch (Throwable t) {
                        warn("Runtime error in event handler (" + eventClass.getSimpleName() + "): "
                                + (t.getMessage() != null ? t.getMessage() : t));
                        t.printStackTrace();
                    }
                }
            };

            plugin.getServer().getPluginManager()
                    .registerEvent(eventClass, dummyListener, p, executor, plugin, false);

            cleanupTasks.add(() -> HandlerList.unregisterAll(dummyListener));
            log("Registered event listener: " + eventClassName + " (priority=" + p + ")");
        } catch (ClassNotFoundException e) {
            warn("Unknown event class: " + eventClassName
                    + " (use the fully-qualified name, e.g. org.bukkit.event.player.PlayerJoinEvent)");
        }
    }

    public void onEvent(String eventClassName, Object callback) {
        onEvent(eventClassName, "NORMAL", callback);
    }

    // ---------------------------------------------------------------- commands

    public void onCommand(String name, Object callback) {
        onCommand(name, null, callback, null);
    }

    public void onCommand(String name, String permission, Object callback) {
        onCommand(name, permission, callback, null);
    }

    public void onCommand(String name, Object callback, Object tabCompleteCallback) {
        onCommand(name, null, callback, tabCompleteCallback);
    }

    public void onCommand(String name, String permission, Object callback, Object tabCompleteCallback) {
        Object adaptedCommand = adapt(callback, CommandCallback.class);
        if (!(adaptedCommand instanceof CommandCallback)) {
            warn("命令回调不是可调用的函数: /" + name);
            return;
        }
        Object adaptedTab = tabCompleteCallback == null ? null : adapt(tabCompleteCallback, TabCompleteCallback.class);
        if (tabCompleteCallback != null && !(adaptedTab instanceof TabCompleteCallback)) {
            warn("Tab 补全回调不是可调用的函数: /" + name);
            adaptedTab = null;
        }
        CommandCallback commandCallback = (CommandCallback) adaptedCommand;
        TabCompleteCallback tab = (TabCompleteCallback) adaptedTab;
        CommandMap map = getCommandMap();
        if (map == null) {
            warn("Could not access the CommandMap - command /" + name + " will not work");
            return;
        }
        BirdDynamicCommand cmd = new BirdDynamicCommand(name, commandCallback);
        if (permission != null && !permission.isEmpty()) {
            cmd.setPermission(permission);
        }
        if (tab != null) {
            cmd.setTabCompleteCallback(tab);
        }
        map.register(plugin.getName().toLowerCase(), cmd);
        cleanupTasks.add(() -> removeCommand(map, cmd));
        log("Registered command: /" + name + (permission != null ? " (permission=" + permission + ")" : ""));
    }

    /**
     * Removes a dynamically registered command for real. Bukkit's
     * {@code Command.unregister(CommandMap)} only detaches the map from the command
     * and leaves it in {@code SimpleCommandMap#knownCommands}, so after a reload the
     * stale copy still answers the name and points at a closed script context.
     */
    @SuppressWarnings("unchecked")
    private static void removeCommand(CommandMap map, org.bukkit.command.Command command) {
        try {
            java.lang.reflect.Method known = map.getClass().getMethod("getKnownCommands");
            Map<String, org.bukkit.command.Command> commands = (Map<String, org.bukkit.command.Command>) known.invoke(map);
            commands.values().removeIf(candidate -> candidate == command);
        } catch (Throwable t) {
            command.unregister(map);
        }
    }

    // ---------------------------------------------------------------- scheduler

    public int runTask(Object task) {
        BukkitTask t = plugin.getServer().getScheduler().runTask(plugin, wrap(task));
        trackTask(t);
        return t.getTaskId();
    }

    public int runTaskLater(Object task, long delayTicks) {
        BukkitTask t = plugin.getServer().getScheduler().runTaskLater(plugin, wrap(task), delayTicks);
        trackTask(t);
        return t.getTaskId();
    }

    public int runTaskTimer(Object task, long delayTicks, long periodTicks) {
        BukkitTask t = plugin.getServer().getScheduler().runTaskTimer(plugin, wrap(task), delayTicks, periodTicks);
        trackTask(t);
        return t.getTaskId();
    }

    public int runTaskAsync(Object task) {
        BukkitTask t = plugin.getServer().getScheduler().runTaskAsynchronously(plugin, wrap(task));
        trackTask(t);
        return t.getTaskId();
    }

    public int runTaskTimerAsync(Object task, long delayTicks, long periodTicks) {
        BukkitTask t = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, wrap(task), delayTicks, periodTicks);
        trackTask(t);
        return t.getTaskId();
    }

    public void cancelTask(int taskId) {
        plugin.getServer().getScheduler().cancelTask(taskId);
    }

    private Runnable wrap(Object task) {
        Runnable adapted = toRunnable(task);
        return () -> {
            try {
                adapted.run();
            } catch (Throwable t) {
                warn("Runtime error in scheduled task: " + (t.getMessage() != null ? t.getMessage() : t));
                t.printStackTrace();
            }
        };
    }

    private void trackTask(BukkitTask t) {
        cleanupTasks.add(() -> {
            try {
                t.cancel();
            } catch (Exception ignored) {
            }
        });
    }

    // ---------------------------------------------------------------- chat

    public void broadcast(String message) {
        plugin.getServer().broadcastMessage(colorize(message));
    }

    public void broadcast(String message, String permission) {
        String colored = colorize(message);
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (p.hasPermission(permission)) {
                p.sendMessage(colored);
            }
        }
    }

    public void broadcastChat(String message) {
        broadcast(message);
    }

    public void broadcastChat(String message, String permission) {
        broadcast(message, permission);
    }

    public void tell(Player player, String message) {
        if (player != null) {
            player.sendMessage(colorize(message));
        }
    }

    /**
     * 往任意命令发送者回消息。控制台、命令方块、玩家都走这个，所以脚本在控制台里
     * 执行命令时也能正常回应——只有 {@link #tell(Player, String)} 的话会直接类型不匹配。
     */
    public void tell(org.bukkit.command.CommandSender target, String message) {
        if (target != null) {
            target.sendMessage(colorize(message));
        }
    }

    public boolean tell(String playerName, String message) {
        Player player = getPlayer(playerName);
        if (player == null) {
            return false;
        }
        player.sendMessage(colorize(message));
        return true;
    }

    public String colorize(String message) {
        return message == null ? null : ChatColor.translateAlternateColorCodes('&', message);
    }

    public void sendTitle(Player player, String title, String subtitle, int fadeInTicks, int stayTicks, int fadeOutTicks) {
        if (player == null) return;
        player.sendTitle(colorize(title), colorize(subtitle), ticks(fadeInTicks), ticks(stayTicks), ticks(fadeOutTicks));
    }

    public void sendTitle(Player player, String title, String subtitle) {
        sendTitle(player, title, subtitle, -1, -1, -1);
    }

    public void broadcastTitle(String title, String subtitle, int fadeInTicks, int stayTicks, int fadeOutTicks) {
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            sendTitle(p, title, subtitle, fadeInTicks, stayTicks, fadeOutTicks);
        }
    }

    public void broadcastTitle(String title, String subtitle) {
        broadcastTitle(title, subtitle, -1, -1, -1);
    }

    public void broadcastTitle(String title, String subtitle, String permission) {
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (p.hasPermission(permission)) {
                sendTitle(p, title, subtitle, -1, -1, -1);
            }
        }
    }

    private int ticks(int value) {
        return value >= 0 ? value : 10;
    }

    public void sendActionBar(Player player, String message) {
        if (player != null) {
            player.sendMessage(colorize(message));
        }
    }

    public void broadcastActionBar(String message) {
        broadcast(message);
    }

    public void broadcastActionBar(String message, String permission) {
        broadcast(message, permission);
    }

    // ---------------------------------------------------------------- players

    public Player getPlayer(String name) {
        return plugin.getServer().getPlayer(name);
    }

    public Player getPlayerExact(String name) {
        return plugin.getServer().getPlayerExact(name);
    }

    public List<Player> getOnlinePlayers() {
        return new ArrayList<>(plugin.getServer().getOnlinePlayers());
    }

    public Server getServer() {
        return plugin.getServer();
    }

    // ---------------------------------------------------------------- data store

    private void ensureLoaded() {
        if (dataLoaded) return;
        dataLoaded = true;
        if (dataFile.exists()) {
            try (InputStreamReader reader = new InputStreamReader(new FileInputStream(dataFile), StandardCharsets.UTF_8)) {
                data.load(reader);
            } catch (IOException e) {
                warn("Failed to load stored data (" + dataFile.getName() + "): " + e.getMessage());
            }
        }
    }

    public void setData(String key, String value) {
        ensureLoaded();
        data.setProperty(key, value);
        saveData();
    }

    public String getData(String key) {
        return getData(key, null);
    }

    public String getData(String key, String defaultValue) {
        ensureLoaded();
        return data.getProperty(key, defaultValue);
    }

    public void removeData(String key) {
        ensureLoaded();
        data.remove(key);
        saveData();
    }

    public List<String> getDataKeys() {
        ensureLoaded();
        return new ArrayList<>(data.stringPropertyNames());
    }

    public void saveData() {
        ensureLoaded();
        try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(dataFile), StandardCharsets.UTF_8)) {
            data.store(writer, "HuHoBot script data for " + scriptName);
        } catch (IOException e) {
            warn("Failed to save stored data: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- items / effects

    public void giveItem(Player player, String materialName) {
        giveItem(player, materialName, 1, null, null);
    }

    public void giveItem(Player player, String materialName, int amount) {
        giveItem(player, materialName, amount, null, null);
    }

    public void giveItem(Player player, String materialName, int amount, String displayName) {
        giveItem(player, materialName, amount, displayName, null);
    }

    public void giveItem(Player player, String materialName, int amount, String displayName, List<String> lore) {
        if (player == null) return;
        Material material = Material.matchMaterial(materialName);
        if (material == null) {
            warn("Unknown material: " + materialName);
            return;
        }
        ItemStack stack = new ItemStack(material, Math.max(1, amount));
        if (displayName != null || (lore != null && !lore.isEmpty())) {
            ItemMeta meta = stack.getItemMeta();
            if (meta != null) {
                if (displayName != null) meta.setDisplayName(colorize(displayName));
                if (lore != null && !lore.isEmpty()) {
                    List<String> colored = new ArrayList<>();
                    for (String line : lore) colored.add(colorize(line));
                    meta.setLore(colored);
                }
                stack.setItemMeta(meta);
            }
        }
        player.getInventory().addItem(stack);
    }

    public void playSound(Player player, String soundName) {
        playSound(player, soundName, 1f, 1f);
    }

    public void playSound(Player player, String soundName, float volume, float pitch) {
        if (player == null) return;
        Sound sound = soundByName(soundName);
        if (sound == null) {
            warn("Unknown sound: " + soundName);
            return;
        }
        player.playSound(player.getLocation(), sound, volume, pitch);
    }

    public void broadcastSound(String soundName, float volume, float pitch) {
        Sound sound = soundByName(soundName);
        if (sound == null) {
            warn("Unknown sound: " + soundName);
            return;
        }
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            p.playSound(p.getLocation(), sound, volume, pitch);
        }
    }

    private Sound soundByName(String soundName) {
        try {
            return Sound.valueOf(soundName.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public void spawnParticle(Player player, String particleName, int count) {
        if (player == null) return;
        Particle particle;
        try {
            particle = Particle.valueOf(particleName.toUpperCase());
        } catch (IllegalArgumentException e) {
            warn("Unknown particle: " + particleName);
            return;
        }
        player.getWorld().spawnParticle(particle, player.getLocation(), Math.max(1, count));
    }

    public void teleport(Player player, double x, double y, double z) {
        if (player == null) return;
        player.teleport(new Location(player.getWorld(), x, y, z));
    }

    public void teleport(Player player, double x, double y, double z, String worldName) {
        if (player == null) return;
        World world = plugin.getServer().getWorld(worldName);
        if (world == null) {
            warn("Unknown world: " + worldName);
            return;
        }
        player.teleport(new Location(world, x, y, z));
    }

    public Block getBlock(String worldName, int x, int y, int z) {
        World world = plugin.getServer().getWorld(worldName);
        return world == null ? null : world.getBlockAt(x, y, z);
    }

    public String getBlockType(String worldName, int x, int y, int z) {
        Block block = getBlock(worldName, x, y, z);
        return block == null ? null : block.getType().name();
    }

    public boolean isBlockType(String worldName, int x, int y, int z, String materialName) {
        String type = getBlockType(worldName, x, y, z);
        return type != null && type.equalsIgnoreCase(materialName);
    }

    public boolean setBlockType(String worldName, int x, int y, int z, String materialName) {
        Block block = getBlock(worldName, x, y, z);
        Material material = Material.matchMaterial(materialName);
        if (block == null || material == null) {
            warn("Cannot set block: world=" + worldName + " material=" + materialName);
            return false;
        }
        block.setType(material);
        return true;
    }

    public Block getTargetBlock(Player player) {
        return getTargetBlock(player, 5);
    }

    @SuppressWarnings("deprecation")
    public Block getTargetBlock(Player player, int maxDistance) {
        if (player == null) return null;
        return player.getTargetBlock(null, Math.max(1, maxDistance));
    }

    public Block getBlockPlayerIsOn(Player player) {
        if (player == null) return null;
        return player.getLocation().getBlock().getRelative(BlockFace.DOWN);
    }

    // ---------------------------------------------------------------- cooldown

    public void setCooldown(String key, Player player, double seconds) {
        if (player == null) return;
        cooldowns.put(cooldownKey(key, player), System.currentTimeMillis() + (long) (seconds * 1000));
    }

    public boolean hasCooldown(String key, Player player) {
        return getCooldownRemaining(key, player) > 0;
    }

    public double getCooldownRemaining(String key, Player player) {
        if (player == null) return 0;
        Long until = cooldowns.get(cooldownKey(key, player));
        if (until == null) return 0;
        long left = until - System.currentTimeMillis();
        return left > 0 ? left / 1000.0 : 0;
    }

    public void clearCooldown(String key, Player player) {
        if (player == null) return;
        cooldowns.remove(cooldownKey(key, player));
    }

    private String cooldownKey(String key, Player player) {
        return scriptName + ":" + key + ":" + player.getUniqueId();
    }

    // ---------------------------------------------------------------- misc helpers

    public int random(int min, int max) {
        if (max <= min) return min;
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    public int getOnlineCount() {
        return plugin.getServer().getOnlinePlayers().size();
    }

    public int getMaxPlayers() {
        return plugin.getServer().getMaxPlayers();
    }

    public boolean isOnline(String playerName) {
        return getPlayer(playerName) != null;
    }

    public double getHealth(Player player) {
        return player == null ? 0 : player.getHealth();
    }

    @SuppressWarnings("deprecation")
    public void setHealth(Player player, double amount) {
        if (player == null) return;
        player.setHealth(Math.max(0, Math.min(amount, player.getMaxHealth())));
    }

    public void giveExp(Player player, int amount) {
        if (player != null) player.giveExp(amount);
    }

    public boolean setGameMode(Player player, String mode) {
        if (player == null) return false;
        try {
            player.setGameMode(GameMode.valueOf(mode.toUpperCase()));
            return true;
        } catch (IllegalArgumentException e) {
            warn("Unknown game mode: " + mode);
            return false;
        }
    }

    public String getGameMode(Player player) {
        return player == null ? null : player.getGameMode().name();
    }

    public void kick(Player player, String reason) {
        if (player != null) player.kickPlayer(colorize(reason));
    }

    public double getDistance(Player a, Player b) {
        if (a == null || b == null || a.getWorld() != b.getWorld()) return -1;
        return a.getLocation().distance(b.getLocation());
    }

    public String stripColor(String message) {
        return message == null ? null : ChatColor.stripColor(colorize(message));
    }

    public String formatTime(long totalSeconds) {
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }

    public List<String> getWorldNames() {
        List<String> names = new ArrayList<>();
        for (World world : plugin.getServer().getWorlds()) names.add(world.getName());
        return names;
    }

    public boolean hasItem(Player player, String materialName, int amount) {
        if (player == null) return false;
        Material material = Material.matchMaterial(materialName);
        if (material == null) return false;
        return player.getInventory().contains(material, Math.max(1, amount));
    }

    public List<String> getPlayerNames() {
        List<String> names = new ArrayList<>();
        for (Player p : plugin.getServer().getOnlinePlayers()) names.add(p.getName());
        return names;
    }

    public int getFood(Player player) {
        return player == null ? 0 : player.getFoodLevel();
    }

    public void setFood(Player player, int level) {
        if (player != null) player.setFoodLevel(Math.max(0, Math.min(20, level)));
    }

    public boolean addPotionEffect(Player player, String effectName, int seconds, int amplifier) {
        if (player == null) return false;
        PotionEffectType type = potionType(effectName);
        if (type == null) return false;
        player.addPotionEffect(new PotionEffect(type, Math.max(1, seconds) * 20, Math.max(0, amplifier)));
        return true;
    }

    public boolean removePotionEffect(Player player, String effectName) {
        if (player == null) return false;
        PotionEffectType type = potionType(effectName);
        if (type == null) return false;
        player.removePotionEffect(type);
        return true;
    }

    public boolean hasPotionEffect(Player player, String effectName) {
        if (player == null) return false;
        PotionEffectType type = potionType(effectName);
        return type != null && player.hasPotionEffect(type);
    }

    private PotionEffectType potionType(String effectName) {
        PotionEffectType type = PotionEffectType.getByName(effectName.toUpperCase());
        if (type == null) warn("Unknown potion effect: " + effectName);
        return type;
    }

    public long getWorldTime(String worldName) {
        World world = plugin.getServer().getWorld(worldName);
        return world == null ? -1 : world.getTime();
    }

    public boolean setWorldTime(String worldName, long time) {
        World world = plugin.getServer().getWorld(worldName);
        if (world == null) {
            warn("Unknown world: " + worldName);
            return false;
        }
        world.setTime(time);
        return true;
    }

    public boolean setWeather(String worldName, boolean storm) {
        World world = plugin.getServer().getWorld(worldName);
        if (world == null) {
            warn("Unknown world: " + worldName);
            return false;
        }
        world.setStorm(storm);
        return true;
    }

    public boolean isStorming(String worldName) {
        World world = plugin.getServer().getWorld(worldName);
        return world != null && world.hasStorm();
    }

    public boolean isNight(String worldName) {
        World world = plugin.getServer().getWorld(worldName);
        if (world == null) return false;
        long time = world.getTime() % 24000;
        return time >= 13000 && time <= 23000;
    }

    public int getPing(Player player) {
        if (player == null) return -1;
        try {
            return (int) player.getClass().getMethod("getPing").invoke(player);
        } catch (ReflectiveOperationException e) {
            return -1;
        }
    }

    public String getPlayerUUID(Player player) {
        return player == null ? null : player.getUniqueId().toString();
    }

    public void setDisplayName(Player player, String name) {
        if (player != null) player.setDisplayName(colorize(name));
    }

    public String getDisplayName(Player player) {
        return player == null ? null : player.getDisplayName();
    }

    public void clearInventory(Player player) {
        if (player != null) player.getInventory().clear();
    }

    @SuppressWarnings("deprecation")
    public String getItemInHand(Player player) {
        if (player == null) return null;
        ItemStack item = player.getInventory().getItemInMainHand();
        return item == null ? "AIR" : item.getType().name();
    }

    public boolean removeItem(Player player, String materialName, int amount) {
        if (player == null) return false;
        Material material = Material.matchMaterial(materialName);
        if (material == null) {
            warn("Unknown material: " + materialName);
            return false;
        }
        int left = Math.max(1, amount);
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length && left > 0; i++) {
            ItemStack stack = contents[i];
            if (stack == null || stack.getType() != material) continue;
            int take = Math.min(left, stack.getAmount());
            left -= take;
            if (stack.getAmount() - take <= 0) {
                contents[i] = null;
            } else {
                stack.setAmount(stack.getAmount() - take);
            }
        }
        if (left > 0) return false;
        player.getInventory().setContents(contents);
        return true;
    }

    public List<Player> getNearbyPlayers(Player player, double radius) {
        List<Player> result = new ArrayList<>();
        if (player == null) return result;
        for (Player other : player.getWorld().getPlayers()) {
            if (other != player && other.getLocation().distanceSquared(player.getLocation()) <= radius * radius) {
                result.add(other);
            }
        }
        return result;
    }

    public void strikeLightning(String worldName, double x, double y, double z) {
        strikeLightning(worldName, x, y, z, false);
    }

    public void strikeLightning(String worldName, double x, double y, double z, boolean visualOnly) {
        World world = plugin.getServer().getWorld(worldName);
        if (world == null) {
            warn("Unknown world: " + worldName);
            return;
        }
        Location loc = new Location(world, x, y, z);
        if (visualOnly) {
            world.strikeLightningEffect(loc);
        } else {
            world.strikeLightning(loc);
        }
    }

    // ---------------------------------------------------------------- custom events

    @SuppressWarnings("unchecked")
    public void on(String eventName, Object callback) {
        Object adapted = adapt(callback, Consumer.class);
        if (!(adapted instanceof Consumer)) {
            warn("脚本间事件回调不是可调用的函数: " + eventName);
            return;
        }
        Consumer<Object> listener = (Consumer<Object>) adapted;
        customEventBus.computeIfAbsent(eventName, k -> new CopyOnWriteArrayList<>()).add(listener);
        cleanupTasks.add(() -> {
            CopyOnWriteArrayList<Consumer<Object>> list = customEventBus.get(eventName);
            if (list != null) list.remove(listener);
        });
    }

    public void emit(String eventName, Object data) {
        CopyOnWriteArrayList<Consumer<Object>> list = customEventBus.get(eventName);
        if (list == null) return;
        for (Consumer<Object> c : list) {
            try {
                c.accept(data);
            } catch (Throwable t) {
                warn("Runtime error in custom event handler (" + eventName + "): "
                        + (t.getMessage() != null ? t.getMessage() : t));
                t.printStackTrace();
            }
        }
    }

    // ---------------------------------------------------------------- http

    public void fetch(String url, Object callback) {
        fetchInternal(url, "GET", null, null, callback);
    }

    public void fetch(String url, String method, Object callback) {
        fetchInternal(url, method, null, null, callback);
    }

    public void fetch(String url, String method, String body, Object callback) {
        fetchInternal(url, method, body, null, callback);
    }

    public void fetch(String url, String method, String body, Map<String, String> headers, Object callback) {
        fetchInternal(url, method, body, headers, callback);
    }

    @SuppressWarnings("unchecked")
    private void fetchInternal(String url, String method, String body, Map<String, String> headers, Object callback) {
        Object adapted = callback == null ? null : adapt(callback, Consumer.class);
        if (callback != null && !(adapted instanceof Consumer)) {
            warn("HTTP 回调不是可调用的函数，已忽略");
            return;
        }
        Consumer<HttpResult> listener = (Consumer<HttpResult>) adapted;
        BukkitTask task = plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            HttpResult result = httpExchange(url, method, body, headers);
            HttpResult finalResult = result;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                try {
                    listener.accept(finalResult);
                } catch (Throwable t) {
                    warn("Runtime error in fetch callback: " + (t.getMessage() != null ? t.getMessage() : t));
                    t.printStackTrace();
                }
            });
        });
        trackTask(task);
    }

    /**
     * 执行一次 HTTP 请求。用 {@link HttpURLConnection} 而不是 {@code java.net.http}，
     * 因为主插件要跑在 Java 8 上；用完立即 disconnect，避免连接池堆积。
     */
    private HttpResult httpExchange(String url, String method, String body, Map<String, String> headers) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(10_000);
            connection.setRequestMethod((method == null || method.isEmpty()) ? "GET" : method.toUpperCase());
            connection.setRequestProperty("Accept", "*/*");

            boolean hasContentType = false;
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    if (entry.getKey() == null || entry.getValue() == null) continue;
                    connection.setRequestProperty(entry.getKey(), entry.getValue());
                    if ("Content-Type".equalsIgnoreCase(entry.getKey())) hasContentType = true;
                }
            }
            if (body != null && !hasContentType) {
                connection.setRequestProperty("Content-Type", "application/json");
            }
            if (body != null) {
                byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(payload.length);
                OutputStream out = connection.getOutputStream();
                try {
                    out.write(payload);
                } finally {
                    out.close();
                }
            }

            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 400
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            String text = "";
            if (stream != null) {
                java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
                try {
                    byte[] chunk = new byte[4096];
                    int read;
                    while ((read = stream.read(chunk)) > 0) {
                        buffer.write(chunk, 0, read);
                    }
                } finally {
                    stream.close();
                }
                text = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
            }
            return new HttpResult(status, text);
        } catch (Exception e) {
            return new HttpResult(0, "Request failed: " + e.getMessage());
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    public void sendDiscordWebhook(String webhookUrl, String message) {
        sendDiscordWebhook(webhookUrl, message, null, null);
    }

    public void sendDiscordWebhook(String webhookUrl, String message, String username, String avatarUrl) {
        StringBuilder json = new StringBuilder("{\"content\":\"").append(jsonEscape(message)).append("\"");
        if (username != null) json.append(",\"username\":\"").append(jsonEscape(username)).append("\"");
        if (avatarUrl != null) json.append(",\"avatar_url\":\"").append(jsonEscape(avatarUrl)).append("\"");
        json.append("}");
        fetchInternal(webhookUrl, "POST", json.toString(), null, (Consumer<HttpResult>) result -> {
            if (!result.ok) {
                warn("Discord webhook failed (" + result.status + "): " + result.body);
            }
        });
    }

    public void sendDiscordEmbed(String webhookUrl, String title, String description, String colorHex) {
        int color = 0x3498db;
        if (colorHex != null) {
            try {
                color = Integer.parseInt(colorHex.replace("#", ""), 16);
            } catch (NumberFormatException ignored) {
            }
        }
        String json = "{\"embeds\":[{\"title\":\"" + jsonEscape(title)
                + "\",\"description\":\"" + jsonEscape(description)
                + "\",\"color\":" + color + "}]}";
        fetchInternal(webhookUrl, "POST", json, null, (Consumer<HttpResult>) result -> {
            if (!result.ok) {
                warn("Discord embed webhook failed (" + result.status + "): " + result.body);
            }
        });
    }

    private String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
    }

    // ---------------------------------------------------------------- files

    public boolean saveFile(String filename, String content) {
        File target = resolveFile(filename);
        if (target == null) return false;
        try {
            File parent = target.getParentFile();
            if (parent != null) parent.mkdirs();
            try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(target), StandardCharsets.UTF_8)) {
                w.write(content != null ? content : "");
            }
            return true;
        } catch (IOException e) {
            warn("Failed to save file \"" + filename + "\": " + e.getMessage());
            return false;
        }
    }

    public String readFile(String filename) {
        File target = resolveFile(filename);
        if (target == null || !target.isFile()) return null;
        try {
            return new String(java.nio.file.Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            warn("Failed to read file \"" + filename + "\": " + e.getMessage());
            return null;
        }
    }

    public boolean fileExists(String filename) {
        File target = resolveFile(filename);
        return target != null && target.isFile();
    }

    public boolean deleteFile(String filename) {
        File target = resolveFile(filename);
        return target != null && target.delete();
    }

    public List<String> listFiles() {
        List<String> result = new ArrayList<>();
        if (filesFolder.isDirectory()) {
            String[] names = filesFolder.list();
            if (names != null) {
                for (String n : names) result.add(n);
            }
        }
        return result;
    }

    private File resolveFile(String filename) {
        if (filename == null || filename.trim().isEmpty()) {
            warn("File name cannot be empty");
            return null;
        }
        int dot = filename.lastIndexOf('.');
        String ext = dot >= 0 ? filename.substring(dot + 1).toLowerCase() : "";
        if (BLOCKED_EXTENSIONS.contains(ext)) {
            warn("Blocked file extension \"." + ext + "\" - not allowed for security reasons: " + filename);
            return null;
        }
        try {
            if (!filesFolder.exists()) filesFolder.mkdirs();
            File target = new File(filesFolder, filename);
            String basePath = filesFolder.getCanonicalPath();
            String targetPath = target.getCanonicalPath();
            if (!targetPath.equals(basePath) && !targetPath.startsWith(basePath + File.separator)) {
                warn("Invalid file path (must stay inside your script's own file folder): " + filename);
                return null;
            }
            return target;
        } catch (IOException e) {
            warn("Invalid file path: " + filename);
            return null;
        }
    }

    // ---------------------------------------------------------------- waits / commands

    public void sleep(double seconds) {
        sleepMillis((long) (seconds * 1000));
    }

    public void waitTicks(int ticks) {
        sleepMillis(ticks * 50L);
    }

    private void sleepMillis(long millis) {
        if (Bukkit.isPrimaryThread()) {
            warn("Bird.sleep()/waitTicks() was called on the main thread - refusing, since it would freeze the whole server. "
                    + "Call it from inside Bird.runTaskAsync(function() { ... }) instead.");
            return;
        }
        try {
            Thread.sleep(Math.max(0, millis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void runCommand(String command) {
        String cmd = command.startsWith("/") ? command.substring(1) : command;
        plugin.getServer().getScheduler().runTask(plugin, () ->
                plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), cmd));
    }

    public void runCommandAs(Player player, String command) {
        if (player == null) return;
        String cmd = command.startsWith("/") ? command.substring(1) : command;
        plugin.getServer().getScheduler().runTask(plugin, () -> player.performCommand(cmd));
    }

    // ---------------------------------------------------------------- HuHoBot addon bridge

    /**
     * Registers this script as a HuHoBot addon. Called automatically when the
     * script finishes loading; scripts may call it again to override metadata.
     */
    public boolean registerAddon(String name, String version, String description, String author) {
        return plugin.registerAddon(name, version, description, author);
    }

    public boolean registerAddon(String name, String version, String description) {
        return registerAddon(name, version, description, scriptName);
    }

    public boolean registerAddon(String name) {
        return registerAddon(name, "1.0.0", "script addon", scriptName);
    }

    /**
     * Registers a QQ-side bot command owned by an addon. The command template
     * is a server command, same as the Java addon API.
     */
    public boolean registerBotCommand(String addonName, String key, String command, int permission, boolean pushMenu) {
        boolean registered = plugin.registerBotCommand(addonName, key, command, permission, pushMenu);
        if (registered && key != null) {
            botCommandKeys.add(key.trim());
        }
        return registered;
    }

    public boolean registerBotCommand(String addonName, String key, String command) {
        return registerBotCommand(addonName, key, command, 0, true);
    }

    public boolean unregisterBotCommand(String key) {
        if (key != null) botCommandKeys.remove(key.trim());
        return plugin.unregisterBotCommand(key);
    }

    /** Sends plain text to every configured QQ group. */
    public void sendBotText(String text) {
        plugin.sendBotText(text);
    }

    /** Sends markdown to every configured QQ group. */
    public void sendBotMarkdown(String markdown) {
        plugin.sendBotMarkdown(markdown);
    }

    public void unregisterAll() {
        for (Runnable r : cleanupTasks) {
            try {
                r.run();
            } catch (Exception e) {
                plugin.getLogger().warning("[" + scriptName + "] 清理时出错: " + e.getMessage());
            }
        }
        cleanupTasks.clear();
        for (String key : new ArrayList<>(botCommandKeys)) {
            try {
                plugin.unregisterBotCommand(key);
            } catch (Exception e) {
                plugin.getLogger().warning("[" + scriptName + "] 注销 QQ 群命令 " + key + " 时出错: " + e.getMessage());
            }
        }
        botCommandKeys.clear();
    }

    /**
     * 取 Bukkit 的 CommandMap。字段 {@code commandMap} 声明在 CraftServer 上，但
     * 服务端实现可能把它放在父类里，所以沿继承链往上找；找不到就明确告警一次，
     * 而不是让每个 onCommand 静默失效。
     */
    private static CommandMap getCommandMap() {
        if (commandMap != null) return commandMap;
        try {
            Class<?> type = Bukkit.getServer().getClass();
            while (type != null) {
                try {
                    Field f = type.getDeclaredField("commandMap");
                    f.setAccessible(true);
                    commandMap = (CommandMap) f.get(Bukkit.getServer());
                    return commandMap;
                } catch (NoSuchFieldException tryParent) {
                    type = type.getSuperclass();
                }
            }
            // getCommandMap 是静态的，拿不到实例 logger，这里退回 Bukkit 的。
            Bukkit.getLogger().severe("无法访问 CommandMap：当前服务端实现里找不到 commandMap 字段，脚本命令将无法注册");
        } catch (Exception e) {
            Bukkit.getLogger().severe("无法访问 CommandMap：" + e);
        }
        return null;
    }
}
