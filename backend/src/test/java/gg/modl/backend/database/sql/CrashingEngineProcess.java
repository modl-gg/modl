package gg.modl.backend.database.sql;

import gg.modl.backend.support.sql.SqlTestDatabase;
import gg.modl.backend.support.sql.TestDialects;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Duration;

public final class CrashingEngineProcess {
    private static final String ACKNOWLEDGED = "crash workload acknowledged";
    private static final int PARENT_GONE_EXIT_CODE = 3;

    private CrashingEngineProcess() {
    }

    public static void main(String[] args) throws InterruptedException {
        haltWhenParentIsGone();
        CrashWorkload workload = CrashWorkload.valueOf(args[1]);
        SqlTestDatabase database = SqlTestDatabase.start(TestDialects.SQLITE, Path.of(args[0]));
        workload.run(new CrashWorkload.Writes(database), args[2], CrashingEngineProcess::print);
        print(ACKNOWLEDGED);
        Thread.sleep(Long.MAX_VALUE);
    }

    static void killRightAfterTheAcknowledgement(Path directory, CrashWorkload workload, String marker) throws InterruptedException {
        killAfter(directory, workload, marker, ACKNOWLEDGED, Duration.ZERO);
    }

    static String killAfter(Path directory, CrashWorkload workload, String marker, String awaitedLine, Duration killDelay)
        throws InterruptedException {
        try (ChildJvm process = ChildJvm.start(CrashingEngineProcess.class, directory.toString(), workload.name(), marker)) {
            String line = process.awaitLineStartingWith(awaitedLine);
            if (killDelay.isPositive()) {
                Thread.sleep(killDelay);
            }
            process.kill();
            return line.substring(awaitedLine.length());
        }
    }

    public static void haltWhenParentIsGone() {
        Thread.ofPlatform().daemon().start(() -> {
            try {
                System.in.transferTo(OutputStream.nullOutputStream());
            } catch (IOException e) {
                System.err.println("The parent process is gone: " + e.getMessage());
            }
            Runtime.getRuntime().halt(PARENT_GONE_EXIT_CODE);
        });
    }

    private static void print(String line) {
        System.out.println(line);
        System.out.flush();
    }
}
