package com.shinoaki.wowsreplay.core.crypto;

import java.security.GeneralSecurityException;

/**
 * Blowfish-CBC 解密策略（可插拔）。
 *
 * <p>回放包数据用固定 16 字节 key + 零 IV 做 Blowfish-CBC 解密
 */
public interface BlowfishCbcDecryptor {

    /**
     * 用硬编码 WG key + 零 IV 解密。
     *
     * @param encrypted 密文（长度不要求 8 对齐，内部自动补零到块边界）
     * @return 解密后的字节（含补零尾块）
     */
    byte[] decrypt(byte[] encrypted) throws GeneralSecurityException;
}
