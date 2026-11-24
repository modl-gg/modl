package gg.modl.backend.hostedimport.job;

import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.hostedimport.archive.ArchiveDocument;
import gg.modl.backend.hostedimport.mapping.ArchiveIndex;
import gg.modl.backend.hostedimport.mapping.StaffPlan;
import gg.modl.backend.hostedimport.mapping.StaffPlan.Candidate;
import gg.modl.backend.hostedimport.mapping.StaffRecords;
import gg.modl.backend.hostedimport.model.ImportFailure;
import gg.modl.backend.hostedimport.model.ImportPhase;
import gg.modl.backend.hostedimport.model.ImportReason;
import gg.modl.backend.hostedimport.model.ImportSubject;
import gg.modl.backend.hostedimport.model.IssueCode;
import gg.modl.backend.hostedimport.repository.ImportTargetRepository;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.staff.data.Staff;
import gg.modl.backend.staff.repository.StaffInsertOutcome;
import gg.modl.backend.staff.repository.StaffRepository;
import gg.modl.backend.staff.service.ImportedStaffAccounts;
import gg.modl.backend.staff.service.StaffLookupCache;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AccessSteps {
    private static final long RESTORED = 1;

    private final ImportedStaffAccounts accounts;
    private final StaffRepository staffRepository;
    private final ImportTargetRepository targets;
    private final PermissionService permissionService;
    private final StaffLookupCache staffLookupCache;
    private final UnitOfWork unitOfWork;
    private final Clock clock;

    List<PhaseStep> steps() {
        return List.of(new Access(), new Subscriptions());
    }

    private final class Access implements PhaseStep {
        @Override
        public ImportPhase phase() {
            return ImportPhase.ACCESS;
        }

        @Override
        public long total(ArchiveIndex index) {
            return RESTORED;
        }

        @Override
        public void run(PhaseRun run) {
            if (run.cursor() >= RESTORED) {
                return;
            }
            StaffPlan plan = run.plan().staff();
            Set<String> selected = run.choices().staffIds();
            long unselected = plan.candidates().stream()
                .filter(candidate -> !selected.contains(candidate.sourceId()))
                .filter(candidate -> plan.ownerDocument(run.choices().mergeArchivedOwner())
                    .map(Candidate::sourceId).filter(candidate.sourceId()::equals).isEmpty())
                .count();
            run.issues().report(IssueCode.STAFF_NOT_RESTORED, unselected);
            run.commitState(RESTORED, () -> {
                Staff owner = accounts.ownerRecord(run.server()).orElseThrow(() -> new ImportFailure(ImportReason.UNEXPECTED));
                Set<String> claimedMinecraft = new HashSet<>();
                if (owner.getAssignedMinecraftUuid() != null) {
                    claimedMinecraft.add(owner.getAssignedMinecraftUuid());
                }
                plan.ownerDocument(run.choices().mergeArchivedOwner()).ifPresent(document -> ownerPreferences(owner, document, run));
                plan.candidates().stream()
                    .filter(candidate -> selected.contains(candidate.sourceId()))
                    .forEach(candidate -> restore(candidate, claimedMinecraft, run));
                unitOfWork.afterCommit(() -> {
                    permissionService.evictPermissionCache();
                    staffLookupCache.evictAll();
                });
            });
        }

        private void ownerPreferences(Staff owner, Candidate document, PhaseRun run) {
            Staff archived = StaffRecords.toStaff(document, run.context().fit(), run.context().exportTime());
            String ownerId = owner.getId();
            if (ownerId == null) {
                throw new IllegalStateException("The stored owner record has no id");
            }
            Instant now = clock.instant();
            staffRepository.updateProfile(ownerId, null, archived.getLanguage(), archived.getDateFormat(), now);
            if (archived.getTicketSubscriptionSettings() != null) {
                staffRepository.updateTicketSubscriptionSettings(ownerId, archived.getTicketSubscriptionSettings(), now);
            }
        }

        private void restore(Candidate candidate, Set<String> claimedMinecraft, PhaseRun run) {
            Staff staff = StaffRecords.toStaff(candidate, run.context().fit(), run.context().exportTime());
            String minecraft = candidate.minecraftUuid();
            if (minecraft != null) {
                boolean free = claimedMinecraft.add(minecraft) && staffRepository.findByAssignedMinecraftUuid(minecraft).isEmpty();
                if (free) {
                    staff.setAssignedMinecraftUuid(minecraft);
                    staff.setAssignedMinecraftUsername(run.context().fit().cutIfPresent(candidate.minecraftUsername(), 100));
                } else {
                    run.issues().report(IssueCode.STAFF_LINK_DROPPED, candidate.email());
                }
            }
            String requested = staff.getUsername();
            StaffInsertOutcome outcome = accounts.insertRestored(staff);
            if (outcome != StaffInsertOutcome.INSERTED) {
                run.issues().report(IssueCode.STAFF_NOT_RESTORED, candidate.email());
                return;
            }
            if (!Objects.equals(requested, staff.getUsername())) {
                run.issues().report(IssueCode.STAFF_RENAMED, candidate.email());
            }
            run.issues().imported(ImportSubject.STAFF);
        }
    }

    private final class Subscriptions implements PhaseStep {
        @Override
        public ImportPhase phase() {
            return ImportPhase.SUBSCRIPTIONS;
        }

        @Override
        public long total(ArchiveIndex index) {
            return index.staff().size();
        }

        @Override
        public void run(PhaseRun run) {
            List<Holder> holders = holders(run);
            for (int index = (int) run.cursor(); index < holders.size(); index++) {
                Holder holder = holders.get(index);
                List<Subscription> subscriptions = subscriptions(holder.source(), run);
                run.commitState(index + 1L, () -> subscriptions.forEach(subscription -> {
                    staffRepository.subscribe(holder.email(), subscription.ticketId(), subscription.subscribedAt());
                    if (subscription.lastReadAt() != null) {
                        staffRepository.markSubscriptionRead(holder.email(), subscription.ticketId(), subscription.lastReadAt());
                    }
                }));
            }
        }

        private List<Holder> holders(PhaseRun run) {
            StaffPlan plan = run.plan().staff();
            List<Holder> holders = new ArrayList<>();
            Optional<Candidate> owner = plan.ownerDocument(run.choices().mergeArchivedOwner());
            owner.ifPresent(document -> holders.add(new Holder(run.ownerEmail(), document.source())));
            plan.candidates().stream()
                .filter(candidate -> run.choices().staffIds().contains(candidate.sourceId()))
                .forEach(candidate -> holders.add(new Holder(candidate.email(), candidate.source())));
            return holders;
        }

        private List<Subscription> subscriptions(ArchiveDocument staff, PhaseRun run) {
            List<Subscription> wanted = new ArrayList<>();
            for (ArchiveDocument subscription : staff.documents("subscribedTickets")) {
                String ticketId = run.context().ticketIds().of(StaffRecords.subscribedTicket(subscription));
                if (ticketId != null && StaffRecords.isActiveSubscription(subscription)) {
                    wanted.add(new Subscription(ticketId,
                        Objects.requireNonNullElse(subscription.instant("subscribedAt"), run.context().exportTime()),
                        subscription.instant("lastReadAt")));
                }
            }
            Set<String> existing = targets.existingTicketIds(wanted.stream().map(Subscription::ticketId).toList());
            long dangling = wanted.stream().filter(subscription -> !existing.contains(subscription.ticketId())).count();
            run.issues().report(IssueCode.REFERENCE_DANGLING, dangling);
            return wanted.stream().filter(subscription -> existing.contains(subscription.ticketId())).toList();
        }
    }

    private record Holder(String email, ArchiveDocument source) {
    }

    private record Subscription(String ticketId, Instant subscribedAt, @Nullable Instant lastReadAt) {
    }
}
