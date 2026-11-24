package gg.modl.backend.email;

public record MailTestResult(MailDeliveryOutcome outcome, String detail, String recipient) {
}
