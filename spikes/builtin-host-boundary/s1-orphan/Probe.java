// S1 probe (java). naive = what BuiltinTools.java:237 does today
// (ProcessBuilder + waitFor(timeout) + destroyForcibly on the direct child).
// tree = Process.descendants() -> destroy, grace, destroyForcibly.
import java.io.File;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class Probe {
    public static void main(String[] a) throws Exception {
        String mode = a[0], marker = a[1];
        ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", "sleep 0.2; sh -c 'sleep 1; touch " + marker + "'");
        pb.redirectErrorStream(true);
        pb.redirectOutput(new File("/dev/null"));
        Process p = pb.start();
        if (!p.waitFor(300, TimeUnit.MILLISECONDS)) {
            if (mode.equals("naive")) {
                p.destroyForcibly();
            } else {
                List<ProcessHandle> kids = p.descendants().toList();
                System.out.println("DESCENDANTS=" + kids.size());
                kids.forEach(ProcessHandle::destroy);
                p.destroy();
                if (!p.waitFor(200, TimeUnit.MILLISECONDS)) {
                    kids.forEach(ProcessHandle::destroyForcibly);
                    p.destroyForcibly();
                }
            }
            p.waitFor();
        }
        System.out.println("killed");
    }
}
