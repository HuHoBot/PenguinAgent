package cn.huohuas001.huhobot.graalpy;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.SourceSection;
import org.graalvm.polyglot.Value;

import java.io.File;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * Creates the GraalPy engine from inside the engine jar's own classloader.
 *
 * <p><b>以下 public 方法全部由主插件反射调用，改签名必须同步改
 * {@code ScriptAddonLoader} 与 {@code BirdScriptApi}：</b>
 * {@code createEngine()}、{@code providesPython(Object)}、{@code warmUp(Object)}、
 * {@code open(Object)}、{@code adapt(Object, Class)}，以及 {@link Session} 的
 * {@code bind/eval/evalSource/close}。细节见
 * {@code docs/spigot-script-addons.md} 的「引擎桥 API」。
 *
 * GraalVM 24.1 discovers languages from the classloader of the class that calls
 * {@code Engine.newBuilder}, and that API has no classloader parameter. The main
 * plugin jar no longer contains the polyglot runtime, so calling it there never
 * sees Python even when this jar is visible as a child loader. Running the call
 * here registers it.
 *
 * The main plugin reaches this class by reflection only, so it compiles and runs
 * without GraalPy on its classpath. {@link Failure} carries the source line of a
 * script error back across that boundary, because {@link PolyglotException} itself
 * is not on the main plugin's classpath.
 */
public final class GraalPyBridge {

    private GraalPyBridge() {
    }

    /** A live GraalPy context. The main plugin only needs bindings / eval / close. */
    public interface Session {
        void bind(String name, Object value);

        void eval(File file) throws Failure;

        /** 在当前上下文里求一段 Python 源码，依赖预检用。失败时抛 {@link Failure}。 */
        void evalSource(String source) throws Failure;

        /** 只关这个上下文。Engine 由调用方共享，Session 不碰。 */
        void close();
    }

    /** A script failure, with the source line when GraalPy reported one. */
    public static final class Failure extends Exception {
        private final int line;

        public Failure(String message, int line, Throwable cause) {
            super(message, cause);
            this.line = line;
        }

        /** Source line, or {@code -1} when the failure has no source location. */
        public int line() {
            return line;
        }
    }

    public static Engine createEngine() {
        return Engine.newBuilder("python")
                .allowExperimentalOptions(true)
                .option("engine.WarnInterpreterOnly", "false")
                .build();
    }

    /** True when {@code engine} was built by {@link #createEngine()} and speaks Python. */
    public static boolean providesPython(Object engine) {
        return engine instanceof Engine && ((Engine) engine).getLanguages().containsKey("python");
    }

    /**
     * 建一个上下文再立刻关掉，只为把 GraalPy 的 home 解压这一步提前做完。
     * 首次解压约 1250 个文件，不提前做的话第一个 .py 脚本会在解压完成前就去 import
     * 标准库，然后报 core path 探测失败。整个过程失败不致命，返回 false 即可。
     */
    public static boolean warmUp(Object engine) {
        try {
            open(engine).close();
            return true;
        } catch (Throwable error) {
            return false;
        }
    }

    /**
     * 函数保留为原始 {@link Value}：宿主侧的回调参数声明成 {@code Object}，GraalPy 没有
     * 具体接口可以按目标类型转换，直接交出来的是 {@code Value}。宿主拿到 {@code Value}
     * 之后调 {@link #adapt(Object, Class)} 把它桥成目标函数式接口。
     *
     * <p>必须是同一个实例：Engine 是所有 .py 脚本共享的，而 GraalVM 要求共享 Engine 的
     * 所有 Context host access 配置完全一致，每次 new 一个会被判为「配置不同」而拒绝。
     */
    private static final HostAccess HOST_ACCESS = HostAccess.newBuilder(HostAccess.ALL)
            .targetTypeMapping(Value.class, Object.class, Value::canExecute, value -> value, HostAccess.TargetMappingPrecedence.HIGHEST)
            .build();

    private static HostAccess hostAccess() {
        return HOST_ACCESS;
    }

    /** 把 Python 函数适配成宿主声明的函数式接口 {@code type}。必须在引擎 classloader 里做。 */
    public static Object adapt(Object function, Class<?> type) {
        if (!(function instanceof Value) || !((Value) function).canExecute() || type == null || !type.isInterface()) {
            return function;
        }
        Value value = (Value) function;
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                switch (method.getName()) {
                    case "toString": return "python-function";
                    case "hashCode": return System.identityHashCode(proxy);
                    case "equals": return proxy == args[0];
                    default: return null;
                }
            }
            Value result = value.execute(args == null ? new Object[0] : args);
            return convert(result, method.getGenericReturnType());
        });
    }

    /** 把 Python 返回值转成宿主方法声明的类型。 */
    private static Object convert(Value result, java.lang.reflect.Type type) {
        if (type == void.class || type == Void.class || result == null || result.isNull()) return null;
        if (type instanceof java.lang.reflect.ParameterizedType) {
            java.lang.reflect.ParameterizedType parameterized = (java.lang.reflect.ParameterizedType) type;
            if (parameterized.getRawType() == List.class) {
                return asList(result);
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

    /** Python 序列 → Java List。空结果和标量都退化成安全的值，不抛异常。 */
    private static List<Object> asList(Value result) {
        List<Object> list = new ArrayList<>();
        if (result == null || !result.hasArrayElements()) {
            if (result != null && !result.isNull()) {
                list.add(result.isString() ? result.asString() : result.toString());
            }
            return list;
        }
        for (Value element : result.as(Value[].class)) {
            list.add(element == null || element.isNull() ? null
                    : element.isString() ? element.asString() : element.toString());
        }
        return list;
    }

    private static Object convertAs(Value result, Class<?> raw) {
        try {
            return result.as(raw);
        } catch (ClassCastException | IllegalArgumentException unsupported) {
            return result.isString() ? result.asString() : result.toString();
        }
    }

    /** 在调用方提供的共享 Engine 上开一个上下文；Session 关闭时不会关掉这个 Engine。 */
    public static Session open(Object engine) {
        if (!(engine instanceof Engine)) {
            throw new IllegalArgumentException("不是 GraalPy 引擎: " + engine);
        }
        // engine.WarnInterpreterOnly 是引擎级选项，共享 Engine 的 Context 上再设会被拒绝。
        // ownedEngine 传 null：这个 Engine 是调用方的，Session 关掉时不能连带关掉。
        return new PySession(context((Engine) engine));
    }

    private static Context context(Engine engine) {
        return Context.newBuilder("python")
                .engine(engine)
                .allowAllAccess(true)
                .allowHostAccess(hostAccess())
                .allowHostClassLookup(className -> true)
                .build();
    }

    public static final class PySession implements Session {
        private final Context context;

        PySession(Context context) {
            this.context = context;
        }

        @Override
        public void bind(String name, Object value) {
            context.getBindings("python").putMember(name, value);
        }

        @Override
        public void eval(File file) throws Failure {
            try {
                context.eval(Source.newBuilder("python", file).build());
            } catch (PolyglotException error) {
                int line = -1;
                SourceSection section = error.getSourceLocation();
                if (section != null && section.isAvailable()) line = section.getStartLine();
                throw new Failure(error.getMessage(), line, error);
            } catch (Exception error) {
                throw new Failure(error.getMessage(), -1, error);
            }
        }

        @Override
        public void evalSource(String source) throws Failure {
            try {
                context.eval("python", source);
            } catch (PolyglotException error) {
                throw new Failure(error.getMessage(), -1, error);
            }
        }

        @Override
        public void close() {
            context.close(true);
        }
    }
}
