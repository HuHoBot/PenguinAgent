package agentcheck;

import java.util.function.Function;

/** Must run with the packaged artifact and this test class only, never the unshaded main classpath. */
public final class ShadedEngineSmoke {
    public static void main(String[] args)throws Exception{
        Class<?> type=Class.forName("agent.spigot.scripting.RhinoBridge");
        Object js=type.getDeclaredConstructor().newInstance();
        try{
            Object value=type.getMethod("eval",String.class).invoke(js,"(function(x){return 'packaged '+x;})");
            Function callback=(Function)type.getMethod("adapt",Object.class,Class.class).invoke(null,value,Function.class);
            Object answer=java.util.concurrent.CompletableFuture.supplyAsync(()->callback.apply("OK")).get();
            if(!"packaged OK".equals(answer.toString()))throw new AssertionError(answer);
            System.out.println("PACKAGED_JAVASCRIPT_CALLBACK_OK");
        }finally{type.getMethod("close").invoke(js);}
    }
}
