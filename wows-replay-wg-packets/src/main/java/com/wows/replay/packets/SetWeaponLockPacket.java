package com.wows.replay.packets;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wows.replay.spec.types.EntityId;
import com.wows.replay.spec.types.Recognized;
import com.wows.replay.spec.types.WeaponLockType;
import com.wows.replay.spec.types.WeaponType;

/**
 * Packet 0x30: Weapon lock state change.
 */
public record SetWeaponLockPacket(
    @JsonProperty("weapon_type") Recognized<WeaponType> weaponType,
    @JsonProperty("lock_type") Recognized<WeaponLockType> lockType,
    @JsonProperty("target_id") EntityId targetId
) {}
