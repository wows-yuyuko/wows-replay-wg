package com.shinoaki.wowsreplay.core.packet;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.shinoaki.wowsreplay.core.model.EntityId;
import com.shinoaki.wowsreplay.core.model.Recognized;
import com.shinoaki.wowsreplay.core.model.WeaponLockType;
import com.shinoaki.wowsreplay.core.model.WeaponType;

/**
 * 0x30: 武器锁定状态变更。 */
public record SetWeaponLockPacket(
    @JsonProperty("weapon_type") Recognized<WeaponType> weaponType,
    @JsonProperty("lock_type") Recognized<WeaponLockType> lockType,
    @JsonProperty("target_id") EntityId targetId
) {}
