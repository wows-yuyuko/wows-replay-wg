package com.wows.replay.packet;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wows.replay.model.EntityId;
import com.wows.replay.model.Recognized;
import com.wows.replay.model.WeaponLockType;
import com.wows.replay.model.WeaponType;

/**
 * 0x30: 姝﹀櫒閿佸畾鐘舵€佸彉鏇淬€? */
public record SetWeaponLockPacket(
    @JsonProperty("weapon_type") Recognized<WeaponType> weaponType,
    @JsonProperty("lock_type") Recognized<WeaponLockType> lockType,
    @JsonProperty("target_id") EntityId targetId
) {}
