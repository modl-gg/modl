package gg.modl.backend.homepage.controller;

import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.homepage.data.FooterLink;
import gg.modl.backend.homepage.data.FooterLinkKind;
import gg.modl.backend.homepage.data.HomepageCardAction;
import gg.modl.backend.homepage.data.HomepageCardEdit;
import gg.modl.backend.homepage.data.HomepageLayout;
import gg.modl.backend.homepage.data.PublishedHomepage;
import gg.modl.proto.modl.v1.HomepageCardActionType;
import gg.modl.proto.modl.v1.HomepageCardDraft;
import gg.modl.proto.modl.v1.HomepageFooterLinkKind;
import gg.modl.proto.modl.v1.HomepageSettings;
import gg.modl.proto.modl.v1.PublicHomepageResponse;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class HomepageSettingsProtoMapperTest {
    private static final HomepageLayout SETTINGS = new HomepageLayout("Since 2012", "play.example.net", true,
        List.of(new FooterLink("Store", "https://store.example.net", FooterLinkKind.STORE)));

    @Test
    void thePublicResponseCarriesThePlayerCountOnlyWhenPublished() {
        PublicHomepageResponse shown = HomepageSettingsProtoMapper.toPublicResponse(new PublishedHomepage(SETTINGS, OptionalInt.of(7)));
        PublicHomepageResponse hidden = HomepageSettingsProtoMapper.toPublicResponse(new PublishedHomepage(SETTINGS, OptionalInt.empty()));

        assertThat(shown.hasOnlinePlayers()).isTrue();
        assertThat(shown.getOnlinePlayers()).isEqualTo(7);
        assertThat(hidden.hasOnlinePlayers()).isFalse();
        assertThat(hidden.getTagline()).isEqualTo("Since 2012");
        assertThat(hidden.getServerAddress()).isEqualTo("play.example.net");
        assertThat(hidden.getFooterLinksList()).singleElement().satisfies(link -> {
            assertThat(link.getLabel()).isEqualTo("Store");
            assertThat(link.getUrl()).isEqualTo("https://store.example.net");
            assertThat(link.getKind()).isEqualTo(HomepageFooterLinkKind.HOMEPAGE_FOOTER_LINK_KIND_STORE);
        });
    }

    @Test
    void everyLinkKindSurvivesTheRoundTrip() {
        List<FooterLink> links = List.of(FooterLinkKind.values()).stream()
            .map(kind -> new FooterLink(kind.name(), "https://example.net", kind))
            .toList();
        HomepageLayout settings = new HomepageLayout("", "", false, links);

        HomepageSettings wire = HomepageSettings.newBuilder()
            .addAllFooterLinks(HomepageSettingsProtoMapper.toPublicResponse(new PublishedHomepage(settings, OptionalInt.empty()))
                .getFooterLinksList())
            .build();

        assertThat(HomepageSettingsProtoMapper.layoutOf(wire)).isEqualTo(settings);
    }

    @Test
    void cardDraftsKeepTheirIdsAndMapTheActionType() {
        HomepageSettings wire = HomepageSettings.newBuilder()
            .addCards(HomepageCardDraft.newBuilder().setId("c1").setTitle("Rules")
                .setActionType(HomepageCardActionType.HOMEPAGE_CARD_ACTION_TYPE_CATEGORY).setCategoryId("k1").setEnabled(true))
            .addCards(HomepageCardDraft.newBuilder().setTitle("Store").setActionUrl("https://store.example.net")
                .setActionType(HomepageCardActionType.HOMEPAGE_CARD_ACTION_TYPE_LINK))
            .build();

        assertThat(HomepageSettingsProtoMapper.cardsOf(wire)).containsExactly(
            new HomepageCardEdit("c1", "Rules", "", "", HomepageCardAction.CATEGORY, "", "k1", true),
            new HomepageCardEdit(null, "Store", "", "", HomepageCardAction.LINK, "https://store.example.net", "", false));
    }
}
