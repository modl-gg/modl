package gg.modl.backend.email;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class EmailHTMLTemplateTest {
    private static final String SERVER_NAME = "<a href=\"https://evil.example\">Click</a>";
    private static final ValidityPeriod VALIDITY = new ValidityPeriod(Duration.ofHours(24));

    @Test
    void serverAndRoleNamesAreTextInEveryMail() {
        String invite = EmailHTMLTemplate.STAFF_INVITE_TEMPLATE.build(SERVER_NAME, "<b>Admin</b>",
            "https://panel.example.com/invite?a=1&b=2", VALIDITY).body();
        String code = EmailHTMLTemplate.USER_CODE.build(SERVER_NAME, "123456", VALIDITY).body();
        String ticketCode = EmailHTMLTemplate.TICKET_VERIFICATION_CODE.build(SERVER_NAME, "123456", VALIDITY).body();
        String changed = EmailHTMLTemplate.EMAIL_CHANGED_NOTICE.build(SERVER_NAME, "n***@example.com").body();

        for (String body : new String[] {invite, code, ticketCode, changed}) {
            assertThat(body).doesNotContain("<a href=\"https://evil.example\">").contains("&lt;a href=");
        }
        assertThat(invite).doesNotContain("<b>Admin</b>").contains("href=\"https://panel.example.com/invite?a=1&amp;b=2\"");
    }
}
