package gg.modl.minecraft.api;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PunishmentStaffNotesTest {
    @Test
    void aFirstNoteThatRepeatsTheReasonIsNotAStaffNote() {
        Punishment punishment = withNotes("Cheating", "Cheating", "informant is Alex");

        assertEquals(Collections.singletonList("informant is Alex"), texts(punishment.getStaffNotes()));
    }

    @Test
    void otherNotesStayStaffNotes() {
        assertEquals(Arrays.asList("informant is Alex", "Cheating"),
                texts(withNotes("Cheating", "informant is Alex", "Cheating").getStaffNotes()));
        assertEquals(Collections.singletonList("Cheating"), texts(withNotes(null, "Cheating").getStaffNotes()));
    }

    private static Punishment withNotes(String reason, String... notes) {
        List<Note> stored = Arrays.stream(notes).map(text -> new Note(text, null, "Mod", null, null)).collect(Collectors.toList());
        return Punishment.builder().id("P").typeOrdinal(2).notes(stored)
                .dataMap(reason == null ? Collections.emptyMap() : Collections.singletonMap("reason", (Object) reason))
                .build();
    }

    private static List<String> texts(List<Note> notes) {
        return notes.stream().map(Note::getText).collect(Collectors.toList());
    }
}
