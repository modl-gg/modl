package gg.modl.backend.staff.repository;

import static gg.modl.backend.database.jooq.Tables.TICKETS;
import static org.junit.jupiter.api.Assertions.assertThrows;

import gg.modl.backend.database.jooq.tables.records.TicketsRecord;
import gg.modl.backend.role.repository.JooqStaffRoleRepository;
import gg.modl.backend.role.repository.StaffRoleRepository;
import gg.modl.backend.staff.data.Staff;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.IntFunction;
import java.util.function.Supplier;

public record StaffPorts(StaffRepository staff, StaffRoleRepository roles, InvitationRepository invitations) {
    private static final long CALL_TIMEOUT_SECONDS = 60;
    private static final long BLOCKED_CHECK_MILLIS = 300;

    public static StaffPorts on(SqlTestDatabase database) {
        return new StaffPorts(
            new JooqStaffRepository(database.dsl(), database.unitOfWork(), database.insertIfAbsent(), database.jsonColumns(),
                database.dialect()),
            new JooqStaffRoleRepository(database.dsl(), database.unitOfWork(), database.insertIfAbsent(), database.dialect()),
            new JooqInvitationRepository(database.dsl(), database.unitOfWork()));
    }

    public static Staff member(String email, String username) {
        return member(null, email, username);
    }

    public static Staff member(String id, String email, String username) {
        return Staff.builder()
            .id(id)
            .email(email)
            .username(username)
            .roleId("helper")
            .createdAt(SqlTestDatabase.START)
            .updatedAt(SqlTestDatabase.START)
            .build();
    }

    public static void insertTicket(SqlTestDatabase database, String id) {
        Instant now = SqlTestDatabase.START;
        TicketsRecord ticket = new TicketsRecord();
        ticket.setId(id);
        ticket.setType("support");
        ticket.setStatus("open");
        ticket.setPublicAccessKey("key-" + id);
        ticket.setCreatedAt(now);
        ticket.setUpdatedAt(now);
        database.unitOfWork().write(() -> {
            database.dsl().insertInto(TICKETS).set(ticket).execute();
        });
    }

    public static <T> T whileUncommitted(SqlTestDatabase database, Runnable held, Supplier<T> contender) throws Exception {
        CountDownLatch changed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService threads = Executors.newFixedThreadPool(2)) {
            try {
                Future<?> holder = threads.submit(() -> database.unitOfWork().write(() -> {
                    held.run();
                    changed.countDown();
                    awaitLatch(release);
                }));
                awaitLatch(changed);
                Future<T> waiting = threads.submit(contender::get);
                assertThrows(TimeoutException.class, () -> waiting.get(BLOCKED_CHECK_MILLIS, TimeUnit.MILLISECONDS),
                    "The contender must wait for the uncommitted change");
                release.countDown();
                holder.get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                return waiting.get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
        }
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("The other unit did not get there in time");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the other unit", e);
        }
    }

    public static <T> List<T> simultaneously(int callers, IntFunction<T> call) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService threads = Executors.newFixedThreadPool(callers)) {
            List<Future<T>> pending = new ArrayList<>();
            for (int caller = 0; caller < callers; caller++) {
                int index = caller;
                pending.add(threads.submit(() -> {
                    start.await();
                    return call.apply(index);
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> result : pending) {
                results.add(result.get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }
            return results;
        }
    }
}
