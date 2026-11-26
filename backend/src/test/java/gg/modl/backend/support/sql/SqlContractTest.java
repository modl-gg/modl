package gg.modl.backend.support.sql;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Tag(SqlContractTest.TAG)
@ParameterizedTest(name = "{0}")
@MethodSource("gg.modl.backend.support.sql.TestDialects#availableStream")
public @interface SqlContractTest {
    String TAG = "sql";
}
