import React, { useRef, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { getItemUV, formatItemName, itemColor, type AtlasInfo } from './atlas-items';
import { computeVitalUnits } from './vitals';
import { activeEffectsAt, effectColor, effectLabelKey, effectLevel, remainingEffectTicks, type ActiveEffect } from './effects';
import { formatTicks } from './format';

export interface PlayerSummary {
  uuid: string;
  playerName: string;
  effects: readonly ActiveEffect[];
}

interface InventorySlot {
  itemName: string;
  count: number;
  enchantments?: { id: string; level: number }[];
}

const ARMOR_SLOTS = [
  { slot: 39, labelKey: 'replay.players.slotHead' },
  { slot: 38, labelKey: 'replay.players.slotChest' },
  { slot: 37, labelKey: 'replay.players.slotLegs' },
  { slot: 36, labelKey: 'replay.players.slotFeet' },
] as const;

const OFFHAND_SLOT = 40;
const EMPTY_VITAL_COLOR = '#333333';
const HEALTH_COLOR = '#cc3333';
const FOOD_COLOR = '#b87333';

// Compact slot for sidebar (27px to fit 9 across in 256px)
const COMPACT_SLOT = 27;
const COMPACT_ICON = 22;

const CompactItemIcon: React.FC<{ itemName: string; atlas: AtlasInfo }> = ({ itemName, atlas }) => {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    const uv = getItemUV(itemName, atlas);
    if (!uv) { ctx.clearRect(0, 0, COMPACT_ICON, COMPACT_ICON); return; }
    const img = atlas.atlasImage;
    ctx.imageSmoothingEnabled = false;
    ctx.clearRect(0, 0, COMPACT_ICON, COMPACT_ICON);
    ctx.drawImage(img, uv.u * img.width, uv.v * img.height, uv.su * img.width, uv.sv * img.height, 0, 0, COMPACT_ICON, COMPACT_ICON);
  }, [itemName, atlas]);
  return <canvas ref={canvasRef} width={COMPACT_ICON} height={COMPACT_ICON} style={{ width: COMPACT_ICON, height: COMPACT_ICON, imageRendering: 'pixelated' }} />;
};

const CompactSlotCell: React.FC<{
  slot: InventorySlot | undefined;
  label?: string;
  atlas?: AtlasInfo | null | undefined;
}> = ({ slot, label, atlas }) => {
  const { t } = useTranslation();
  const hasIcon = slot && atlas && getItemUV(slot.itemName, atlas);
  const title = slot
    ? t('replay.players.itemCount', { item: formatItemName(slot.itemName), amount: slot.count })
    : label ?? t('replay.players.emptySlot');
  return (
    <div
      style={{
        width: COMPACT_SLOT,
        height: COMPACT_SLOT,
        backgroundColor: slot
          ? (hasIcon ? 'rgba(80, 80, 80, 0.5)' : itemColor(slot.itemName))
          : 'rgba(139, 139, 139, 0.2)',
        border: '1px solid rgba(255,255,255,0.1)',
        borderRadius: 2,
        position: 'relative',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
      }}
      title={title}
    >
      {slot && (
        <>
          {hasIcon ? (
            <CompactItemIcon itemName={slot.itemName} atlas={atlas} />
          ) : (
            <span style={{ fontSize: 6, color: 'white', textAlign: 'center', overflow: 'hidden', maxWidth: COMPACT_SLOT - 4, lineHeight: '8px' }}>
              {slot.itemName.split('_').pop()}
            </span>
          )}
          {slot.count > 1 && (
            <span style={{ position: 'absolute', bottom: 0, right: 1, fontSize: 7, color: 'white', fontWeight: 'bold', textShadow: '1px 1px 1px rgba(0,0,0,0.8)' }}>
              {slot.count}
            </span>
          )}
        </>
      )}
      {!slot && label && (
        <span style={{ fontSize: 6, color: 'rgba(255,255,255,0.3)' }}>{label}</span>
      )}
    </div>
  );
};

export interface PlayerState {
  uuid: string;
  playerName: string;
  health: number;
  food: number;
  saturation: number;
  effects: readonly ActiveEffect[];
  equipment: Map<number, { itemName: string; enchantments?: { id: string; level: number }[] }>;
  inventory: Map<number, InventorySlot>;
}

interface PlayerSidebarProps {
  players: readonly PlayerSummary[];
  followingPlayer: string | null;
  expandedPlayer: string | null;
  onFollowPlayer: (uuid: string | null) => void;
  onExpandPlayer: (uuid: string | null) => void;
  currentTimeMs?: number;
  visible?: boolean;
  selectedPlayerState?: PlayerState | null;
  atlas?: AtlasInfo | null | undefined;
}

const VitalBar: React.FC<{ value: number; label: string; color: string }> = ({ value, label, color }) => (
  <div style={{ display: 'flex', alignItems: 'center' }}>
    {computeVitalUnits(value).map(({ position, state }) => (
      <div
        key={position}
        style={{
          display: 'inline-block',
          width: 8,
          height: 8,
          margin: '0 0.5px',
          borderRadius: 1,
          backgroundColor: state === 'empty' ? EMPTY_VITAL_COLOR : color,
          opacity: state === 'empty' ? 0.4 : 1,
          ...(state === 'half' ? {
            background: `linear-gradient(to right, ${color} 50%, ${EMPTY_VITAL_COLOR} 50%)`,
          } : {}),
        }}
      />
    ))}
    <span style={{ color, fontSize: 10, marginLeft: 4 }}>
      {label}
    </span>
  </div>
);

const EffectChip: React.FC<{ effect: ActiveEffect; currentTimeMs: number }> = ({ effect, currentTimeMs }) => {
  const { t } = useTranslation();
  const labelKey = effectLabelKey(effect.effectTypeId);
  const name = labelKey ? t(labelKey) : t('replay.effects.unknown', { id: effect.effectTypeId });
  const remainingTicks = Math.max(0, remainingEffectTicks(effect, currentTimeMs));
  return (
    <span style={{
      fontSize: 9,
      padding: '1px 5px',
      borderRadius: 3,
      background: 'rgba(255,255,255,0.06)',
      color: effectColor(effect.effectTypeId),
      whiteSpace: 'nowrap',
    }}>
      {name} {effectLevel(effect.amplifier)} {formatTicks(remainingTicks)}
    </span>
  );
};

const SECTION_LABEL_STYLE: React.CSSProperties = {
  fontSize: 9,
  color: 'rgba(255,255,255,0.4)',
  marginBottom: 4,
  textTransform: 'uppercase',
  letterSpacing: '0.05em',
};

const PlayerDetail: React.FC<{
  playerState: PlayerState;
  currentTimeMs: number;
  atlas?: AtlasInfo | null | undefined;
  isFollowing: boolean;
  onFollow: () => void;
  onUnfollow: () => void;
}> = ({ playerState, currentTimeMs, atlas, isFollowing, onFollow, onUnfollow }) => {
  const { t } = useTranslation();
  const getSlot = (index: number) => playerState.inventory.get(index);
  const activeEffects = activeEffectsAt(playerState.effects, currentTimeMs);

  return (
    <div style={{ padding: '8px 12px', borderTop: '1px solid rgba(255,255,255,0.06)' }}>
      {/* Follow/Unfollow button */}
      <button
        onClick={isFollowing ? onUnfollow : onFollow}
        style={{
          width: '100%',
          padding: '5px 10px',
          marginBottom: 8,
          background: isFollowing ? 'rgba(99, 102, 241, 0.3)' : 'rgba(99, 102, 241, 0.15)',
          border: '1px solid rgba(99, 102, 241, 0.3)',
          borderRadius: 6,
          color: 'white',
          fontSize: 11,
          cursor: 'pointer',
          transition: 'background 0.15s',
        }}
      >
        {isFollowing ? t('replay.players.unfollow') : t('replay.players.follow')}
      </button>

      {/* Health & Food */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 3, marginBottom: 8 }}>
        <VitalBar value={playerState.health} label={playerState.health.toFixed(0)} color={HEALTH_COLOR} />
        <VitalBar value={playerState.food} label={String(playerState.food)} color={FOOD_COLOR} />
      </div>

      {/* Equipment strip */}
      <div style={{ marginBottom: 8 }}>
        <div style={SECTION_LABEL_STYLE}>
          {t('replay.players.equipment')}
        </div>
        <div style={{ display: 'flex', gap: 1 }}>
          {ARMOR_SLOTS.map(({ slot, labelKey }) => (
            <CompactSlotCell key={slot} slot={getSlot(slot)} label={t(labelKey)} atlas={atlas} />
          ))}
          <div style={{ width: 4 }} />
          <CompactSlotCell slot={getSlot(OFFHAND_SLOT)} label={t('replay.players.slotOffhand')} atlas={atlas} />
        </div>
      </div>

      {/* Potion effects */}
      {activeEffects.length > 0 && (
        <div style={{ marginBottom: 8 }}>
          <div style={SECTION_LABEL_STYLE}>
            {t('replay.players.effects')}
          </div>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 3 }}>
            {activeEffects.map((effect) => (
              <EffectChip key={effect.effectTypeId} effect={effect} currentTimeMs={currentTimeMs} />
            ))}
          </div>
        </div>
      )}

      {/* Inventory grid */}
      <div>
        <div style={SECTION_LABEL_STYLE}>
          {t('replay.players.inventory')}
        </div>
        {/* Main inventory (9-35) */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 1, marginBottom: 4 }}>
          {[0, 1, 2].map((row) => (
            <div key={row} style={{ display: 'flex', gap: 1 }}>
              {Array.from({ length: 9 }, (_, col) => {
                const slotIndex = 9 + row * 9 + col;
                return <CompactSlotCell key={slotIndex} slot={getSlot(slotIndex)} atlas={atlas} />;
              })}
            </div>
          ))}
        </div>
        {/* Separator */}
        <div style={{ borderTop: '1px solid rgba(255,255,255,0.06)', margin: '3px 0' }} />
        {/* Hotbar (0-8) */}
        <div style={{ display: 'flex', gap: 1 }}>
          {Array.from({ length: 9 }, (_, i) => (
            <CompactSlotCell key={i} slot={getSlot(i)} atlas={atlas} />
          ))}
        </div>
      </div>
    </div>
  );
};

export const PlayerSidebar: React.FC<PlayerSidebarProps> = ({
  players,
  followingPlayer,
  expandedPlayer,
  onFollowPlayer,
  onExpandPlayer,
  currentTimeMs = 0,
  visible = true,
  selectedPlayerState,
  atlas,
}) => {
  const { t } = useTranslation();
  if (players.length === 0) return null;

  return (
    <div style={{
      position: 'absolute',
      top: 16,
      right: 16,
      bottom: 80,
      width: 280,
      background: 'rgba(15, 15, 30, 0.5)',
      backdropFilter: 'blur(16px)',
      WebkitBackdropFilter: 'blur(16px)',
      border: '1px solid rgba(255, 255, 255, 0.08)',
      borderRadius: 12,
      overflowY: 'auto',
      fontFamily: 'system-ui, sans-serif',
      color: 'white',
      fontSize: 13,
      zIndex: 5,
      opacity: visible ? 1 : 0,
      transform: visible ? 'translateX(0)' : 'translateX(12px)',
      transition: 'opacity 0.3s ease, transform 0.3s ease',
      pointerEvents: visible ? 'auto' : 'none',
    }}>
      <div style={{
        padding: '10px 12px 6px',
        fontSize: 10,
        textTransform: 'uppercase',
        letterSpacing: '0.05em',
        opacity: 0.5,
      }}>
        {t('replay.players.title')}
      </div>
      {players.map((player) => {
        const isFollowing = followingPlayer === player.uuid;
        const isExpanded = expandedPlayer === player.uuid;
        const activeEffectCount = activeEffectsAt(player.effects, currentTimeMs).length;
        const detailState = isExpanded && selectedPlayerState?.uuid === player.uuid ? selectedPlayerState : null;

        return (
          <div key={player.uuid}>
            <button
              onClick={() => onExpandPlayer(isExpanded ? null : player.uuid)}
              style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                width: '100%',
                padding: '6px 12px',
                background: isExpanded ? 'rgba(99, 102, 241, 0.2)' : 'none',
                border: 'none',
                color: 'white',
                textAlign: 'left',
                cursor: 'pointer',
                fontSize: 13,
                transition: 'background 0.15s',
              }}
              title={isExpanded ? t('replay.players.collapse') : t('replay.players.expand', { name: player.playerName })}
            >
              <span>{player.playerName}</span>
              {isFollowing && (
                <span style={{ opacity: 0.5, fontSize: 10 }}>{t('replay.players.following')}</span>
              )}
              {!isFollowing && activeEffectCount > 0 && (
                <span style={{ opacity: 0.4, fontSize: 10 }}>
                  {t('replay.players.effectCount', { count: activeEffectCount })}
                </span>
              )}
            </button>
            {/* Expanded detail for selected player */}
            {detailState && (
              <PlayerDetail
                playerState={detailState}
                currentTimeMs={currentTimeMs}
                atlas={atlas}
                isFollowing={isFollowing}
                onFollow={() => onFollowPlayer(player.uuid)}
                onUnfollow={() => onFollowPlayer(null)}
              />
            )}
          </div>
        );
      })}
      {followingPlayer && (
        <button
          onClick={() => onFollowPlayer(null)}
          style={{
            display: 'block',
            width: '100%',
            padding: '6px 12px',
            marginTop: 4,
            background: 'none',
            border: 'none',
            borderTop: '1px solid rgba(255,255,255,0.06)',
            color: 'rgba(255,255,255,0.5)',
            textAlign: 'center',
            cursor: 'pointer',
            fontSize: 12,
          }}
        >
          {t('replay.players.freeCamera')}
        </button>
      )}
    </div>
  );
};

