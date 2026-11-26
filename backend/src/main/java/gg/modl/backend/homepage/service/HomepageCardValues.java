package gg.modl.backend.homepage.service;

import gg.modl.backend.homepage.data.HomepageCardAction;
import gg.modl.backend.homepage.data.HomepageCardEdit;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.infrastructure.validation.SafeUrls;
import java.util.List;

final class HomepageCardValues {
    static final String CARD_INVALID_CODE = "HOMEPAGE_CARD_INVALID";

    private static final String TITLE_MISSING_MESSAGE = "Every homepage card needs a title";
    private static final String LINK_INVALID_MESSAGE = "A link card needs an http or https address or a path such as /appeal";
    private static final String CATEGORY_MISSING_MESSAGE = "A category card needs a knowledgebase category";

    private HomepageCardValues() {
    }

    static List<HomepageCardEdit> validated(List<HomepageCardEdit> cards) {
        return cards.stream().map(HomepageCardValues::validated).toList();
    }

    private static HomepageCardEdit validated(HomepageCardEdit card) {
        String title = card.title().strip();
        if (title.isEmpty()) {
            throw new ValidationException(TITLE_MISSING_MESSAGE, CARD_INVALID_CODE);
        }
        return card.action() == HomepageCardAction.CATEGORY ? categoryCard(card, title) : linkCard(card, title);
    }

    private static HomepageCardEdit linkCard(HomepageCardEdit card, String title) {
        String url = card.actionUrl().strip();
        if (url.isEmpty() || !SafeUrls.isSafe(url)) {
            throw new ValidationException(LINK_INVALID_MESSAGE, CARD_INVALID_CODE);
        }
        return new HomepageCardEdit(card.id(), title, card.description().strip(), card.icon().strip(), HomepageCardAction.LINK, url,
            "", card.enabled());
    }

    private static HomepageCardEdit categoryCard(HomepageCardEdit card, String title) {
        String categoryId = card.categoryId().strip();
        if (categoryId.isEmpty()) {
            throw new ValidationException(CATEGORY_MISSING_MESSAGE, CARD_INVALID_CODE);
        }
        return new HomepageCardEdit(card.id(), title, card.description().strip(), card.icon().strip(), HomepageCardAction.CATEGORY, "",
            categoryId, card.enabled());
    }
}
