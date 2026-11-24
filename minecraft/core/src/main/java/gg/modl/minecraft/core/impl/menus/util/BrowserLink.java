package gg.modl.minecraft.core.impl.menus.util;

import gg.modl.minecraft.core.PluginServices;
import gg.modl.minecraft.core.locale.LocaleManager;
import gg.modl.minecraft.core.util.ClickableJsonMessage;

import static gg.modl.minecraft.core.util.Java8Collections.mapOf;

public final class BrowserLink {
    private BrowserLink() {}

    public static String json(String label, String url) {
        LocaleManager locale = PluginServices.locale();
        return ClickableJsonMessage.empty()
                .extra(ClickableJsonMessage.text(label).color("gold"))
                .extra(ClickableJsonMessage.text(url)
                        .color("aqua")
                        .underlined(true)
                        .openUrl(url)
                        .hoverText(locale.getMessage("menus.common.open_in_browser")))
                .toJson();
    }

    public static String ticketJson(String ticketId, String ticketUrl) {
        return json(PluginServices.locale().getMessageWithRawValues("menus.common.ticket_link_label", mapOf("id", ticketId)),
                ticketUrl);
    }
}
