package cn.huohuas001.huhobotPenguin.spigot.scripting;

import java.io.File;

/**
 * A loaded script. {@code session} holds the language runtime that ran it:
 * the GraalJS session (loaded from the engines jar) for {@code .js} files, a
 * {@link org.luaj.vm2.Globals} for {@code .lua} files, or the GraalPy session
 * (also loaded from the engines jar) for {@code .py} files.
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
