package cn.huohuas001.huhobotPenguin.spigot.scripting;

/**
 * Outcome of loading one addon directory. Adapted from BirdLibraryApi
 * (https://github.com/prach1121/birdlibraryapi, Apache-2.0).
 *
 * <p>{@code skipped} is deliberately distinct from {@code failed}: an addon that
 * declares {@code _enabled: false} in its manifest is intentionally not loaded,
 * so it must not be reported to the operator as a failure.
 */
public class ScriptLoadResult {

    private final String name;
    private final boolean success;
    private final boolean skipped;
    private final String message;
    private final int line;

    private ScriptLoadResult(String name, boolean success, boolean skipped, String message, int line) {
        this.name = name;
        this.success = success;
        this.skipped = skipped;
        this.message = message;
        this.line = line;
    }

    public static ScriptLoadResult ok(String name) {
        return new ScriptLoadResult(name, true, false, null, -1);
    }

    /** Intentionally not loaded, e.g. {@code _enabled: false}. Not an error. */
    public static ScriptLoadResult skipped(String name, String reason) {
        return new ScriptLoadResult(name, false, true, reason, -1);
    }

    public static ScriptLoadResult error(String name, String message) {
        return new ScriptLoadResult(name, false, false, message, -1);
    }

    public static ScriptLoadResult error(String name, String message, int line) {
        return new ScriptLoadResult(name, false, false, message, line);
    }

    public static ScriptLoadResult notFound(String name) {
        return new ScriptLoadResult(name, false, false, "找不到该脚本插件目录", -1);
    }

    public String name() {
        return name;
    }

    public boolean success() {
        return success;
    }

    public boolean skipped() {
        return skipped;
    }

    public String message() {
        return message;
    }

    public String formatted() {
        if (success) {
            return "§a✔ " + name;
        }
        if (skipped) {
            return "§e⏭ " + name + " §7- §e" + message;
        }
        StringBuilder sb = new StringBuilder("§c✘ " + name + " §7- §c" + message);
        if (line >= 0) {
            sb.append(" §7(line ").append(line).append(")");
        }
        return sb.toString();
    }
}
