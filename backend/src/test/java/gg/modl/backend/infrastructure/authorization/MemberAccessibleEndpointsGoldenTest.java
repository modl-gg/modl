package gg.modl.backend.infrastructure.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

class MemberAccessibleEndpointsGoldenTest {
    private static final String GOLDEN = "/golden/member-endpoints.txt";

    @Test
    void theEndpointsOpenToBehindProxyKeysArePinned() throws IOException, ClassNotFoundException {
        assertThat(memberAccessibleEndpoints()).containsExactlyElementsOf(golden());
    }

    private static List<String> memberAccessibleEndpoints() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<String> endpoints = new ArrayList<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("gg.modl.backend")) {
            Class<?> controller = Class.forName(candidate.getBeanClassName());
            RequestMapping prefix = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
            for (Method method : controller.getDeclaredMethods()) {
                if (!method.isAnnotationPresent(MemberAccessible.class)) continue;
                RequestMapping mapping = Objects.requireNonNull(AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class));
                String path = firstOf(prefix == null ? null : prefix.path()) + firstOf(mapping.path());
                endpoints.add(mapping.method()[0] + " " + path);
            }
        }
        return endpoints.stream().sorted().toList();
    }

    private static String firstOf(String[] paths) {
        return paths == null || paths.length == 0 ? "" : paths[0];
    }

    private static List<String> golden() throws IOException {
        try (InputStream input = MemberAccessibleEndpointsGoldenTest.class.getResourceAsStream(GOLDEN)) {
            return new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8).lines()
                .filter(line -> !line.isBlank())
                .sorted()
                .toList();
        }
    }
}
