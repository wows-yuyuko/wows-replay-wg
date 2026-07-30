package com.wows.replay.packets;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wows.replay.core.types.EntityId;
import com.wows.replay.core.types.Recognized;
import com.wows.replay.core.types.WeaponLockType;
import com.wows.replay.core.types.WeaponType;

/**
 * 0x30: 姝﹀櫒閿佸畾鐘舵€佸彉鏇淬€? */
public record SetWeaponLockPacket(
    @JsonProperty("weapon_type") Recognized<WeaponType> weaponType,
    @JsonProperty("lock_type") Recognized<WeaponLockType> lockType,
    @JsonProperty("target_id") EntityId targetId
) {}
