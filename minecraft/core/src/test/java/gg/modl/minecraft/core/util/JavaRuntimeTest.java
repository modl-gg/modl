package gg.modl.minecraft.core.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JavaRuntimeTest {
    private final ClassLoader loader = JavaRuntimeTest.class.getClassLoader();

    @Test
    void parsesLegacyAndModernSpecificationVersions() {
        assertEquals(8, JavaRuntime.featureVersion("1.8"));
        assertEquals(17, JavaRuntime.featureVersion("17"));
        assertEquals(21, JavaRuntime.featureVersion("21"));
        assertEquals(25, JavaRuntime.featureVersion("25.0"));
        assertEquals(0, JavaRuntime.featureVersion("unknown"));
        assertEquals(0, JavaRuntime.featureVersion(null));
    }

    @Test
    void readsTheJavaVersionAClassFileNeedsWithoutLoadingIt() {
        assertEquals(8, JavaRuntime.featureVersionRequiredBy(loader, JavaRuntime.class.getName()));
    }

    @Test
    void aClassThatIsNotOnTheClassPathIsAPackagingError() {
        assertThrows(IllegalStateException.class,
                () -> JavaRuntime.featureVersionRequiredBy(loader, "gg.modl.minecraft.core.util.Missing"));
    }
}
