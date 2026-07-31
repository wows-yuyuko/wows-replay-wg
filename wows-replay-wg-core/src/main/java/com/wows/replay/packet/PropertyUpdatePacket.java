package com.wows.replay.packet;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wows.replay.model.EntityId;

/**
 * 0x23锛堟柊鐗堬級/ 0x22锛堟棫鐗堬級: 宓屽灞炴€ф洿鏂般€? * 鐢ㄤ簬鏇存柊澶嶆潅绫诲瀷鐨勫瓙灞炴€с€? */
public record PropertyUpdatePacket(
    @JsonProperty("entity_id") EntityId entityId,
    @JsonProperty("property") String property,
    @JsonProperty("update_cmd") Object updateCmd
) {}
