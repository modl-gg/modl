package gg.modl.backend.email;

public record MailFailure(MailDeliveryOutcome outcome, String detail) {
}
