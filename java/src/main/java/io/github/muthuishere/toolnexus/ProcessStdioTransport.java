package io.github.muthuishere.toolnexus;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * §2.2a stdio probe handoff: the frozen SDK client over an ALREADY RUNNING child (the discover probe),
 * since the SDK's {@code StdioClientTransport} always spawns its own. Newline-delimited JSON-RPC on the
 * process's stdin/stdout, reading from the same stream object the probe used (nothing was read past
 * the discover reply). Close = close stdin, wait briefly, then destroy.
 */
final class ProcessStdioTransport implements McpClientTransport {
    private final Process proc;
    private final McpJsonMapper mapper;
    private final OutputStream stdin;
    private volatile boolean closing;
    private volatile Consumer<Throwable> onError = t -> {};

    ProcessStdioTransport(Process proc, McpJsonMapper mapper) {
        this.proc = proc;
        this.mapper = mapper;
        this.stdin = proc.getOutputStream();
    }

    @Override
    public Mono<Void> connect(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
        return Mono.fromRunnable(() -> Thread.ofVirtual().name("toolnexus-stdio-reuse").start(() -> {
            InputStream out = proc.getInputStream();
            try {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                for (int c; (c = out.read()) != -1; ) {
                    if (c != '\n') { b.write(c); continue; }
                    String line = b.toString(StandardCharsets.UTF_8);
                    b.reset();
                    if (line.isBlank()) continue;
                    McpSchema.JSONRPCMessage msg;
                    try {
                        msg = McpSchema.deserializeJsonRpcMessage(mapper, line);
                    } catch (IOException e) {
                        continue; // noise on stdout
                    }
                    handler.apply(Mono.just(msg)).subscribe(r -> {}, onError); // like the SDK: the session replies itself
                }
            } catch (IOException e) {
                if (!closing) onError.accept(e);
            }
        }));
    }

    @Override
    public void setExceptionHandler(Consumer<Throwable> handler) { if (handler != null) onError = handler; }

    @Override
    public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
        return Mono.fromRunnable(() -> {
            try {
                byte[] line = (mapper.writeValueAsString(message) + "\n").getBytes(StandardCharsets.UTF_8);
                synchronized (stdin) {
                    stdin.write(line);
                    stdin.flush();
                }
            } catch (IOException e) {
                throw new RuntimeException("stdio write: " + e.getMessage(), e);
            }
        });
    }

    @Override
    public Mono<Void> closeGracefully() {
        return Mono.fromRunnable(() -> {
            closing = true;
            try {
                stdin.close();
            } catch (IOException ignored) {
                // already closed
            }
            try {
                if (!proc.waitFor(2, TimeUnit.SECONDS)) {
                    proc.destroy();
                    if (!proc.waitFor(2, TimeUnit.SECONDS)) proc.destroyForcibly();
                }
            } catch (InterruptedException e) {
                proc.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        });
    }

    @Override
    public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) { return mapper.convertValue(data, typeRef); }
}
