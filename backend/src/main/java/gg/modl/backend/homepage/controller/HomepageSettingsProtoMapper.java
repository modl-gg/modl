package gg.modl.backend.homepage.controller;

import static gg.modl.backend.infrastructure.proto.ProtoMapperSupport.nullToEmpty;
import static gg.modl.backend.settings.controller.SettingsMetaMapper.toSettingsMeta;

import gg.modl.backend.homepage.data.FooterLink;
import gg.modl.backend.homepage.data.FooterLinkKind;
import gg.modl.backend.homepage.data.HomepageCard;
import gg.modl.backend.homepage.data.HomepageCardAction;
import gg.modl.backend.homepage.data.HomepageCardEdit;
import gg.modl.backend.homepage.data.HomepageLayout;
import gg.modl.backend.homepage.data.PublishedHomepage;
import gg.modl.backend.homepage.service.HomepageCardService;
import gg.modl.backend.homepage.service.HomepageCardService.EnrichedCard;
import gg.modl.backend.settings.service.VersionedSettings;
import gg.modl.proto.modl.v1.HomepageCardActionType;
import gg.modl.proto.modl.v1.HomepageCardDraft;
import gg.modl.proto.modl.v1.HomepageFooterLink;
import gg.modl.proto.modl.v1.HomepageFooterLinkKind;
import gg.modl.proto.modl.v1.HomepageSettings;
import gg.modl.proto.modl.v1.HomepageSettingsEnvelope;
import gg.modl.proto.modl.v1.PublicHomepageResponse;
import java.util.List;

final class HomepageSettingsProtoMapper {
    private HomepageSettingsProtoMapper() {
    }

    static HomepageSettingsEnvelope toEnvelope(VersionedSettings<HomepageLayout> settings, List<EnrichedCard> cards) {
        HomepageSettings.Builder data = toProto(settings.data()).toBuilder();
        cards.forEach(card -> data.addCards(toProto(card)));
        return HomepageSettingsEnvelope.newBuilder()
            .setData(data)
            .setMeta(toSettingsMeta(settings))
            .build();
    }

    static List<HomepageCardEdit> cardsOf(HomepageSettings settings) {
        return settings.getCardsList().stream().map(HomepageSettingsProtoMapper::fromProto).toList();
    }

    private static HomepageCardDraft toProto(EnrichedCard enriched) {
        HomepageCard card = enriched.card();
        HomepageCardDraft.Builder draft = HomepageCardDraft.newBuilder()
            .setId(card.getId())
            .setTitle(card.getTitle())
            .setDescription(nullToEmpty(card.getDescription()))
            .setIcon(nullToEmpty(card.getIcon()))
            .setActionType(toProto(HomepageCardAction.ofStored(card.getActionType())))
            .setActionUrl(nullToEmpty(card.getActionUrl()))
            .setCategoryId(nullToEmpty(card.getCategoryId()))
            .setEnabled(card.isEnabled());
        HomepageCardService.EmbeddedCategory category = enriched.category();
        if (category != null) {
            draft.setCategoryName(category.name());
        }
        return draft.build();
    }

    private static HomepageCardEdit fromProto(HomepageCardDraft draft) {
        return new HomepageCardEdit(draft.getId(), draft.getTitle(), draft.getDescription(), draft.getIcon(), fromProto(draft.getActionType()),
            draft.getActionUrl(), draft.getCategoryId(), draft.getEnabled());
    }

    private static HomepageCardActionType toProto(HomepageCardAction action) {
        return switch (action) {
            case LINK -> HomepageCardActionType.HOMEPAGE_CARD_ACTION_TYPE_LINK;
            case CATEGORY -> HomepageCardActionType.HOMEPAGE_CARD_ACTION_TYPE_CATEGORY;
        };
    }

    private static HomepageCardAction fromProto(HomepageCardActionType action) {
        return switch (action) {
            case HOMEPAGE_CARD_ACTION_TYPE_CATEGORY -> HomepageCardAction.CATEGORY;
            case HOMEPAGE_CARD_ACTION_TYPE_LINK, HOMEPAGE_CARD_ACTION_TYPE_UNSPECIFIED, UNRECOGNIZED -> HomepageCardAction.LINK;
        };
    }

    static PublicHomepageResponse toPublicResponse(PublishedHomepage published) {
        HomepageLayout settings = published.settings();
        PublicHomepageResponse.Builder response = PublicHomepageResponse.newBuilder()
            .setTagline(settings.tagline())
            .setServerAddress(settings.serverAddress());
        settings.footerLinks().forEach(link -> response.addFooterLinks(toProto(link)));
        published.onlinePlayers().ifPresent(response::setOnlinePlayers);
        return response.build();
    }

    static HomepageLayout layoutOf(HomepageSettings settings) {
        return new HomepageLayout(
            settings.getTagline(),
            settings.getServerAddress(),
            settings.getShowPlayerCount(),
            settings.getFooterLinksList().stream().map(HomepageSettingsProtoMapper::fromProto).toList());
    }

    private static HomepageSettings toProto(HomepageLayout settings) {
        HomepageSettings.Builder proto = HomepageSettings.newBuilder()
            .setTagline(settings.tagline())
            .setServerAddress(settings.serverAddress())
            .setShowPlayerCount(settings.showPlayerCount());
        settings.footerLinks().forEach(link -> proto.addFooterLinks(toProto(link)));
        return proto.build();
    }

    private static HomepageFooterLink toProto(FooterLink link) {
        return HomepageFooterLink.newBuilder()
            .setLabel(link.label())
            .setUrl(link.url())
            .setKind(toProto(link.kind()))
            .build();
    }

    private static FooterLink fromProto(HomepageFooterLink link) {
        return new FooterLink(link.getLabel(), link.getUrl(), fromProto(link.getKind()));
    }

    private static HomepageFooterLinkKind toProto(FooterLinkKind kind) {
        return switch (kind) {
            case DISCORD -> HomepageFooterLinkKind.HOMEPAGE_FOOTER_LINK_KIND_DISCORD;
            case STORE -> HomepageFooterLinkKind.HOMEPAGE_FOOTER_LINK_KIND_STORE;
            case WEBSITE -> HomepageFooterLinkKind.HOMEPAGE_FOOTER_LINK_KIND_WEBSITE;
            case VOTE -> HomepageFooterLinkKind.HOMEPAGE_FOOTER_LINK_KIND_VOTE;
            case OTHER -> HomepageFooterLinkKind.HOMEPAGE_FOOTER_LINK_KIND_OTHER;
        };
    }

    private static FooterLinkKind fromProto(HomepageFooterLinkKind kind) {
        return switch (kind) {
            case HOMEPAGE_FOOTER_LINK_KIND_DISCORD -> FooterLinkKind.DISCORD;
            case HOMEPAGE_FOOTER_LINK_KIND_STORE -> FooterLinkKind.STORE;
            case HOMEPAGE_FOOTER_LINK_KIND_WEBSITE -> FooterLinkKind.WEBSITE;
            case HOMEPAGE_FOOTER_LINK_KIND_VOTE -> FooterLinkKind.VOTE;
            case HOMEPAGE_FOOTER_LINK_KIND_OTHER, HOMEPAGE_FOOTER_LINK_KIND_UNSPECIFIED, UNRECOGNIZED -> FooterLinkKind.OTHER;
        };
    }
}
