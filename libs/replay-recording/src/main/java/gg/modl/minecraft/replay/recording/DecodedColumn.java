package gg.modl.minecraft.replay.recording;

import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;

final class DecodedColumn {
    private final int chunkX;
    private final int chunkZ;
    private final int minSection;
    private final int sectionCount;
    private final Map<Integer, DecodedSection> sections = new TreeMap<>();

    DecodedColumn(int chunkX, int chunkZ, int minSection, int sectionCount) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.minSection = minSection;
        this.sectionCount = sectionCount;
    }

    int chunkX() {
        return chunkX;
    }

    int chunkZ() {
        return chunkZ;
    }

    int minSection() {
        return minSection;
    }

    int sectionCount() {
        return sectionCount;
    }

    void put(DecodedSection section) {
        sections.put(section.getSectionY(), section);
    }

    @Nullable DecodedSection section(int sectionY) {
        return sections.get(sectionY);
    }

    DecodedSection sectionOrAir(int sectionY) {
        DecodedSection section = sections.get(sectionY);
        if (section == null) {
            section = new DecodedSection(sectionY, null);
            sections.put(sectionY, section);
        }
        return section;
    }

    void merge(DecodedColumn partial) {
        for (DecodedSection section : partial.sections()) put(section);
    }

    Collection<DecodedSection> sections() {
        return sections.values();
    }

    void set(int x, int y, int z, int state) {
        int sectionY = y >> 4;
        if (sectionY < minSection || sectionY >= minSection + sectionCount) return;
        sectionOrAir(sectionY).set(DecodedSection.index(x & 15, y & 15, z & 15), state);
    }
}
