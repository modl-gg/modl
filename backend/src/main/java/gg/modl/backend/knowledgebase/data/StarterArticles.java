package gg.modl.backend.knowledgebase.data;

import java.util.List;

public final class StarterArticles {
    public record StarterArticle(String categorySlug, String title, String slug, String content) {
    }

    public static final List<StarterArticle> ALL = List.of(
        new StarterArticle("rules-policies", "Server rules", "server-rules", """
            These are the rules of our server. Breaking them can lead to a warning, a mute or a ban.

            1. Be respectful. No harassment, hate speech or targeting other players.
            2. No cheating. Hacked clients, x-ray and exploits are not allowed.
            3. No griefing or stealing from other players.
            4. No spam or advertising other servers.
            5. Keep usernames and skins appropriate.
            6. Follow staff instructions.

            Staff may act on behavior that is not listed here when it harms the community.
            """),
        new StarterArticle("guides-troubleshooting", "How to appeal a punishment", "how-to-appeal", """
            If you think a punishment was a mistake, you can appeal it.

            1. Open the Appeal page from the homepage.
            2. Enter the punishment ID shown when you were banned or muted.
            3. Explain what happened and why the punishment should be removed.

            Staff will review your appeal and reply on the appeal page. Please submit one appeal per punishment.
            """),
        new StarterArticle("guides-troubleshooting", "How to report a player", "how-to-report", """
            You can report players who break the rules.

            - In game, use the report command on the player and choose a reason.
            - For chat, use the chat report command. Their recent chat is attached automatically.
            - On this site, open a ticket from the homepage.

            Staff will review every report.
            """),
        new StarterArticle("news-updates", "Welcome", "welcome", """
            Welcome to our server's help center. Here you can read the rules, find guides, appeal punishments and contact staff.
            """)
    );

    private StarterArticles() {
    }
}
