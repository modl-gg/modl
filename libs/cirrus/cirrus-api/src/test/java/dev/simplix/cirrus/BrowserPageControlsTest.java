package dev.simplix.cirrus;

import dev.simplix.cirrus.item.CirrusItem;
import dev.simplix.cirrus.item.CirrusItemType;
import dev.simplix.cirrus.menu.CirrusInventoryType;
import dev.simplix.cirrus.menus.AbstractBrowser;
import dev.simplix.cirrus.model.Click;
import dev.simplix.cirrus.schematic.MenuSchematic;
import dev.simplix.cirrus.text.CirrusChatElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(CirrusTestExtension.class)
public class BrowserPageControlsTest {
    private static final int PREVIOUS = 45;
    private static final int NEXT = 53;

    @Test
    public void aSinglePageHasNoPageNumberAndNoPageControls() throws Exception {
        List<MenuSchematic> pages = build(new PagedBrowser(10));

        assertEquals(1, pages.size());
        assertEquals("Reports", pages.get(0).title());
        assertFalse(pages.get(0).element(PREVIOUS).item().isPresent());
        assertFalse(pages.get(0).element(NEXT).item().isPresent());
    }

    @Test
    public void eachPageShowsOnlyTheControlsThatLeadSomewhere() throws Exception {
        List<MenuSchematic> pages = build(new PagedBrowser(100));

        assertEquals(3, pages.size());
        assertEquals("Reports (1/3)", pages.get(0).title());
        assertFalse(pages.get(0).element(PREVIOUS).item().isPresent());
        assertTrue(pages.get(0).element(NEXT).item().isPresent());
        assertTrue(pages.get(1).element(PREVIOUS).item().isPresent());
        assertTrue(pages.get(1).element(NEXT).item().isPresent());
        assertTrue(pages.get(2).element(PREVIOUS).item().isPresent());
        assertFalse(pages.get(2).element(NEXT).item().isPresent());
    }

    private static List<MenuSchematic> build(PagedBrowser browser) throws Exception {
        Method build = AbstractBrowser.class.getDeclaredMethod("build");
        build.setAccessible(true);
        build.invoke(browser);
        Field pages = AbstractBrowser.class.getDeclaredField("pages");
        pages.setAccessible(true);
        List<MenuSchematic> built = new ArrayList<>();
        for (Object page : (List<?>) pages.get(browser)) {
            built.add((MenuSchematic) page);
        }
        return built;
    }

    private static final class PagedBrowser extends AbstractBrowser<String> {
        private final int count;

        private PagedBrowser(int count) {
            this.count = count;
            title("Reports");
            fixedSize(CirrusInventoryType.GENERIC_9X6);
        }

        @Override
        protected void handleClick(Click click, String value) {
        }

        @Override
        protected Collection<String> elements() {
            List<String> elements = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                elements.add(Integer.toString(i));
            }
            return elements;
        }

        @Override
        protected CirrusItem map(String element) {
            return CirrusItem.of(CirrusItemType.STONE, CirrusChatElement.ofLegacyText(element));
        }

        @Override
        protected Map<Integer, CirrusItem> intercept(int menuSize) {
            Map<Integer, CirrusItem> bottomRow = new HashMap<>();
            for (int slot = menuSize - 9; slot < menuSize; slot++) {
                bottomRow.put(slot, null);
            }
            return bottomRow;
        }

        @Override
        protected Map<Integer, CirrusItem> pageControls(int pageIndex, int pageTotal) {
            Map<Integer, CirrusItem> controls = new HashMap<>();
            if (pageIndex > 0) {
                controls.put(PREVIOUS, CirrusItem.of(CirrusItemType.ARROW, CirrusChatElement.ofLegacyText("previous")));
            }
            if (pageIndex < pageTotal - 1) {
                controls.put(NEXT, CirrusItem.of(CirrusItemType.ARROW, CirrusChatElement.ofLegacyText("next")));
            }
            return controls;
        }
    }
}
