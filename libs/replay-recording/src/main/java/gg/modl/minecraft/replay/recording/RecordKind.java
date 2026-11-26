package gg.modl.minecraft.replay.recording;

final class RecordKind {
    static final int ENTITY_SPAWN = 1;
    static final int ENTITY_REMOVE = 2;
    static final int ENTITY_MOVE = 3;
    static final int ENTITY_TELEPORT = 4;
    static final int ENTITY_HEAD = 5;
    static final int ENTITY_METADATA = 6;
    static final int ENTITY_EQUIPMENT = 7;
    static final int ENTITY_ANIMATION = 8;
    static final int ENTITY_HURT = 9;
    static final int ENTITY_DEATH = 10;
    static final int ENTITY_EFFECT = 11;
    static final int ENTITY_EFFECT_REMOVE = 12;
    static final int ENTITY_PASSENGERS = 13;
    static final int ITEM_PICKUP = 14;
    static final int BLOCK_CHANGE = 15;
    static final int BLOCK_BREAK_PROGRESS = 16;
    static final int PLAYER_INFO = 17;
    static final int PLAYER_INFO_REMOVE = 18;
    static final int CHAT = 19;
    static final int TARGET_HEALTH = 20;
    static final int TARGET_ATTRIBUTES = 21;
    static final int TARGET_EXPERIENCE = 22;
    static final int TARGET_SLOT = 23;
    static final int TARGET_INVENTORY = 24;
    static final int TARGET_GAME_MODE = 25;
    static final int WORLD_SEGMENT = 26;
    static final int WORLD_TIME = 27;
    static final int WEATHER = 28;
    static final int TARGET_MOVE = 29;

    private RecordKind() {
    }
}
