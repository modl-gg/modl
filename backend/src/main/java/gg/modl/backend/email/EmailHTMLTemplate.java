package gg.modl.backend.email;

import org.springframework.web.util.HtmlUtils;

public interface EmailHTMLTemplate {
    CodeTemplate USER_CODE = (serverName, code, validity) -> new HTMLEmail(
        "%s | Login Code".formatted(serverName),
        """
            <div style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto; background-color: #f9f9f9; padding: 20px;">
              <div style="background-color: white; padding: 30px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1);">
                <h2 style="color: #333; margin-bottom: 20px;">Login Verification Code</h2>
            
                <p style="color: #555; font-size: 16px;">
                  Your login verification code for <strong>%s</strong> is:
                </p>
            
                <div style="background-color: #f8f9fa; padding: 20px; border-left: 4px solid #007bff; margin: 20px 0; text-align: center;">
                  <h3 style="margin: 0; color: #333; font-size: 24px; letter-spacing: 3px;">%s</h3>
                </div>
            
                <p style="color: #888; font-size: 14px; margin: 20px 0;">
                  This code will expire in %s.
                </p>
            
                <div style="border-top: 1px solid #e9ecef; padding-top: 20px; margin-top: 30px;">
                  <p style="color: #6c757d; font-size: 12px; margin: 15px 0 0 0;">
                    This is an automated message. Please do not reply to this email.
                  </p>
                </div>
              </div>
            </div>
            """.formatted(html(serverName), code, validity.describe())
    );

    CodeTemplate EMAIL_CHANGE_CODE = (serverName, code, validity) -> new HTMLEmail(
        "%s | Confirm your new email".formatted(serverName),
        """
            <div style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto; background-color: #f9f9f9; padding: 20px;">
              <div style="background-color: white; padding: 30px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1);">
                <h2 style="color: #333; margin-bottom: 20px;">Confirm Your New Email</h2>

                <p style="color: #555; font-size: 16px;">
                  Use this code to confirm your new email address for <strong>%s</strong>:
                </p>

                <div style="background-color: #f8f9fa; padding: 20px; border-left: 4px solid #007bff; margin: 20px 0; text-align: center;">
                  <h3 style="margin: 0; color: #333; font-size: 24px; letter-spacing: 3px;">%s</h3>
                </div>

                <p style="color: #888; font-size: 14px; margin: 20px 0;">
                  This code will expire in %s. If you did not request an email change, you can ignore this message and your address will stay the same.
                </p>

                <div style="border-top: 1px solid #e9ecef; padding-top: 20px; margin-top: 30px;">
                  <p style="color: #6c757d; font-size: 12px; margin: 15px 0 0 0;">
                    This is an automated message. Please do not reply to this email.
                  </p>
                </div>
              </div>
            </div>
            """.formatted(html(serverName), code, validity.describe())
    );

    CodeTemplate EMAIL_CHANGE_APPROVAL_CODE = (serverName, code, validity) -> new HTMLEmail(
        "%s | Approve an email change".formatted(serverName),
        """
            <div style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto; background-color: #f9f9f9; padding: 20px;">
              <div style="background-color: white; padding: 30px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1);">
                <h2 style="color: #333; margin-bottom: 20px;">Approve An Email Change</h2>

                <p style="color: #555; font-size: 16px;">
                  Someone signed in to your <strong>%s</strong> account asked to move it to a new email address. Use this code to approve it:
                </p>

                <div style="background-color: #f8f9fa; padding: 20px; border-left: 4px solid #007bff; margin: 20px 0; text-align: center;">
                  <h3 style="margin: 0; color: #333; font-size: 24px; letter-spacing: 3px;">%s</h3>
                </div>

                <p style="color: #888; font-size: 14px; margin: 20px 0;">
                  This code will expire in %s. If you did not ask for this, do not share the code and sign out of your other sessions.
                </p>

                <div style="border-top: 1px solid #e9ecef; padding-top: 20px; margin-top: 30px;">
                  <p style="color: #6c757d; font-size: 12px; margin: 15px 0 0 0;">
                    This is an automated message. Please do not reply to this email.
                  </p>
                </div>
              </div>
            </div>
            """.formatted(html(serverName), code, validity.describe())
    );

    EmailChangedNoticeTemplate EMAIL_CHANGED_NOTICE = (serverName, newEmail) -> new HTMLEmail(
        "%s | Your email address was changed".formatted(serverName),
        """
            <div style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto; background-color: #f9f9f9; padding: 20px;">
              <div style="background-color: white; padding: 30px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1);">
                <h2 style="color: #333; margin-bottom: 20px;">Email Address Changed</h2>

                <p style="color: #555; font-size: 16px;">
                  The email address for your <strong>%s</strong> staff account was just changed to <strong>%s</strong>.
                </p>

                <p style="color: #555; font-size: 16px;">
                  If you made this change, no action is needed. If you did not, contact your server administrator immediately. Your account security may be at risk.
                </p>

                <div style="border-top: 1px solid #e9ecef; padding-top: 20px; margin-top: 30px;">
                  <p style="color: #6c757d; font-size: 12px; margin: 15px 0 0 0;">
                    This is an automated message. Please do not reply to this email.
                  </p>
                </div>
              </div>
            </div>
            """.formatted(html(serverName), html(newEmail))
    );

    StaffInviteTemplate STAFF_INVITE_TEMPLATE = (serverName, staffRole, link, validity) -> new HTMLEmail(
        "%s | Staff Invitation".formatted(serverName),
        """
            <div style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto; background-color: #f9f9f9; padding: 20px;">
              <div style="background-color: white; padding: 30px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1);">
                <h2 style="color: #333; margin-bottom: 20px;">Team Invitation</h2>
            
                <p style="color: #555; font-size: 16px;">
                  You have been invited to join the <strong>%s</strong> team as a <strong>%s</strong>!
                </p>
            
                <div style="background-color: #f8f9fa; padding: 15px; border-left: 4px solid #28a745; margin: 20px 0;">
                  <h4 style="margin: 0 0 10px 0; color: #333;">Welcome to the Team!</h4>
                  <p style="margin: 0; color: #555;">Click the button below to accept your invitation and get started.</p>
                </div>
            
                <div style="text-align: center; margin: 30px 0;">
                  <a href="%s" style="background-color: #28a745; color: white; padding: 12px 24px; text-decoration: none; border-radius: 4px; display: inline-block; font-weight: bold;">Accept Invitation</a>
                </div>
            
                <p style="color: #888; font-size: 14px; margin: 20px 0;">
                  This invitation will expire in %s.
                </p>
            
                <div style="border-top: 1px solid #e9ecef; padding-top: 20px; margin-top: 30px;">
                  <p style="color: #6c757d; font-size: 12px; margin: 15px 0 0 0;">
                    This is an automated message. Please do not reply to this email.
                  </p>
                </div>
              </div>
            </div>
            """.formatted(html(serverName), html(staffRole), html(link), validity.describe())
    );

    TicketReplyTemplate TICKET_REPLY_TEMPLATE = (serverName, playerName, isStaffReply, ticketType, ticketId, ticketSubject, replyAuthor, replyContent, ticketUrl) -> new HTMLEmail(
        "%s | Someone replied to Ticket #%s".formatted(serverName, ticketId),
        """
            <div style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto; background-color: #f9f9f9; padding: 20px;">
              <div style="background-color: white; padding: 30px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1);">
                <h2 style="color: #333; margin-bottom: 20px;">Ticket Reply Notification</h2>
            
                <p style="color: #555; font-size: 16px;">Hello <strong>%s</strong>,</p>
            
                <p style="color: #555; font-size: 16px;">
                  %s has replied to your <strong>%s</strong> ticket:
                </p>
            
                <div style="background-color: #f8f9fa; padding: 15px; border-left: 4px solid #007bff; margin: 20px 0;">
                  <h4 style="margin: 0 0 10px 0; color: #333;">Ticket #%s: %s</h4>
                </div>
            
                <div style="background-color: #fff; border: 1px solid #e9ecef; border-radius: 4px; padding: 15px; margin: 20px 0;">
                  <h5 style="margin: 0 0 10px 0; color: #495057;">Reply from %s:</h5>
                  <p style="margin: 0; color: #333; white-space: pre-wrap;">%s</p>
                </div>
            
                <div style="text-align: center; margin: 30px 0;">
                  <a href="%s" style="background-color: #007bff; color: white; padding: 12px 24px; text-decoration: none; border-radius: 4px; display: inline-block; font-weight: bold;">View Ticket & Reply</a>
                </div>
            
                <div style="border-top: 1px solid #e9ecef; padding-top: 20px; margin-top: 30px;">
                  <p style="color: #6c757d; font-size: 12px; margin: 15px 0 0 0;">
                    This is an automated message. Please do not reply to this email.
                  </p>
                </div>
              </div>
            </div>
            """.formatted(playerName, isStaffReply ? "A staff member" : "Someone", ticketType, ticketId, ticketSubject, replyAuthor, replyContent, ticketUrl));

    TicketTranscriptTemplate TICKET_TRANSCRIPT_TEMPLATE = (serverName, playerName, ticketType, ticketId, ticketSubject, messagesHtml, ticketUrl) -> new HTMLEmail(
        "%s | Ticket #%s Transcript".formatted(serverName, ticketId),
        """
            <div style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto; background-color: #f9f9f9; padding: 20px;">
              <div style="background-color: white; padding: 30px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1);">
                <h2 style="color: #333; margin-bottom: 20px;">Ticket Closed - Transcript</h2>
            
                <p style="color: #555; font-size: 16px;">Hello <strong>%s</strong>,</p>
            
                <p style="color: #555; font-size: 16px;">
                  Your <strong>%s</strong> ticket has been closed. Here is the full transcript:
                </p>
            
                <div style="background-color: #f8f9fa; padding: 15px; border-left: 4px solid #6c757d; margin: 20px 0;">
                  <h4 style="margin: 0 0 5px 0; color: #333;">Ticket #%s: %s</h4>
                </div>
            
                %s
            
                <div style="text-align: center; margin: 30px 0;">
                  <a href="%s" style="background-color: #007bff; color: white; padding: 12px 24px; text-decoration: none; border-radius: 4px; display: inline-block; font-weight: bold;">View Ticket</a>
                </div>
            
                <div style="border-top: 1px solid #e9ecef; padding-top: 20px; margin-top: 30px;">
                  <p style="color: #6c757d; font-size: 12px; margin: 15px 0 0 0;">
                    This is an automated message. Please do not reply to this email.
                  </p>
                </div>
              </div>
            </div>
            """.formatted(playerName, ticketType, ticketId, ticketSubject, messagesHtml, ticketUrl));

    CodeTemplate TICKET_VERIFICATION_CODE = (serverName, code, validity) -> new HTMLEmail(
        "%s | Ticket Verification Code".formatted(serverName),
        """
            <div style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto; background-color: #f9f9f9; padding: 20px;">
              <div style="background-color: white; padding: 30px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1);">
                <h2 style="color: #333; margin-bottom: 20px;">Ticket Verification Code</h2>
            
                <p style="color: #555; font-size: 16px;">
                  Your ticket verification code for <strong>%s</strong> is:
                </p>
            
                <div style="background-color: #f8f9fa; padding: 20px; border-left: 4px solid #007bff; margin: 20px 0; text-align: center;">
                  <h3 style="margin: 0; color: #333; font-size: 24px; letter-spacing: 3px;">%s</h3>
                </div>
            
                <p style="color: #888; font-size: 14px; margin: 20px 0;">
                  This code will expire in %s.
                </p>
            
                <div style="border-top: 1px solid #e9ecef; padding-top: 20px; margin-top: 30px;">
                  <p style="color: #6c757d; font-size: 12px; margin: 15px 0 0 0;">
                    This is an automated message. Please do not reply to this email.
                  </p>
                </div>
              </div>
            </div>
            """.formatted(html(serverName), code, validity.describe())
    );

    TestEmailTemplate MAIL_TEST = serverName -> new HTMLEmail(
        "%s | Test email".formatted(serverName),
        """
            <div style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto; background-color: #f9f9f9; padding: 20px;">
              <div style="background-color: white; padding: 30px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1);">
                <h2 style="color: #333; margin-bottom: 20px;">Email Delivery Works</h2>

                <p style="color: #555; font-size: 16px;">
                  This test email confirms that <strong>%s</strong> can send email. Login codes, staff invitations and ticket notifications will now be delivered by email.
                </p>

                <div style="border-top: 1px solid #e9ecef; padding-top: 20px; margin-top: 30px;">
                  <p style="color: #6c757d; font-size: 12px; margin: 15px 0 0 0;">
                    This is an automated message. Please do not reply to this email.
                  </p>
                </div>
              </div>
            </div>
            """.formatted(HtmlUtils.htmlEscape(serverName))
    );

    interface CodeTemplate {
        HTMLEmail build(String serverName, String code, ValidityPeriod validity);
    }

    interface EmailChangedNoticeTemplate {
        HTMLEmail build(String serverName, String newEmail);
    }

    interface StaffInviteTemplate {
        HTMLEmail build(String serverName, String staffRole, String link, ValidityPeriod validity);
    }

    interface TicketReplyTemplate {
        HTMLEmail build(String serverName, String playerName, boolean isStaffReply, String ticketType, String ticketId, String ticketSubject, String replyAuthor, String replyContent, String ticketUrl);
    }

    interface TicketTranscriptTemplate {
        HTMLEmail build(String serverName, String playerName, String ticketType, String ticketId, String ticketSubject, String messagesHtml, String ticketUrl);
    }

    interface TestEmailTemplate {
        HTMLEmail build(String serverName);
    }

    record HTMLEmail(String subject, String body) {
    }

    private static String html(String text) {
        return text == null ? "" : HtmlUtils.htmlEscape(text);
    }
}
