package gg.modl.backend.hostedimport.controller;

import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.hostedimport.model.IssueCode;
import gg.modl.proto.modl.v1.HostedImportIssueCode;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class HostedImportIssueCodeContractTest {
    @Test
    void everyIssueCodeHasExactlyOneWireCode() {
        assertThat(Arrays.stream(IssueCode.values()).map(HostedImportProtoMapper::toProto))
            .doesNotHaveDuplicates()
            .containsExactlyInAnyOrderElementsOf(Arrays.stream(HostedImportIssueCode.values())
                .filter(code -> code != HostedImportIssueCode.HOSTED_IMPORT_ISSUE_CODE_UNSPECIFIED
                    && code != HostedImportIssueCode.UNRECOGNIZED)
                .toList());
    }
}
