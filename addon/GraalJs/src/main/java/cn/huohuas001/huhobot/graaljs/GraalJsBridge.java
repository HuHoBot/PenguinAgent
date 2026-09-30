package cn.huohuas001.huhobot.graaljs;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

import java.io.File;
import java.lang.reflect.Proxy;
import java.util.List;

/**
 * GraalJS entry point that lives inside the engine jar's own classloader.
 *
 * <b>以下 public 方法全部由主插件反射调用，改签名必须同步改
 * {@code ScriptAddonLoader} 与 {@code BirdScriptApi}：{@code open()}、{@code adapt(Object, Class)}，
 * 以及 {@link Session} 的 {@code bind/eval/close}。细节见
 * {@code docs/spigot-script-addons.md} 的「引擎桥 API」。
 *
 * GraalVM 24.1 discovers languages from the classloader that is current when the
 * polyglot context is built, and that API has no classloader parameter. The main
 * plugin jar no longer contains GraalJS, so building the context there never sees
 * the {@code js} language even when this jar is visible as a child loader. Running
 * it here registers the language.
 *
 * The main plugin reaches this class by reflection, so it compiles and runs
 * without GraalJS on its classpath. {@link #open()} builds the context and
 * {@link #adapt(Object, Class)} bridges script functions back into the host's
 * functional interfaces.
 */
public final class GraalJsBridge {

    private GraalJsBridge() {
    }

    /** A live GraalJS context. The caller only needs eval / bindings / close. */
    public interface Session {
        void bind(String name, Object value);

        void eval(File file) throws Exception;

        void close();
    }

    public static Session open() {
        Context context = Context.newBuilder("js")
                .allowAllAccess(true)
                .allowHostAccess(hostAccess())
                .allowHostClassLookup(className -> true)
                .allowExperimentalOptions(true)
                .option("js.nashorn-compat", "true")
                .option("engine.WarnInterpreterOnly", "false")
                .build();
        return new JsSession(context);
    }

    /**
     * {@link HostAccess#ALL} maps every JavaScript function onto
     * {@code java.util.function.Function}, so a callback declared with a different
     * shape arrives as the wrong type. Keeping the raw function lets the host adapt
     * it to the exact interface its method declared.
     *
     * <p>Single instance on purpose: GraalVM rejects contexts that share an engine but
     * differ in host access configuration, and a fresh instance each time counts as
     * a different configuration.
     */
    private static final HostAccess HOST_ACCESS = HostAccess.newBuilder(HostAccess.ALL)
            .targetTypeMapping(Value.class, Object.class, Value::canExecute, value -> value, HostAccess.TargetMappingPrecedence.HIGHEST)
            .build();

    private static HostAccess hostAccess() {
        return HOST_ACCESS;
    }

    /** Adapts a JavaScript function to the functional interface {@code type}. */
    public static Object adapt(Object function, Class<?> type) {
        if (!(function instanceof Value) || !((Value) function).canExecute() || type == null || !type.isInterface()) {
            return function;
        }
        Value value = (Value) function;
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                switch (method.getName()) {
                    case "toString": return "js-function";
                    case "hashCode": return System.identityHashCode(proxy);
                    case "equals": return proxy == args[0];
                    default: return null;
                }
            }
            Value result = value.execute(args == null ? new Object[0] : args);
            return convert(result, method.getGenericReturnType());
        });
    }

    /** Converts a JavaScript return value into the type the host method declared. */
    private static Object convert(Value result, java.lang.reflect.Type type) {
        if (type == void.class || type == Void.class || result == null || result.isNull()) return null;
        if (type instanceof java.lang.reflect.ParameterizedType) {
            java.lang.reflect.ParameterizedType parameterized = (java.lang.reflect.ParameterizedType) type;
            if (parameterized.getRawType() == List.class) {
                return result.as(List.class);
            }
            if (parameterized.getRawType() instanceof Class) {
                return convertAs(result, (Class<?>) parameterized.getRawType());
            }
        }
        if (type instanceof Class) {
            return convertAs(result, (Class<?>) type);
        }
        return result.isString() ? result.asString() : result.toString();
    }

    private static Object convertAs(Value result, Class<?> raw) {
        try {
            return result.as(raw);
        } catch (ClassCastException | IllegalArgumentException unsupported) {
            return result.isString() ? result.asString() : result.toString();
        }
    }

    public static final class JsSession implements Session {
        private final Context context;

        JsSession(Context context) {
            this.context = context;
        }

        @Override
        public void bind(String name, Object value) {
            context.getBindings("js").putMember(name, value);
        }

        @Override
        public void eval(File file) throws Exception {
            context.eval(Source.newBuilder("js", file).build());
        }

        @Override
        public void close() {
            context.close(true);
        }
    }
}
