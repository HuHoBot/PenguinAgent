package agent.spigot.scripting;

import org.mozilla.javascript.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** Small isolated trusted-addon context. Host access is intentional; this is not a security sandbox. */
public final class RhinoBridge implements AutoCloseable {
    private static final Map<Scriptable,java.lang.ref.WeakReference<RhinoBridge>> OWNERS = Collections.synchronizedMap(new WeakHashMap<>());
    private final ReentrantLock lock = new ReentrantLock(true);
    private final ThreadLocal<Long> deadline = new ThreadLocal<>();
    private volatile boolean closed;
    private Scriptable scope;
    private Script prepared;
    private final ContextFactory factory = new ContextFactory() {
        @Override protected Context makeContext() {
            Context cx=super.makeContext();cx.setLanguageVersion(Context.VERSION_ES6);cx.setOptimizationLevel(-1);cx.setInstructionObserverThreshold(10000);return cx;
        }
        @Override protected void observeInstructionCount(Context cx,int instructions) {
            Long limit=deadline.get();
            if(closed||Thread.currentThread().isInterrupted()||limit!=null&&System.nanoTime()>limit)
                throw new ScriptLimit("JavaScript execution cancelled or exceeded its CPU time limit");
        }
    };
    private static final class ScriptLimit extends Error { ScriptLimit(String message){super(message);} }
    private static boolean tickThread(){try{return agent.spigot.manager.FoliaScheduler.isTickThread();}catch(Throwable absent){return false;}}
    private <T>T call(ContextAction<T> action) {
        if(closed)throw new IllegalStateException("JavaScript addon is unloaded");
        boolean acquired=false;Long previous=deadline.get();
        try {
            if(!lock.tryLock(tickThread()?1:5000,TimeUnit.MILLISECONDS))throw new IllegalStateException("JavaScript context is busy; callback was not run on a second thread");
            acquired=true;if(closed)throw new IllegalStateException("JavaScript addon is unloaded");
            if(previous==null)deadline.set(System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(tickThread()?100:5000));
            return factory.call(action);
        } catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("JavaScript interrupted",interrupted);}
        finally{if(previous==null)deadline.remove();else deadline.set(previous);if(acquired)lock.unlock();}
    }
    public RhinoBridge() {
        scope=call(cx->new ImporterTopLevel(cx));OWNERS.put(scope,new java.lang.ref.WeakReference<>(this));
    }
    public void bind(String name,Object value){call(cx->{ScriptableObject.putProperty(scope,name,Context.javaToJS(value,scope));return null;});}
    private static String source(File file)throws IOException {
        if(file.length()>1024*1024)throw new IOException("JavaScript source exceeds 1 MiB");
        return new String(java.nio.file.Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8);
    }
    public void prepare(File file)throws IOException {String code=source(file);call(cx->{prepared=cx.compileString(code,file.getName(),1,null);return null;});}
    public void runPrepared(){call(cx->{if(prepared==null)throw new IllegalStateException("JavaScript not prepared");return prepared.exec(cx,scope);});}
    public void eval(File file)throws IOException {String code=source(file);call(cx->cx.evaluateString(scope,code,file.getName(),1,null));}
    public Object eval(String code){return call(cx->cx.evaluateString(scope,code,"<addon>",1,null));}
    @Override public void close(){closed=true;OWNERS.remove(scope);prepared=null;}
    public static boolean supports(Object value){return value instanceof Scriptable;}
    public static Object adapt(Object value,Class<?> type) {
        if(type.isInstance(value)||!(value instanceof Callable)||!type.isInterface())return value;
        Scriptable function=(Scriptable)value;
        Scriptable root=ScriptableObject.getTopLevelScope(function);
        java.lang.ref.WeakReference<RhinoBridge> reference=OWNERS.get(root);
        RhinoBridge owner=reference==null?null:reference.get();
        if(owner==null)throw new IllegalStateException("JavaScript callback owner is not active");
        return Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(proxy,method,args)->{
            if(method.getDeclaringClass()==Object.class){
                if(method.getName().equals("toString"))return "JavaScriptCallback";
                if(method.getName().equals("hashCode"))return System.identityHashCode(proxy);
                if(method.getName().equals("equals"))return args!=null&&proxy==args[0];
            }
            if(method.isDefault())return InvocationHandler.invokeDefault(proxy,method,args==null?new Object[0]:args);
            return owner.call(cx->{
                Object[] incoming=args==null?new Object[0]:args,converted=new Object[incoming.length];
                for(int i=0;i<incoming.length;i++)converted[i]=Context.javaToJS(incoming[i],root);
                Object result=((Callable)value).call(cx,root,root,converted);
                if(method.getReturnType()==void.class)return null;
                if(result instanceof NativeArray && List.class.isAssignableFrom(method.getReturnType()))return new ArrayList<>((NativeArray)result);
                return Context.jsToJava(result,method.getReturnType());
            });
        });
    }
}
