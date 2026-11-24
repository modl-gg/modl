package gg.modl.minecraft.api.http.response;

import gg.modl.minecraft.api.Note;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter @NoArgsConstructor
public class PaginatedNotesResponse extends PaginatedResponse<Note> {
    private List<Note> notes;

    public PaginatedNotesResponse(List<Note> notes, int totalCount, int page, boolean hasMore, int status) {
        super(totalCount, page, hasMore, status);
        this.notes = notes;
    }

    @Override
    public List<Note> getItems() {
        return notes;
    }
}
