package agent.spigot.scripting;

/** Native Java addon entry point; Main.java is compiled with the running server's JDK. */
public interface AddonProgram {
    void start(BirdScriptApi api) throws Exception;
    default void stop() throws Exception {}
}
