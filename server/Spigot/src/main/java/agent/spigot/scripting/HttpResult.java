package agent.spigot.scripting;

public class HttpResult {

    public final int status;

    public final String body;

    public final boolean ok;

    public HttpResult(int status, String body) {
        this.status = status;
        this.body = body;
        this.ok = status >= 200 && status < 300;
    }

    @Override
    public String toString() {
        return "HttpResult{status=" + status + ", ok=" + ok + ", body=" + body + "}";
    }
}
