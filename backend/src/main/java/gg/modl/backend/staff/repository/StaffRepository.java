package gg.modl.backend.staff.repository;

import gg.modl.backend.staff.data.Staff;
import org.jspecify.annotations.Nullable;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface StaffRepository {
    StaffInsertOutcome insert(Staff staff);

    boolean updateRole(String staffId, String roleId, Instant updatedAt);

    boolean updateProfile(String staffId, @Nullable String username, @Nullable String language, @Nullable String dateFormat,
                          Instant updatedAt);

    boolean updateEmail(String staffId, String email, Instant updatedAt);

    boolean updateMinecraftAssignment(String staffId, @Nullable String uuid, @Nullable String username, Instant updatedAt);

    boolean updateTicketSubscriptionSettings(String staffId, Staff.TicketSubscriptionSettings settings, Instant updatedAt);

    void clearTwoFactor(String staffId);

    boolean deleteById(String staffId);

    long countAll();

    List<Staff> findAll();

    Optional<Staff> findById(String staffId);

    Optional<Staff> findByIdForUpdate(String staffId);

    Optional<Staff> findByEmail(String email);

    Optional<Staff> findByUsername(String username);

    List<Staff> findByUsernames(Collection<String> usernames);

    boolean existsByEmail(String email);

    boolean existsByEmailExcluding(String email, String currentEmail);

    boolean existsByUsername(String username);

    boolean existsByUsernameExcludingId(String username, String excludedStaffId);

    boolean existsByEmailOrUsername(String email, String username);

    Map<String, String> findUsernamesByIds(Collection<String> staffIds);

    Optional<String> findUsernameByEmail(String email);

    List<Staff> findAssignedMinecraftStaff();

    List<String> findAssignedMinecraftUuids();

    Optional<Staff> findByAssignedMinecraftUuid(String uuid);

    Optional<Staff> findByAssignedMinecraftUuidExcludingId(String uuid, @Nullable String excludedStaffId);

    boolean updateLastSeenByAssignedMinecraftUuid(String uuid, Instant seenAt);

    int countByRoleId(String roleId);

    Map<String, Integer> countByRoleId();

    boolean createTwoFactorToken(String uuid, String tokenHash, @Nullable String ip, Instant createdAt);

    Optional<Staff> findByTwoFactorTokenHash(String tokenHash);

    boolean activateTwoFactorSession(String staffId, String tokenHash, @Nullable String sessionIp, Instant sessionExpiresAt);

    List<Staff> findWithPendingTwoFactorDelivery();

    int clearPendingTwoFactorDelivery(Collection<String> staffIds);

    void subscribe(String staffEmail, String ticketId, Instant now);

    boolean unsubscribe(String staffEmail, String ticketId);

    boolean markSubscriptionRead(String staffEmail, String ticketId, Instant readAt);

    List<TicketSubscription> findSubscriptions(String staffEmail);
}
