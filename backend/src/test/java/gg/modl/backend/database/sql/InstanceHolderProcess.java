package gg.modl.backend.database.sql;

import gg.modl.backend.support.sql.SqlTestDatabase;
import gg.modl.backend.support.sql.TestDialects;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;

public final class InstanceHolderProcess {
    static final String READY = "instance ready";
    static final String FAILED = "instance failed: ";

    private static final int FAILURE_EXIT_CODE = 1;

    private InstanceHolderProcess() {
    }

    public static void main(String[] args) throws IOException {
        SqlTestDatabase database;
        try {
            database = SqlTestDatabase.start(TestDialects.SQLITE, Path.of(args[0]));
        } catch (RuntimeException e) {
            System.out.println(FAILED + CauseChain.messages(e));
            System.out.flush();
            Runtime.getRuntime().halt(FAILURE_EXIT_CODE);
            return;
        }
        System.out.println(READY);
        System.out.flush();
        System.in.transferTo(OutputStream.nullOutputStream());
        database.close();
    }
}
