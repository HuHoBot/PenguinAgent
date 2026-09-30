package cn.huohuas001.huhobotPenguin.spigot.scripting;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.jse.CoerceJavaToLua;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lua 侧的绑定层，把 {@code Bird} / {@code config} / {@code kv} 暴露成 Lua 对象。
 *
 * <p>直接 {@code CoerceJavaToLua.coerce(api)} 有两个问题：
 *
 * <ol>
 *   <li>LuaJ 把 Java 的 {@code List} 和 {@code Map} 一律变成 userdata。Lua 里对 userdata
 *       既不能取长度（{@code #}）、也不能下标、也不能 {@code pairs}，所以
 *       {@code #Bird:getDataKeys()} 会抛 {@code attempt to get length of userdata}。
 *   <li>重载由 LuaJ 自己挑，遇到它不认识的参数组合就直接失败。
 * </ol>
 *
 * <p>这里改成自己分发：{@code __index} 返回一个闭包，按「方法名 + 实参个数 + 类型吻合度」
 * 选重载；调用前把 LuaValue 转成 Java 参数，调用后把容器返回值转成真正的 {@link LuaTable}
 * （数组与 List → 1 起的序列，Map → 键值表）。JS 和 Python 不走这一层。
 *
 * <p>不要用它包 {@code Class}：静态方法会失效，静态入口（{@code Bukkit}）继续直接 coerce。
 */
final class LuaBridge {

    private LuaBridge() {
    }

    /** 包一个实例。找不到的成员名回退给 LuaJ 自己处理。 */
    static LuaValue wrap(final Object target) {
        final LuaValue fallback = CoerceJavaToLua.coerce(target);
        final Map<String, List<Method>> byName = methodsByName(target.getClass());
        final LuaTable wrapper = new LuaTable();
        final LuaTable meta = new LuaTable();
        meta.set("__index", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                LuaValue key = args.arg(2);
                final List<Method> candidates = key.isnil() ? null : byName.get(key.tojstring());
                if (candidates == null) {
                    return fallback.get(key);
                }
                final String name = candidates.get(0).getName();
                return new VarArgFunction() {
                    @Override
                    public Varargs invoke(Varargs call) {
                        // 冒号调用时 wrapper 自己会作为第一个实参传进来，subargs(2) 丢掉它
                        Varargs actual = call.subargs(2);
                        Method method = pick(candidates, actual);
                        if (method == null) {
                            throw new LuaError(name + " 没有匹配 " + actual.narg() + " 个参数的用法");
                        }
                        return toLua(callMethod(target, method, actual));
                    }
                };
            }
        });
        wrapper.setmetatable(meta);
        return wrapper;
    }

    private static Object callMethod(Object target, Method method, Varargs args) {
        Class<?>[] types = method.getParameterTypes();
        Object[] parsed = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            parsed[i] = toJava(args.arg(i + 1), types[i]);
            if (parsed[i] != null && !boxed(types[i]).isInstance(parsed[i])) {
                // 反射只会抛一句 argument type mismatch，脚本作者看不出是哪个参数错了
                throw new LuaError(method.getName() + " 第 " + (i + 1) + " 个参数要 "
                        + types[i].getSimpleName() + "，收到的是 " + describe(args.arg(i + 1)));
            }
        }
        try {
            return method.invoke(target, parsed);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            throw new LuaError(method.getName() + " 抛出异常: "
                    + (cause.getMessage() == null ? cause.toString() : cause.getMessage()));
        } catch (IllegalAccessException error) {
            throw new LuaError("无法调用 " + method.getName() + ": " + error);
        }
    }

    private static Class<?> boxed(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == double.class) return Double.class;
        if (type == float.class) return Float.class;
        if (type == boolean.class) return Boolean.class;
        if (type == void.class) return Void.class;
        return type;
    }

    private static String describe(LuaValue value) {
        if (value == null || value.isnil()) return "nil";
        if (value.isstring()) return "字符串 " + value.tojstring();
        if (value.istable()) return "table";
        if (value.isfunction()) return "function";
        if (value.isuserdata()) {
            Object raw = value.checkuserdata();
            return raw == null ? "userdata" : raw.getClass().getSimpleName();
        }
        return value.typename();
    }

    /** Java 容器 → 真正的 Lua table；其余交给 LuaJ。 */
    private static LuaValue toLua(Object value) {
        if (value == null) return LuaValue.NIL;
        if (value instanceof LuaValue) return (LuaValue) value;
        if (value.getClass().isArray()) {
            LuaTable table = new LuaTable();
            for (int i = 0, length = Array.getLength(value); i < length; i++) {
                table.set(i + 1, toLua(Array.get(value, i)));
            }
            return table;
        }
        if (value instanceof Collection) {
            LuaTable table = new LuaTable();
            int index = 1;
            for (Object element : (Collection<?>) value) {
                table.set(index++, toLua(element));
            }
            return table;
        }
        if (value instanceof Map) {
            LuaTable table = new LuaTable();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (entry.getKey() != null) {
                    table.set(entry.getKey().toString(), toLua(entry.getValue()));
                }
            }
            return table;
        }
        return CoerceJavaToLua.coerce(value);
    }

    /** Lua → Java 参数。只处理脚本能直接给出的类型，其余原样交给 LuaJ。 */
    private static Object toJava(LuaValue value, Class<?> type) {
        if (type == Object.class) return unwrap(value, Object.class);
        if (type == String.class || CharSequence.class.isAssignableFrom(type)) return value.tojstring();
        if (type == int.class || type == Integer.class) return value.toint();
        if (type == long.class || type == Long.class) return value.tolong();
        if (type == double.class || type == Double.class) return value.todouble();
        if (type == float.class || type == Float.class) return (float) value.todouble();
        if (type == boolean.class || type == Boolean.class) return value.toboolean();
        if (Map.class.isAssignableFrom(type) && value.istable()) return toMap(value.checktable());
        if (Collection.class.isAssignableFrom(type) && value.istable()) return toList(value.checktable());
        if (type.isArray() && value.istable()) return toArray(value.checktable(), type.getComponentType());
        // Player、CommandSender、Location 这类宿主对象是 Java 传进 Lua 的 userdata，
        // 回调时必须解包回原对象，直接把 LuaValue 塞进反射调用会 argument type mismatch。
        return unwrap(value, type);
    }

    /** 把 LuaJ 的 userdata 还原成 Java 对象；不是 userdata 或类型不符时回退原值。 */
    private static Object unwrap(LuaValue value, Class<?> type) {
        if (value == null || value.isnil()) return null;
        if (value.isuserdata()) {
            if (type == Object.class) {
                Object raw = value.checkuserdata();
                return raw != null ? raw : value;
            }
            if (value.isuserdata(type)) return value.checkuserdata(type);
        }
        return value;
    }

    private static Map<String, Object> toMap(LuaTable table) {
        Map<String, Object> map = new LinkedHashMap<>();
        LuaValue key = LuaValue.NIL;
        while (true) {
            Varargs next = table.next(key);
            key = next.arg1();
            if (key.isnil()) return map;
            map.put(key.tojstring(), plain(table.get(key)));
        }
    }

    private static List<Object> toList(LuaTable table) {
        List<Object> list = new ArrayList<>();
        for (int i = 1, length = table.length(); i <= length; i++) {
            list.add(plain(table.get(i)));
        }
        return list;
    }

    private static Object[] toArray(LuaTable table, Class<?> component) {
        Object array = Array.newInstance(component, table.length());
        for (int i = 1, length = table.length(); i <= length; i++) {
            Array.set(array, i - 1, plain(table.get(i)));
        }
        return (Object[]) array;
    }

    /** Lua 值转成能安全放进 Java 容器的类型。 */
    private static Object plain(LuaValue value) {
        if (value == null || value.isnil()) return null;
        if (value.isstring()) return value.tojstring();
        if (value.isboolean()) return value.toboolean();
        if (value.isnumber()) return value.isint() ? (Object) value.toint() : (Object) value.todouble();
        return value;
    }

    private static Map<String, List<Method>> methodsByName(Class<?> type) {
        Map<String, List<Method>> byName = new LinkedHashMap<>();
        for (Method method : type.getMethods()) {
            List<Method> same = byName.get(method.getName());
            if (same == null) {
                same = new ArrayList<>();
                byName.put(method.getName(), same);
            }
            same.add(method);
        }
        return byName;
    }

    /**
     * 按实参挑重载：个数必须一致、类型都要对得上；同样合法的多个重载里取参数类型
     * 最具体的那个（{@code tell(Player, String)} 优先于 {@code tell(CommandSender, String)}），
     * 不能靠反射返回顺序决定——那是不确定的。
     */
    private static Method pick(List<Method> candidates, Varargs args) {
        List<Method> usable = new ArrayList<>();
        for (Method method : candidates) {
            Class<?>[] types = method.getParameterTypes();
            if (types.length != args.narg()) continue;
            boolean fits = true;
            for (int i = 0; i < types.length; i++) {
                if (fit(args.arg(i + 1), types[i]) < 0) {
                    fits = false;
                    break;
                }
            }
            if (fits) usable.add(method);
        }
        usable.removeIf(candidate -> {
            for (Method other : usable) {
                if (other != candidate && moreDerived(candidate, other, args)) return true;
            }
            return false;
        });
        return usable.isEmpty() ? null : usable.get(0);
    }

    /** candidate 的每个 userdata 参数都比 other 的更具体，且不存在完全相同的情况。 */
    private static boolean moreDerived(Method candidate, Method other, Varargs args) {
        Class<?>[] mine = candidate.getParameterTypes();
        Class<?>[] theirs = other.getParameterTypes();
        if (mine.length != theirs.length) return false;
        boolean strictlyNarrower = false;
        for (int i = 0; i < mine.length; i++) {
            if (!args.arg(i + 1).isuserdata() || mine[i].equals(theirs[i])) continue;
            if (!theirs[i].isAssignableFrom(mine[i])) return false;
            if (!mine[i].isAssignableFrom(theirs[i])) strictlyNarrower = true;
        }
        return strictlyNarrower;
    }

    /** -1 表示类型对不上，数字越大越贴合。 */
    private static int fit(LuaValue value, Class<?> type) {
        if (type == Object.class) return value.isnil() ? 0 : 3;
        if (type == String.class) return value.isstring() ? 6 : -1;
        if (CharSequence.class.isAssignableFrom(type)) return value.isstring() ? 4 : -1;
        if (type == int.class || type == Integer.class) return value.isint() ? 6 : -1;
        if (type == long.class || type == Long.class) return value.isnumber() ? 5 : -1;
        if (type == double.class || type == Double.class) return value.isnumber() ? 5 : -1;
        if (type == float.class || type == Float.class) return value.isnumber() ? 4 : -1;
        if (type == boolean.class || type == Boolean.class) return value.isboolean() ? 6 : -1;
        if (Map.class.isAssignableFrom(type)) return value.istable() ? 5 : -1;
        if (Collection.class.isAssignableFrom(type)) return value.istable() ? 5 : -1;
        if (type.isArray()) return value.istable() ? 4 : -1;
        // 宿主对象（Player / CommandSender / Location …）以 userdata 形式进出
        if (value.isuserdata()) {
            Object raw = value.checkuserdata();
            return raw != null && type.isAssignableFrom(raw.getClass()) ? 6 : -1;
        }
        return value.isnil() ? 0 : 1;
    }
}
