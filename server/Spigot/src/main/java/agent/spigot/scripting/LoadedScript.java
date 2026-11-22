package agent.spigot.scripting;

import java.io.File;

/**
 * A loaded addon, its owning API and its closeable session (Rhino, LuaJ or Java).
 *
 * Adapted from BirdLibraryApi (https://github.com/prach1121/birdlibraryapi, Apache-2.0).
 */
public class LoadedScript {

    private final String name;
    private final File file;
    private final Object session;
    private final BirdScriptApi api;

    public LoadedScript(String name, File file, Object session, BirdScriptApi api) {
        this.name = name;
        this.file = file;
        this.session = session;
        this.api = api;
    }

    public String name() {
        return name;
    }

    public File file() {
        return file;
    }

    public Object session() {
        return session;
    }

    public BirdScriptApi api() {
        return api;
    }
}
