package gg.modl.backend.update.commit;

import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.database.sql.DatabaseWorkExecutor;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

class StartupWorkWaitsForCommitTest {
    private static final String ROOT_PACKAGE = "gg.modl.backend";
    private static final Set<Class<?>> MAY_RUN_ON_READY = Set.of(UpdateCommit.class);

    @Test
    void onlyTheCommitListensForTheReadyEvent() throws ClassNotFoundException {
        List<String> early = new ArrayList<>();
        for (Class<?> type : backendClasses()) {
            for (Method method : type.getDeclaredMethods()) {
                if (listensForReady(method) && !MAY_RUN_ON_READY.contains(type)) {
                    early.add(type.getName() + "#" + method.getName());
                }
            }
        }

        assertThat(early).as("startup work must listen for InstanceCommittedEvent").isEmpty();
    }

    @Test
    void scheduledDatabaseWorkGoesThroughScheduledDatabaseJobs() throws ClassNotFoundException {
        List<String> direct = new ArrayList<>();
        for (Class<?> type : backendClasses()) {
            if (hasScheduledMethod(type) && takesWorkExecutor(type)) {
                direct.add(type.getName());
            }
        }

        assertThat(direct).isEmpty();
    }

    private static boolean listensForReady(Method method) {
        EventListener listener = method.getAnnotation(EventListener.class);
        if (listener == null) {
            return false;
        }
        boolean declared = Arrays.asList(listener.value()).contains(ApplicationReadyEvent.class)
            || Arrays.asList(listener.classes()).contains(ApplicationReadyEvent.class);
        boolean parameter = method.getParameterCount() == 1 && method.getParameterTypes()[0] == ApplicationReadyEvent.class;
        return declared || parameter;
    }

    private static boolean hasScheduledMethod(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods()).anyMatch(method -> method.isAnnotationPresent(Scheduled.class));
    }

    private static boolean takesWorkExecutor(Class<?> type) {
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (Arrays.asList(constructor.getParameterTypes()).contains(DatabaseWorkExecutor.class)) {
                return true;
            }
        }
        return false;
    }

    private static List<Class<?>> backendClasses() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) {
                return true;
            }
        };
        scanner.addIncludeFilter((reader, factory) -> !reader.getClassMetadata().getClassName().contains("Test"));
        List<Class<?>> classes = new ArrayList<>();
        ClassLoader loader = StartupWorkWaitsForCommitTest.class.getClassLoader();
        for (BeanDefinition candidate : scanner.findCandidateComponents(ROOT_PACKAGE)) {
            classes.add(Class.forName(candidate.getBeanClassName(), false, loader));
        }
        return classes;
    }
}
