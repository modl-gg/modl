package gg.modl.minecraft.spigot.features.handler.belongings;

import gg.modl.minecraft.features.staffmode.custody.BelongingsException;
import lombok.Value;
import org.bukkit.entity.Player;

@Value
class Vitals {
    private static final int FIELD_COUNT = 7;

    int totalExperience;
    int level;
    float experience;
    double health;
    int foodLevel;
    float saturation;
    float exhaustion;

    static Vitals of(Player player) {
        return new Vitals(Math.max(0, player.getTotalExperience()), Math.max(0, player.getLevel()),
                experienceBar(player.getExp()), player.getHealth(),
                player.getFoodLevel(), player.getSaturation(), player.getExhaustion());
    }

    boolean hasSameExperience(Player player) {
        Vitals current = of(player);
        return totalExperience == current.totalExperience && level == current.level
                && Float.compare(experience, current.experience) == 0;
    }

    private static float experienceBar(float progress) {
        return Math.min(1f, Math.max(0f, progress));
    }

    void applyTo(Player player) {
        player.setTotalExperience(totalExperience);
        player.setLevel(level);
        player.setExp(experience);
        player.setHealth(Math.min(health, player.getMaxHealth()));
        player.setFoodLevel(foodLevel);
        player.setSaturation(saturation);
        player.setExhaustion(exhaustion);
    }

    String write() {
        return totalExperience + " " + level + " " + experience + " " + health + " " + foodLevel + " " + saturation
                + " " + exhaustion;
    }

    static Vitals read(String text) throws BelongingsException {
        String[] fields = text.split(" ");
        if (fields.length != FIELD_COUNT) throw new BelongingsException("The saved player state is malformed");
        try {
            Vitals vitals = new Vitals(Integer.parseInt(fields[0]), Integer.parseInt(fields[1]),
                    Float.parseFloat(fields[2]), Double.parseDouble(fields[3]), Integer.parseInt(fields[4]),
                    Float.parseFloat(fields[5]), Float.parseFloat(fields[6]));
            return vitals.validated();
        } catch (NumberFormatException failure) {
            throw ReflectiveCalls.failed("read the saved player state", failure);
        }
    }

    Vitals validated() throws BelongingsException {
        if (!isValid()) throw new BelongingsException("The player state is out of range");
        return this;
    }

    private boolean isValid() {
        return totalExperience >= 0 && level >= 0 && experience >= 0f && experience <= 1f
                && health > 0 && Double.isFinite(health)
                && Float.isFinite(saturation) && Float.isFinite(exhaustion);
    }
}
