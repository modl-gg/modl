package gg.modl.minecraft.api.http.response;

import gg.modl.minecraft.api.Punishment;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter @NoArgsConstructor
public class PaginatedPunishmentsResponse extends PaginatedResponse<Punishment> {
    private List<Punishment> punishments;

    public PaginatedPunishmentsResponse(List<Punishment> punishments, int totalCount, int page, boolean hasMore, int status) {
        super(totalCount, page, hasMore, status);
        this.punishments = punishments;
    }

    @Override
    public List<Punishment> getItems() {
        return punishments;
    }
}
