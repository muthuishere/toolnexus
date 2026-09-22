import io.github.muthuishere.toolnexus.BuiltinTools;
import io.github.muthuishere.toolnexus.Tool;
import io.github.muthuishere.toolnexus.ToolContext;
import io.github.muthuishere.toolnexus.ToolResult;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stress harness for the SHIPPED java builtins (spikes/builtin-host-boundary/stress/java).
 * Calls only the public API: BuiltinTools.create(cfg) -> List<Tool>, tool.execute(args, ctx).
 * Never reimplements builtin logic.
 */
public final class Harness {

    static Path tmp;
    static Tool bash;

    static Tool tool(List<Tool> tools, String name) {
        for (Tool t : tools) if (t.name().equals(name)) return t;
        throw new IllegalStateException("no tool " + name);
    }

    static ToolContext ctx() { return new ToolContext(); }

    static long count(Path dir, String prefix) throws IOException {
        if (!Files.isDirectory(dir)) return 0;
        try (var s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().startsWith(prefix)).count();
        }
    }

    // ---------------- S1 ----------------
    static String s1(String tag) throws Exception {
        long t0 = System.nanoTime();
        Path mk = Files.createDirectories(tmp.resolve("s1-" + tag));
        int n = 30;
        AtomicInteger timedOut = new AtomicInteger(), killedTree = new AtomicInteger(), errors = new AtomicInteger();
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                final int k = i;
                fs.add(ex.submit(() -> {
                    ToolResult r = bash.execute(new LinkedHashMap<>(Map.of(
                            "command", "sleep 0.2; sh -c 'sleep 5; touch " + mk.resolve("m" + k) + "'",
                            "timeout", 300)), ctx());
                    if (r.isError()) errors.incrementAndGet();
                    Map<String, Object> m = r.metadata();
                    if (m != null && Boolean.TRUE.equals(m.get("timedOut"))) timedOut.incrementAndGet();
                    if (m != null && Boolean.TRUE.equals(m.get("killedTree"))) killedTree.incrementAndGet();
                }));
            }
            for (Future<?> f : fs) f.get();
        }
        Thread.sleep(7000);
        long markers = count(mk, "m");

        // CONTROL: same command, 20s timeout, markers MUST appear
        Path ck = Files.createDirectories(tmp.resolve("s1c-" + tag));
        AtomicInteger cOk = new AtomicInteger();
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                final int k = i;
                fs.add(ex.submit(() -> {
                    ToolResult r = bash.execute(new LinkedHashMap<>(Map.of(
                            "command", "sleep 0.2; sh -c 'sleep 5; touch " + ck.resolve("m" + k) + "'",
                            "timeout", 20000)), ctx());
                    if (!r.isError()) cOk.incrementAndGet();
                }));
            }
            for (Future<?> f : fs) f.get();
        }
        long cMarkers = count(ck, "m");
        double wall = (System.nanoTime() - t0) / 1e9;
        boolean controlOk = cMarkers == 3 && cOk.get() == 3;
        String verdict = !controlOk ? "INVALID" : (markers == 0 && timedOut.get() == n && killedTree.get() == n && errors.get() == n ? "PASS" : "FAIL");
        return String.format("S1 verdict=%s markers=%d timedOut=%d/%d killedTree=%d/%d errorResults=%d/%d control_markers=%d/3 control_ok=%d/3 wall=%.1fs",
                verdict, markers, timedOut.get(), n, killedTree.get(), n, errors.get(), n, cMarkers, cOk.get(), wall);
    }

    // ---------------- S2 ----------------
    static String s2(String tag) throws Exception {
        long t0 = System.nanoTime();
        Path mk = Files.createDirectories(tmp.resolve("s2-" + tag));
        int pairs = 20;
        AtomicInteger okRight = new AtomicInteger(), okWrong = new AtomicInteger(),
                toErr = new AtomicInteger(), toWrong = new AtomicInteger();
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < pairs * 2; i++) {
                final int k = i;
                fs.add(ex.submit(() -> {
                    if (k % 2 == 0) { // success arm
                        ToolResult r = bash.execute(new LinkedHashMap<>(Map.of(
                                "command", "echo ok-" + k, "timeout", 20000)), ctx());
                        Map<String, Object> m = r.metadata();
                        boolean good = !r.isError()
                                && r.output().strip().equals("ok-" + k)
                                && m != null && Integer.valueOf(0).equals(m.get("exitCode"));
                        if (good) okRight.incrementAndGet(); else okWrong.incrementAndGet();
                    } else { // timeout arm
                        ToolResult r = bash.execute(new LinkedHashMap<>(Map.of(
                                "command", "sleep 0.2; sh -c 'sleep 5; touch " + mk.resolve("m" + k) + "'",
                                "timeout", 300)), ctx());
                        Map<String, Object> m = r.metadata();
                        boolean good = r.isError() && m != null && Boolean.TRUE.equals(m.get("timedOut"))
                                && !r.output().contains("ok-");
                        if (good) toErr.incrementAndGet(); else toWrong.incrementAndGet();
                    }
                }));
            }
            for (Future<?> f : fs) f.get();
        }
        Thread.sleep(7000);
        long markers = count(mk, "m");
        double wall = (System.nanoTime() - t0) / 1e9;
        String verdict = (okRight.get() == pairs && toErr.get() == pairs && markers == 0) ? "PASS" : "FAIL";
        return String.format("S2 verdict=%s successCorrect=%d/%d successWrong=%d timeoutCorrect=%d/%d timeoutWrong=%d crossedResults=%d orphanMarkers=%d control_successArm=%d/%d wall=%.1fs",
                verdict, okRight.get(), pairs, okWrong.get(), toErr.get(), pairs, toWrong.get(),
                okWrong.get() + toWrong.get(), markers, okRight.get(), pairs, wall);
    }

    // ---------------- S3 ----------------
    static String s3(String tag) throws Exception {
        long t0 = System.nanoTime();
        ToolResult r = bash.execute(new LinkedHashMap<>(Map.of(
                "command", "sh -c 'head -c 5000000 /dev/zero | tr \"\\0\" \"x\"; sleep 10'",
                "timeout", 1000)), ctx());
        double wall = (System.nanoTime() - t0) / 1e9;
        // CONTROL: same big output, no trailing sleep, generous timeout
        long c0 = System.nanoTime();
        ToolResult c = bash.execute(new LinkedHashMap<>(Map.of(
                "command", "sh -c 'head -c 5000000 /dev/zero | tr \"\\0\" \"x\"'",
                "timeout", 30000)), ctx());
        double cwall = (System.nanoTime() - c0) / 1e9;
        int cLen = c.output().length();
        boolean controlOk = !c.isError() && cLen == 5_000_000;
        boolean ok = r.isError() && wall < 5.0;
        String verdict = !controlOk ? "INVALID" : (ok ? "PASS" : "FAIL");
        return String.format("S3 verdict=%s isError=%s wall=%.2fs timedOut=%s outBytes=%d control_isError=%s control_bytes=%d control_wall=%.2fs",
                verdict, r.isError(), wall,
                r.metadata() != null && Boolean.TRUE.equals(r.metadata().get("timedOut")),
                r.output().length(), c.isError(), cLen, cwall);
    }

    // ---------------- S4 probes ----------------
    static int threads() { return ManagementFactory.getThreadMXBean().getThreadCount(); }
    static int children() { return (int) ProcessHandle.current().descendants().count(); }
    static int fds() {
        try (var s = Files.list(Path.of("/dev/fd"))) { return (int) s.count(); }
        catch (Exception e) { return -1; }
    }

    // ---------------- S5 ----------------
    static String s5(String tag) throws Exception {
        Path base = Files.createDirectories(tmp.resolve("s5-base-" + tag));
        Path outside = Files.createDirectories(tmp.resolve("s5-outside-" + tag));
        Files.createDirectories(base.resolve("sub"));
        Path link = base.resolve("link");
        if (!Files.exists(link, LinkOption.NOFOLLOW_LINKS)) Files.createSymbolicLink(link, outside);

        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("baseDir", base.toString());
        cfg.put("confineToBaseDir", true);
        List<Tool> tools = BuiltinTools.create(cfg);
        Tool write = tool(tools, "write");
        Tool read = tool(tools, "read");

        List<String> legal = List.of("a.txt", "sub/b.txt", "./c.txt");
        List<String> escapes = new ArrayList<>(List.of(
                "../x", "sub/../../x", outside.resolve("abs.txt").toString(),
                "link/via-symlink.txt", "....//x", "sub/../../../x",
                "a.txt\u0000/x"));

        AtomicInteger legalOk = new AtomicInteger(), legalRefused = new AtomicInteger(),
                escRefused = new AtomicInteger(), escAllowed = new AtomicInteger();
        List<String> notes = Collections.synchronizedList(new ArrayList<>());
        int total = 500;
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < total; i++) {
                final int k = i;
                fs.add(ex.submit(() -> {
                    if (k % 2 == 0) {
                        // unique per attempt: 250 threads rewriting the SAME 3 files is a
                        // harness race (truncate-while-read), not a confinement question.
                        String p = legal.get((k / 2) % legal.size()).replace(".txt", "-" + k + ".txt");
                        ToolResult w = write.execute(new LinkedHashMap<>(Map.of("path", p, "content", "x")), ctx());
                        ToolResult rd = read.execute(new LinkedHashMap<>(Map.of("path", p)), ctx());
                        if (!w.isError() && !rd.isError() && rd.output().equals("x")) legalOk.incrementAndGet();
                        else { legalRefused.incrementAndGet(); notes.add("legal-refused:" + p + ":" + w.output() + "|" + rd.output()); }
                    } else {
                        String p = escapes.get((k / 2) % escapes.size());
                        ToolResult w = write.execute(new LinkedHashMap<>(Map.of("path", p, "content", "ESCAPED")), ctx());
                        boolean landedInside = false;
                        if (!w.isError()) {
                            try { landedInside = base.resolve(p).toRealPath().startsWith(base.toRealPath()); }
                            catch (Exception e) { landedInside = false; }
                        }
                        // refused, OR allowed but demonstrably still inside the base
                        // ("....//x" is a directory literally named "...." -- a traversal
                        // lookalike, not a traversal).
                        if (w.isError() || landedInside) escRefused.incrementAndGet();
                        else { escAllowed.incrementAndGet(); notes.add("escape-allowed:" + p + ":" + w.output()); }
                    }
                }));
            }
            for (Future<?> f : fs) f.get();
        }
        // did anything actually land outside the base?
        long outsideFiles;
        try (var s = Files.walk(outside)) { outsideFiles = s.filter(Files::isRegularFile).count(); }
        long parentLeak = Files.exists(tmp.resolve("x")) ? 1 : 0;

        boolean controlOk = legalOk.get() > 0; // the harness actually ran legal paths
        String verdict = !controlOk ? "INVALID"
                : (escAllowed.get() == 0 && legalRefused.get() == 0 && outsideFiles == 0 && parentLeak == 0 ? "PASS" : "FAIL");
        String note = notes.isEmpty() ? "" : " firstNote=" + notes.get(0).replace(' ', '_');
        return String.format("S5[%s] verdict=%s attempts=%d legalAllowed=%d/%d legalRefused=%d escapesRefused=%d/%d escapesAllowed=%d filesOutsideBase=%d parentLeak=%d control_legalAllowed=%d%s",
                tag, verdict, total, legalOk.get(), total / 2, legalRefused.get(),
                escRefused.get(), total / 2, escAllowed.get(), outsideFiles, parentLeak, legalOk.get(), note);
    }

    public static void main(String[] args) throws Exception {
        tmp = Files.createTempDirectory("tn-stress-java-");
        boolean only5 = args.length > 0 && args[0].equals("s5");
        List<Tool> tools = BuiltinTools.create(null);
        bash = tool(tools, "bash");
        System.out.println("# harness class source: "
                + BuiltinTools.class.getProtectionDomain().getCodeSource().getLocation());
        System.out.println("# tmp=" + tmp);

        if (only5) {
            String a = s5("run1"), b = s5("run2");
            System.out.println(a); System.out.println(b);
            String x = a.split("verdict=")[1].split(" ")[0], y = b.split("verdict=")[1].split(" ")[0];
            System.out.println("S5 verdict=" + (x.equals(y) && x.equals("PASS") ? "PASS" : "FAIL")
                    + " deterministic=" + x.equals(y) + " run1=" + x + " run2=" + y);
            return;
        }
        int[] t = new int[4], c = new int[4], f = new int[4];
        t[0] = threads(); c[0] = children(); f[0] = fds();
        for (int round = 1; round <= 3; round++) {
            String a = s1("r" + round), b = s2("r" + round), d = s3("r" + round);
            if (round == 1) { System.out.println(a); System.out.println(b); System.out.println(d); }
            else { System.out.println("# round" + round + " " + a); System.out.println("# round" + round + " " + b); System.out.println("# round" + round + " " + d); }
            System.gc(); Thread.sleep(1500);
            t[round] = threads(); c[round] = children(); f[round] = fds();
        }
        boolean thrGrow = t[1] < t[2] && t[2] < t[3];
        boolean chGrow = c[1] < c[2] && c[2] < c[3];
        boolean fdGrow = f[1] < f[2] && f[2] < f[3];
        String v = (thrGrow || chGrow || fdGrow) ? "FAIL" : "PASS";
        System.out.println(String.format(
                "S4 verdict=%s threads=%s childProcs=%s fds=%s monotonicGrowth=threads:%s,children:%s,fds:%s control_roundsRun=3/3",
                v, Arrays.toString(t), Arrays.toString(c), Arrays.toString(f), thrGrow, chGrow, fdGrow));

        String r1 = s5("run1"), r2 = s5("run2");
        System.out.println(r1);
        System.out.println(r2);
        String v1 = r1.split("verdict=")[1].split(" ")[0], v2 = r2.split("verdict=")[1].split(" ")[0];
        System.out.println("S5 verdict=" + (v1.equals(v2) && v1.equals("PASS") ? "PASS" : "FAIL")
                + " deterministic=" + v1.equals(v2) + " run1=" + v1 + " run2=" + v2);
    }
}
