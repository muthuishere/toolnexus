package io.github.muthuishere.toolnexus.mcp;

import java.util.Map;

/** A JSON-RPC error: thrown by the client when the server answers with one, built by the server. */
public class RpcError extends McpException {
    public final int code;
    public final Object data;

    public RpcError(int code, String message) { this(code, message, null); }

    public RpcError(int code, String message, Object data) {
        super(message);
        this.code = code;
        this.data = data;
    }

    Map<String, Object> toJson() {
        Map<String, Object> m = Mcp.obj("code", code, "message", getMessage());
        if (data != null) m.put("data", data);
        return m;
    }

    static RpcError fromJson(Map<String, Object> m) {
        Object c = m.get("code");
        return new RpcError(c instanceof Number n ? n.intValue() : 0, Mcp.str(m.get("message")), m.get("data"));
    }
}
