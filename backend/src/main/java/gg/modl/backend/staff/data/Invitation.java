package gg.modl.backend.staff.data;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Invitation {
    private String id;

    private String email;

    private String roleId;

    private String tokenHash;

    private Instant expiresAt;

    private Instant createdAt;

    private Instant updatedAt;
}
