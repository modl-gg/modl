package gg.modl.backend.theme.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gg.modl.backend.theme.data.CustomStylesheet;
import gg.modl.backend.theme.service.CustomCssService;
import gg.modl.backend.theme.service.CustomCssService.PublishedStylesheet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PublicThemeStylesheetControllerTest {
    private static final String PATH = "/v1/public/theme.css";
    private static final String CSS = "[data-modl=\"card\"]{color:red}";
    private static final String REVISION = new CustomStylesheet(CSS).revision();

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        CustomCssService stylesheets = mock(CustomCssService.class);
        when(stylesheets.published()).thenReturn(new PublishedStylesheet(new CustomStylesheet(CSS), REVISION));
        mvc = MockMvcBuilders.standaloneSetup(new PublicThemeStylesheetController(stylesheets)).build();
    }

    @Test
    void theCurrentRevisionIsServedAsAnImmutableStylesheet() throws Exception {
        mvc.perform(get(PATH).param("v", REVISION))
            .andExpect(status().isOk())
            .andExpect(content().contentType("text/css;charset=UTF-8"))
            .andExpect(content().string(CSS))
            .andExpect(header().string("ETag", "\"" + REVISION + "\""))
            .andExpect(header().string("Cache-Control", "max-age=31536000, public, immutable"));
    }

    @Test
    void anyOtherRequestRevalidates() throws Exception {
        mvc.perform(get(PATH).param("v", "0123456789abcdef"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-cache"));
        mvc.perform(get(PATH))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-cache"));
    }

    @Test
    void aMatchingEtagAnswersNotModified() throws Exception {
        mvc.perform(get(PATH).header("If-None-Match", "\"" + REVISION + "\""))
            .andExpect(status().isNotModified())
            .andExpect(content().string(""));
    }
}
