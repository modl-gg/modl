package gg.modl.backend.hostedimport.mapping;

import static gg.modl.backend.database.jooq.Tables.STAFF;

import gg.modl.backend.email.EmailAddressUtil;
import gg.modl.backend.hostedimport.archive.ArchiveDocument;
import gg.modl.backend.hostedimport.mapping.ImportedIds.IdKind;
import gg.modl.backend.role.data.StaffRole;
import gg.modl.backend.role.service.RoleAuthorization;
import org.jspecify.annotations.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class StaffPlan {
    public static final String BLOCKED_INVALID_EMAIL = "invalidEmail";
    public static final String BLOCKED_DUPLICATE_EMAIL = "duplicateEmail";
    public static final String BLOCKED_OWNER = "owner";
    private static final int ID_LIMIT = 36;

    private final List<Candidate> candidates;
    @Nullable
    private final Candidate archivedOwner;
    private final @Nullable String localOwnerEmail;

    private StaffPlan(List<Candidate> candidates, @Nullable Candidate archivedOwner, @Nullable String localOwnerEmail) {
        this.candidates = List.copyOf(candidates);
        this.archivedOwner = archivedOwner;
        this.localOwnerEmail = localOwnerEmail;
    }

    public static StaffPlan of(ArchiveIndex index, ImportedIds ids, @Nullable String localOwnerEmail, RoleMapper roles) {
        String owner = EmailAddressUtil.normalize(localOwnerEmail);
        String archivedAdmin = index.server().adminEmail() == null ? null : EmailAddressUtil.normalizeIfValid(index.server().adminEmail());
        List<Candidate> candidates = new ArrayList<>();
        Set<String> emails = new HashSet<>();
        Candidate archivedOwner = null;
        for (ArchiveDocument source : index.staff()) {
            String sourceId = source.id();
            if (sourceId == null) {
                continue;
            }
            String email = source.string("email") == null ? null : EmailAddressUtil.normalizeIfValid(source.string("email").trim());
            String blocked = null;
            if (email == null || email.length() > STAFF.EMAIL.getDataType().length()) {
                blocked = BLOCKED_INVALID_EMAIL;
            } else if (!emails.add(email.toLowerCase(Locale.ROOT))) {
                blocked = BLOCKED_DUPLICATE_EMAIL;
            } else if (email.equalsIgnoreCase(owner)) {
                blocked = BLOCKED_OWNER;
            }
            String roleSource = source.string("role");
            Optional<StaffRole> role = roleSource == null || RoleAuthorization.SUPER_ADMIN_ROLE_ID.equals(roleSource)
                ? Optional.empty()
                : roles.roleFor(roleSource);
            UUID minecraft = source.uuid("assignedMinecraftUuid");
            Candidate candidate = new Candidate(
                sourceId,
                ids.adopt(IdKind.STAFF, "", sourceId, ID_LIMIT),
                Objects.requireNonNullElse(email, Objects.requireNonNullElse(source.string("email"), "")),
                Objects.requireNonNullElse(source.text("username"), ""),
                role.map(StaffRole::getId).orElse(null),
                role.map(StaffRole::getName).orElse(""),
                role.map(StaffRole::getPermissions).map(List::copyOf).orElse(List.of()),
                minecraft == null ? null : minecraft.toString(),
                source.text("assignedMinecraftUsername"),
                blocked,
                source);
            if (archivedAdmin != null && email != null && email.equalsIgnoreCase(archivedAdmin)) {
                archivedOwner = candidate;
            }
            candidates.add(candidate);
        }
        return new StaffPlan(candidates, archivedOwner, owner);
    }

    public List<Candidate> candidates() {
        return candidates;
    }

    public Optional<Candidate> archivedOwner() {
        return Optional.ofNullable(archivedOwner);
    }

    public boolean ownerEmailsMatch(@Nullable String archivedAdminEmail) {
        return archivedAdminEmail != null && localOwnerEmail != null && archivedAdminEmail.trim().equalsIgnoreCase(localOwnerEmail);
    }

    public Optional<Candidate> ownerDocument(boolean mergeArchivedOwner) {
        Optional<Candidate> sameEmail = candidates.stream().filter(candidate -> BLOCKED_OWNER.equals(candidate.blockedCode())).findFirst();
        if (sameEmail.isPresent()) {
            return sameEmail;
        }
        return mergeArchivedOwner ? archivedOwner() : Optional.empty();
    }

    public Optional<String> selectionProblem(Set<String> selectedSourceIds, boolean mergeArchivedOwner) {
        Map<String, Candidate> bySource = new HashMap<>();
        candidates.forEach(candidate -> bySource.put(candidate.sourceId(), candidate));
        Optional<Candidate> owner = ownerDocument(mergeArchivedOwner);
        for (String selected : selectedSourceIds) {
            Candidate candidate = bySource.get(selected);
            if (candidate == null || !candidate.selectable()
                || owner.map(Candidate::sourceId).filter(selected::equals).isPresent()) {
                return Optional.of(selected);
            }
        }
        return Optional.empty();
    }

    public Map<String, String> restoredStaffIds(Set<String> selectedSourceIds, boolean mergeArchivedOwner, String ownerStaffId) {
        Map<String, String> restored = new HashMap<>();
        candidates.stream().filter(candidate -> selectedSourceIds.contains(candidate.sourceId()))
            .forEach(candidate -> restored.put(candidate.sourceId(), candidate.targetId()));
        ownerDocument(mergeArchivedOwner).ifPresent(owner -> restored.put(owner.sourceId(), ownerStaffId));
        return restored;
    }

    public Map<String, String> archivedUsernames() {
        Map<String, String> usernames = new HashMap<>();
        candidates.forEach(candidate -> usernames.put(candidate.sourceId(), candidate.username()));
        return usernames;
    }

    public record Candidate(
        String sourceId,
        String targetId,
        String email,
        String username,
        @Nullable String roleId,
        String roleName,
        List<String> permissions,
        @Nullable String minecraftUuid,
        @Nullable String minecraftUsername,
        @Nullable String blockedCode,
        ArchiveDocument source
    ) {
        public boolean selectable() {
            return blockedCode == null;
        }
    }
}
