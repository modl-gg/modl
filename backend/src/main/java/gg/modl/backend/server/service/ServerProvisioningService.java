package gg.modl.backend.server.service;

import gg.modl.backend.database.EntityIds;
import gg.modl.backend.database.JsonColumns;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.homepage.data.HomepageCard;
import gg.modl.backend.homepage.repository.HomepageCardRepository;
import gg.modl.backend.knowledgebase.data.KnowledgebaseArticle;
import gg.modl.backend.knowledgebase.data.KnowledgebaseCategory;
import gg.modl.backend.knowledgebase.data.StarterArticles;
import gg.modl.backend.knowledgebase.data.StarterArticles.StarterArticle;
import gg.modl.backend.knowledgebase.repository.KnowledgebaseArticleRepository;
import gg.modl.backend.knowledgebase.repository.KnowledgebaseCategoryRepository;
import gg.modl.backend.role.service.RoleService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.repository.SettingsRepository;
import gg.modl.backend.settings.service.PunishmentTypeEditor;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ServerProvisioningService {
    private static final String AI_CHAT_ABUSE_CONFIG_ID = "6";
    private static final String AI_ANTI_SOCIAL_CONFIG_ID = "7";

    private final SettingsRepository settingsRepository;
    private final KnowledgebaseCategoryRepository knowledgebaseCategoryRepository;
    private final KnowledgebaseArticleRepository knowledgebaseArticleRepository;
    private final HomepageCardRepository homepageCardRepository;
    private final PunishmentTypeEditor punishmentTypeEditor;
    private final RoleService roleService;
    private final UnitOfWork unitOfWork;
    private final JsonColumns jsonColumns;
    private final Clock clock;

    public void provision(Server server) {
        List<String> failures = new ArrayList<>();
        Exception firstError = null;

        for (Map.Entry<String, Runnable> step : provisioningSteps(server).entrySet()) {
            try {
                step.getValue().run();
            } catch (Exception e) {
                log.error("[Provisioning] Step '{}' failed", step.getKey(), e);
                failures.add(step.getKey() + ": " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                if (firstError == null) {
                    firstError = e;
                }
            }
        }

        if (!failures.isEmpty()) {
            throw new ProvisioningException(
                "Provisioning failed [" + String.join("; ", failures) + "]",
                firstError);
        }
    }

    private Map<String, Runnable> provisioningSteps(Server server) {
        Map<String, Runnable> steps = new LinkedHashMap<>();
        steps.put("seedPunishmentTypes", () -> punishmentTypeEditor.seedDefaultTypes(server));
        steps.put("seedAIModerationSettings", this::seedAIModerationSettings);
        steps.put("seedTicketForms", this::seedTicketForms);
        steps.put("seedQuickResponses", this::seedQuickResponses);
        steps.put("seedGeneralSettings", this::seedGeneralSettings);
        steps.put("seedTicketLabelSettings", this::seedTicketLabelSettings);
        steps.put("seedKnowledgebaseCategories", () -> unitOfWork.write(this::seedKnowledgebaseCategories));
        steps.put("seedHomepageCards", () -> unitOfWork.write(this::seedHomepageCards));
        steps.put("createDefaultRoles", () -> roleService.createDefaultRoles(server));
        return steps;
    }

    private void seedAIModerationSettings() {
        if (settingsExist("aiModerationSettings")) {
            return;
        }

        Map<String, Object> chatAbuseConfig = new LinkedHashMap<>();
        chatAbuseConfig.put("id", AI_CHAT_ABUSE_CONFIG_ID);
        chatAbuseConfig.put("name", "Chat Abuse");
        chatAbuseConfig.put("aiDescription",
            "Chat abuse is the act of spamming, excessive profanity, abusive language, inappropriate topics or jokes, and misleading information");
        chatAbuseConfig.put("enabled", true);

        Map<String, Object> antiSocialConfig = new LinkedHashMap<>();
        antiSocialConfig.put("id", AI_ANTI_SOCIAL_CONFIG_ID);
        antiSocialConfig.put("name", "Anti-Social");
        antiSocialConfig.put("aiDescription",
            "Anti-social behavior is the act of harassing, threatening, black-mailing, or otherwise abusing another player or group of players. This includes bigotry and other forms of discrimination against protected classes.");
        antiSocialConfig.put("enabled", true);

        Map<String, Object> aiPunishmentConfigs = new LinkedHashMap<>();
        aiPunishmentConfigs.put(AI_CHAT_ABUSE_CONFIG_ID, chatAbuseConfig);
        aiPunishmentConfigs.put(AI_ANTI_SOCIAL_CONFIG_ID, antiSocialConfig);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("enableAIReview", false);
        data.put("enableAutomatedActions", false);
        data.put("aiPunishmentConfigs", aiPunishmentConfigs);

        storeIfAbsent("aiModerationSettings", data);
    }

    private boolean settingsExist(String type) {
        return settingsRepository.find(type).isPresent();
    }

    private void storeIfAbsent(String type, Map<String, Object> data) {
        String json = jsonColumns.write(data);
        unitOfWork.write(() -> {
            settingsRepository.insertIfAbsent(type, json);
        });
    }

    private void seedTicketForms() {
        if (settingsExist("ticketForms")) {
            return;
        }

        Map<String, Object> bugForm = new LinkedHashMap<>();
        bugForm.put("fields", List.of(
            formField("1753243804677", "textarea", "Bug", "Describe the bug in full detail", true, 3, "1753243782799"),
            formField("1753243846548", "textarea", "Environment", "Game/server, client version, and any other relevant conditions", true, 3, "1753243782799"),
            formField("1753243865490", "textarea", "Steps to reproduce", null, true, 2, "1753243782799"),
            formField("1753243883567", "textarea", "Anything else", null, false, 3, "1753243782799"),
            formField("1753243946458", "file_upload", "Attachments", null, false, 4, "1753243782799")
        ));
        bugForm.put("sections", List.of(
            formSection("1753243782799", "General", 0, false)
        ));

        Map<String, Object> supportForm = new LinkedHashMap<>();
        supportForm.put("fields", List.of(
            formField("1753243961223", "textarea", "How can we help?", null, true, 0, "1753243900648"),
            formField("1753243997358", "file_upload", "Attachments", null, false, 1, "1753243900648")
        ));
        supportForm.put("sections", List.of(
            formSection("1753243900648", "General", 0, false)
        ));

        Map<String, Object> applicationForm = buildApplicationForm();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("bug", bugForm);
        data.put("support", supportForm);
        data.put("application", applicationForm);

        storeIfAbsent("ticketForms", data);
    }

    private Map<String, Object> buildApplicationForm() {
        List<Map<String, Object>> fields = new ArrayList<>();

        fields.add(formField("1753244313811", "text", "First name", null, true, 0, "1753244011186"));
        fields.add(formField("1753244038340", "text", "Discord username", "Starting with @", true, 1, "1753244011186"));
        fields.add(formField("1753244070995", "text", "Age", null, true, 2, "1753244011186"));
        fields.add(formField("1753244166086", "text", "Region and time zone", "For example NA, Eastern Time", true, 3, "1753244011186"));
        fields.add(
            formField("1753244525756", "text", "Languages you speak", "With your fluency in each", true,
                4, "1753244011186"));
        fields.add(
            formField("1753244114967", "checkbox", "Do you have access to both a working microphone and recording software?", null, true, 5, "1753244011186"));

        Map<String, Object> positionField = new LinkedHashMap<>();
        positionField.put("id", "1753244244863");
        positionField.put("type", "dropdown");
        positionField.put("label", "Position");
                positionField.put("required", true);
        positionField.put("options", List.of("Moderator", "Builder", "Developer", "Media"));
        positionField.put("order", 6);
        positionField.put("sectionId", "1753244011186");
        positionField.put("optionSectionMapping", Map.of(
            "Moderator", "1753244183109",
            "Builder", "1753244277605",
            "Developer", "1753244282540",
            "Media", "1753244286527"
        ));
        fields.add(positionField);

        fields.add(formField("1753244506417", "textarea", "Have you ever been banned or muted on this server? If yes, what have you learned moving forward?",
            "If so, please explain each occurrence.", true, 0, "1753244183109"));
        fields.add(formField("1753244551193", "textarea", "Describe your moderation background and previous experience.",
            "Any community counts, not only Minecraft servers. Add references for notable experience.",
            true, 1, "1753244183109"));
        fields.add(formField("1753244585381", "textarea", "Why do you want to become a moderator on this server?",
            null,
            true, 9, "1753244183109"));
        fields.add(formField("1753244603377", "text", "How much time do you see yourself committing to the server?", null, true, 10, "1753244183109"));
        fields.add(formField("1753244687326", "textarea",
            "You are a Moderator with the ability to mute and ban. You are playing on the server with a friend and come across a player who you think is hacking. They kill your friend, but then you kill them. What do you do in this situation?",
            null, true, 11, "1753244183109"));
        fields.add(formField("1753244762984", "textarea",
            "You are a Moderator with the ability to mute and ban. You are spectating a player who you believe is hacking, but multiple chat reports come in about a player in another gamemode who is being violently disruptive in chat. Somehow, you are the only moderator online. How do you handle the two situations?",
            null, true, 12, "1753244183109"));
        fields.add(formField("1753244861431", "textarea",
            "You are a Moderator with the ability to mute and ban. You see 5+ reports come in accusing the same player of breaking the chat rules. You join the server where the situation is taking place and open the accused player's recent chat history. You see that they were being rude, but haven't actually broken a rule. When you decide that they are not guilty, the same group reports the player again, and sends you multiple private messages calling you a bad moderator for not muting the player. What's the first step in dealing with this situation? Explain how this step will move towards resolving the conflict.",
            null, true, 13, "1753244183109"));
        fields.add(formField("1753244931272", "textarea",
            "You are the newest Moderator on the team. While you are spectating a game, you witness a Sr. Moderator mining suspiciously. In a matter of minutes, you get enough evidence that suggests that the Sr. Moderator may likely be x-raying. Suddenly, they head to the surface and do nothing suspicious for the rest of your time spectating them. How do you proceed?",
            null, true, 14, "1753244183109"));
        fields.add(formField("1753245023983", "textarea",
            "You are a Moderator with the ability to mute and ban. You notice a well-known streamer/YouTuber closely affiliated with the server is nicked. They message a player words encouraging suicide under their disguised alias. What steps do you take to resolve the situation?",
            null, true, 15, "1753244183109"));

        fields.add(formField("1753245081481", "textarea", "Do you have experience building for other servers?", null, true, 16, "1753244277605"));
        fields.add(formField("1753245137086", "textarea", "Please provide proof of previous work in link form here (Imgur, YouTube, etc)", null, true, 17,
            "1753244277605"));
        fields.add(formField("1753245154307", "textarea", "Anything else you would like to say?", null, false, 23, "1753244277605"));

        fields.add(formField("1753245191475", "textarea", "Why do you want to be a developer on this server?", null, true, 0, "1753244282540"));
        fields.add(formField("1753245262717", "textarea", "Do you have experience developing for other servers?", null, true, 1, "1753244282540"));
        fields.add(formField("1753245280773", "text", "Please provide proof of previous work in the form of a GitHub link", null, true, 2, "1753244282540"));
        fields.add(formField("1753245291714", "textarea", "Anything else you would like to say?", null, false, 3, "1753244282540"));

        fields.add(formField("1753245348514", "text", "Have you ever been banned or muted on this server? If yes, what have you learned moving forward?",
            "If so, please explain each occurrence.", true, 23, "1753244286527"));
        fields.add(formField("1753245358313", "text", "A link to your YouTube and/or Stream Channel", null, true, 24, "1753244286527"));
        fields.add(formField("1753245471763", "checkbox",
            "We will email the contact email listed on the channel for proof of ownership, please verify it is accurate and actively monitored.", null, true,
            25, "1753244286527"));
        fields.add(formField("1753245511672", "textarea", "Anything else you would like to say?", null, false, 26, "1753244286527"));

        List<Map<String, Object>> sections = List.of(
            formSection("1753244011186", "General", 0, false),
            formSection("1753244183109", "Moderator", 1, true),
            formSection("1753244277605", "Builder", 2, true),
            formSection("1753244282540", "Developer", 3, true),
            formSection("1753244286527", "Media", 4, true)
        );

        Map<String, Object> form = new LinkedHashMap<>();
        form.put("fields", fields);
        form.put("sections", sections);
        return form;
    }

    private Map<String, Object> formField(String id, String type, String label, @Nullable String description, boolean required, int order, String sectionId) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("id", id);
        field.put("type", type);
        field.put("label", label);
        if (description != null) {
            field.put("description", description);
        }
        field.put("required", required);
        field.put("order", order);
        field.put("sectionId", sectionId);
        return field;
    }

    private Map<String, Object> formSection(String id, String title, int order, boolean hideByDefault) {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("id", id);
        section.put("title", title);
        section.put("order", order);
        section.put("hideByDefault", hideByDefault);
        return section;
    }

    private void seedQuickResponses() {
        if (settingsExist("quickResponses")) {
            return;
        }

        List<Map<String, Object>> categories = new ArrayList<>();

        categories.add(quickResponseCategory("chat_report_actions", "Chat reports", List.of("chat_report"), 1, List.of(
            quickResponseAction("accept_report", "Accept",
                "Thank you for creating this report. After careful review, we have accepted this and the reported player will be receiving a punishment.", 1,
                true, true, null),
            quickResponseAction("reject_insufficient_chat", "Ask for evidence",
                "Thank you for submitting this chat report. After reviewing the evidence provided, we need additional evidence to proceed with action.", 2,
                false, false, null),
            quickResponseAction("reject_no_violation_chat", "No violation",
                "Thank you for submitting this chat report. After reviewing the evidence provided, we have determined that this does not violate our community guidelines.",
                3, true, false, null)
        )));

        categories.add(quickResponseCategory("player_report_actions", "Player reports", List.of("player_report"), 2, List.of(
            quickResponseAction("accept_report", "Accept",
                "Thank you for creating this report. After careful review, we have accepted this and the reported player will be receiving a punishment.", 1,
                true, true, null),
            quickResponseAction("reject_insufficient_player", "Ask for evidence",
                "Thank you for submitting this player report. After reviewing the evidence provided, we need additional evidence to proceed with action.", 2,
                false, false, null),
            quickResponseAction("reject_no_violation_player", "No violation",
                "Thank you for submitting this player report. After reviewing the evidence provided, we have determined that this does not violate our community guidelines.",
                3, true, false, null)
        )));

        categories.add(quickResponseCategory("appeal_actions", "Appeals", List.of("appeal"), 3, List.of(
            quickResponseAction("pardon_full", "Pardon",
                "After reviewing your appeal, we have decided to remove the punishment completely. We apologize for any inconvenience.", 1, true, false,
                "pardon"),
            quickResponseAction("reduce_punishment", "Reduce",
                "We have reviewed your appeal and decided to reduce the duration of your punishment. Please check your punishment details for the updated duration.",
                2, true, false, "reduce"),
            quickResponseAction("reject_upheld", "Uphold",
                "After careful consideration of your appeal, we have decided to uphold the original punishment.", 3, true, false, "reject"),
            quickResponseAction("need_more_info_appeal", "Ask for details",
                "We need additional information to process your appeal. Please provide more details about your situation.", 4, false, false, null)
        )));

        categories.add(quickResponseCategory("application_actions", "Staff applications", List.of("application"), 4, List.of(
            quickResponseAction("accept_builder", "Accept as builder",
                "Your builder application has been accepted. Welcome to the team. You will receive further instructions and permissions shortly.",
                1, true, false, null),
            quickResponseAction("accept_helper", "Accept as helper",
                "Your helper application has been accepted. Welcome to the team. You will receive further instructions and permissions shortly.",
                2, true, false, null),
            quickResponseAction("accept_developer", "Accept as developer",
                "Your developer application has been accepted. Welcome to the team. You will receive further instructions and permissions shortly.",
                3, true, false, null),
            quickResponseAction("reject_application", "Reject",
                "Thank you for your interest in joining our team. Unfortunately, we have decided not to move forward with your application at this time. You may reapply in the future.",
                4, true, false, null),
            quickResponseAction("pending_review", "In review",
                "Thank you for your application. We are currently reviewing it and will get back to you soon.", 5, false, false, null),
            quickResponseAction("interview_scheduled", "Interview scheduled",
                "Your application has progressed to the interview stage. Please check your email for interview details.", 6, false, false, null),
            quickResponseAction("need_more_info_app", "Ask for details",
                "We need additional information about your application. Please provide more details about your experience and qualifications.", 7, false, false,
                null)
        )));

        categories.add(quickResponseCategory("bug_actions", "Bug reports", List.of("bug"), 5, List.of(
            quickResponseAction("completed", "Fixed", "Thank you for reporting this bug. We have fixed the issue and it will be included in our next update.",
                1, true, false, null),
            quickResponseAction("investigating", "Investigating",
                "Thank you for this bug report. We are currently investigating the issue and will provide updates as they become available.", 2, false, false,
                null),
            quickResponseAction("need_more_info", "Ask for details",
                "Thank you for this bug report. We need additional information to investigate this issue. Please provide more details about how to reproduce this bug.",
                3, false, false, null),
            quickResponseAction("duplicate", "Duplicate",
                "This bug has been identified as a duplicate of an existing issue. We appreciate your report and are working on a fix.", 4, true, false, null),
            quickResponseAction("cannot_reproduce", "Cannot reproduce",
                "We were unable to reproduce this issue. If you continue to experience this problem, please provide additional details.", 5, true, false, null)
        )));

        categories.add(quickResponseCategory("support_actions", "Support", List.of("support"), 6, List.of(
            quickResponseAction("resolved", "Resolved",
                "Your support request has been resolved. If you need further assistance, please feel free to create a new ticket.", 1, true, false, null),
            quickResponseAction("escalated", "Escalated",
                "Your support request has been escalated to our specialized team. They will contact you with additional information.", 2, false, false, null),
            quickResponseAction("need_info_support", "Ask for details",
                "We need additional information to assist you with your request. Please provide more details about your issue.", 3, false, false, null)
        )));

        categories.add(
            quickResponseCategory("general_actions", "General", List.of("player_report", "chat_report", "bug", "appeal", "support", "application"), 7,
                List.of(
                    quickResponseAction("acknowledge", "Acknowledge", "Thank you for your message. We have received your ticket and will review it shortly.", 1,
                        false, false, null),
                    quickResponseAction("follow_up", "Follow up",
                        "We are following up on your ticket. Please let us know if you have any additional information or questions.", 2, false, false, null)
                )));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("categories", categories);

        storeIfAbsent("quickResponses", data);
    }

    private Map<String, Object> quickResponseCategory(String id, String name, List<String> ticketTypes, int order, List<Map<String, Object>> actions) {
        Map<String, Object> category = new LinkedHashMap<>();
        category.put("id", id);
        category.put("name", name);
        category.put("ticketTypes", ticketTypes);
        category.put("order", order);
        category.put("actions", actions);
        return category;
    }

    private Map<String, Object> quickResponseAction(String id, String name, String message, int order, boolean closeTicket, boolean showPunishment, @Nullable String appealAction) {
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("id", id);
        action.put("name", name);
        action.put("message", message);
        action.put("order", order);
        action.put("closeTicket", closeTicket);
        if (showPunishment) {
            action.put("showPunishment", true);
        }
        if (appealAction != null) {
            action.put("appealAction", appealAction);
        }
        return action;
    }

    private void seedGeneralSettings() {
        if (settingsExist("general")) {
            return;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("homepageIconUrl", "");
        data.put("panelIconUrl", "");

        storeIfAbsent("general", data);
    }

    private void seedTicketLabelSettings() {
        if (settingsExist("ticketLabels")) {
            return;
        }

        List<Map<String, Object>> labels = List.of(
            labelMap("high priority", "#e74c3c", "High priority tickets"),
            labelMap("needs admin review", "#f39c12", "Tickets that need review"),
            labelMap("in progress", "#2ecc71", "Tickets being worked on"),
            labelMap("won't fix", "#6b7280", "Issues that won't be fixed"),
            labelMap("duplicate", "#6b7280", "Duplicate tickets")
        );

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("labels", labels);

        storeIfAbsent("ticketLabels", data);
    }

    private Map<String, Object> labelMap(String name, String color, String description) {
        Map<String, Object> label = new LinkedHashMap<>();
        label.put("id", UUID.randomUUID().toString());
        label.put("name", name);
        label.put("color", color);
        label.put("description", description);
        return label;
    }

    private void seedKnowledgebaseCategories() {
        if (knowledgebaseCategoryRepository.hasAny()) {
            return;
        }

        Instant now = clock.instant();
        List<KnowledgebaseCategory> categories = List.of(
            KnowledgebaseCategory.builder()
                .id(EntityIds.next())
                .name("Rules")
                .slug("rules-policies")
                .ordinal(0)
                .isVisible(true)
                .createdAt(now)
                .updatedAt(now)
                .build(),
            KnowledgebaseCategory.builder()
                .id(EntityIds.next())
                .name("Guides")
                .slug("guides-troubleshooting")
                .ordinal(1)
                .isVisible(true)
                .createdAt(now)
                .updatedAt(now)
                .build(),
            KnowledgebaseCategory.builder()
                .id(EntityIds.next())
                .name("News")
                .slug("news-updates")
                .ordinal(2)
                .isVisible(true)
                .createdAt(now)
                .updatedAt(now)
                .build()
        );

        for (KnowledgebaseCategory category : categories) {
            knowledgebaseCategoryRepository.insert(category);
        }
        seedStarterArticles(categories, now);
    }

    private void seedStarterArticles(List<KnowledgebaseCategory> categories, Instant now) {
        Map<String, String> categoryIdsBySlug = new LinkedHashMap<>();
        categories.forEach(category -> categoryIdsBySlug.put(category.getSlug(), category.getId()));
        Map<String, Integer> nextOrdinal = new LinkedHashMap<>();
        for (StarterArticle starter : StarterArticles.ALL) {
            String categoryId = categoryIdsBySlug.get(starter.categorySlug());
            if (categoryId == null) {
                continue;
            }
            int ordinal = nextOrdinal.merge(categoryId, 1, Integer::sum) - 1;
            knowledgebaseArticleRepository.insert(KnowledgebaseArticle.builder()
                .id(EntityIds.next())
                .title(starter.title())
                .slug(starter.slug())
                .content(starter.content())
                .categoryId(categoryId)
                .ordinal(ordinal)
                .isVisible(true)
                .starter(true)
                .createdAt(now)
                .updatedAt(now)
                .build());
        }
    }

    private void seedHomepageCards() {
        if (homepageCardRepository.hasAny()) {
            return;
        }

        List<KnowledgebaseCategory> categories = knowledgebaseCategoryRepository.findAllOrdered();

        String rulesCategoryId = categories.stream()
            .filter(c -> "rules-policies".equals(c.getSlug()))
            .findFirst().map(KnowledgebaseCategory::getId).orElse(null);
        String guidesCategoryId = categories.stream()
            .filter(c -> "guides-troubleshooting".equals(c.getSlug()))
            .findFirst().map(KnowledgebaseCategory::getId).orElse(null);
        String newsCategoryId = categories.stream()
            .filter(c -> "news-updates".equals(c.getSlug()))
            .findFirst().map(KnowledgebaseCategory::getId).orElse(null);

        Instant now = clock.instant();

        List<HomepageCard> cards = List.of(
            HomepageCard.builder()
                .id(EntityIds.next())
                .title("Appeal a punishment")
                .icon("Scale")
                .iconColor("#f59e0b")
                .actionType("url")
                .actionUrl("/appeal")
                .isEnabled(true)
                .ordinal(0)
                .createdAt(now)
                .updatedAt(now)
                .build(),
            HomepageCard.builder()
                .id(EntityIds.next())
                .title("Apply for staff")
                .icon("UserPlus")
                .iconColor("#3b82f6")
                .actionType("url")
                .actionUrl("/submit-ticket/apply")
                .isEnabled(true)
                .ordinal(1)
                .createdAt(now)
                .updatedAt(now)
                .build(),
            HomepageCard.builder()
                .id(EntityIds.next())
                .title("Contact staff")
                .icon("MessageCircle")
                .iconColor("#10b981")
                .actionType("url")
                .actionUrl("/submit-ticket/support")
                .isEnabled(true)
                .ordinal(2)
                .createdAt(now)
                .updatedAt(now)
                .build(),
            HomepageCard.builder()
                .id(EntityIds.next())
                .title("Rules")
                .icon("BookOpen")
                .iconColor("#8b5cf6")
                .actionType("category_dropdown")
                .categoryId(rulesCategoryId)
                .isEnabled(true)
                .ordinal(3)
                .createdAt(now)
                .updatedAt(now)
                .build(),
            HomepageCard.builder()
                .id(EntityIds.next())
                .title("Guides")
                .icon("HelpCircle")
                .iconColor("#f97316")
                .actionType("category_dropdown")
                .categoryId(guidesCategoryId)
                .isEnabled(true)
                .ordinal(4)
                .createdAt(now)
                .updatedAt(now)
                .build(),
            HomepageCard.builder()
                .id(EntityIds.next())
                .title("News")
                .icon("Newspaper")
                .iconColor("#6366f1")
                .actionType("category_dropdown")
                .categoryId(newsCategoryId)
                .isEnabled(true)
                .ordinal(5)
                .createdAt(now)
                .updatedAt(now)
                .build()
        );

        for (HomepageCard card : cards) {
            homepageCardRepository.insert(card);
        }
    }

}
