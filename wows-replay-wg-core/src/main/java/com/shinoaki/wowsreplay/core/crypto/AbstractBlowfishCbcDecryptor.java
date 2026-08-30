package com.shinoaki.wowsreplay.core.crypto;

import javax.crypto.Cipher;
import java.security.GeneralSecurityException;
import java.util.Arrays;

/**
 * 共享的 Blowfish-CBC 解密骨架：ECB 逐块解密
 *
 * <p>子类只需提供已初始化（DECRYPT_MODE）的 {@code Blowfish/ECB/NoPadding} Cipher，
 * CBC 链与补零逻辑由本类统一实现，保证两个实现输出逐字节一致。</p>
 */
public abstract class AbstractBlowfishCbcDecryptor implements BlowfishCbcDecryptor {

    /** Blowfish key（16 字节），WG 全版本通用。 */
    protected static final byte[] BLOWFISH_KEY = {
            0x29, (byte) 0xB7, (byte) 0xC9, 0x09, 0x38, 0x3F, (byte) 0x84, (byte) 0x88,
            (byte) 0xFA, (byte) 0x98, (byte) 0xEC, 0x4E, 0x13, 0x19, 0x79, (byte) 0xFB
    };

    /** 返回已初始化（DECRYPT_MODE）的 {@code Blowfish/ECB/NoPadding} Cipher。 */
    protected abstract Cipher newDecryptCipher() throws GeneralSecurityException;

    @Override
    public final byte[] decrypt(byte[] encrypted) throws GeneralSecurityException {
        // 补到 8 字节块边界（Blowfish 为 64 位块密码）
        int paddedLen = ((encrypted.length + 7) / 8) * 8;
        byte[] padded = Arrays.copyOf(encrypted, paddedLen);
        Cipher cipher = newDecryptCipher();
        // CBC with zero IV done manually
        byte[] decrypted = new byte[paddedLen];
        byte[] previous = new byte[8]; // all-zero IV

        for (int offset = 0; offset < paddedLen; offset += 8) {
            byte[] block = new byte[8];
            System.arraycopy(padded, offset, block, 0, 8);
            byte[] decryptedBlock = cipher.doFinal(block);
            for (int j = 0; j < 8; j++) {
                decrypted[offset + j] = (byte) (decryptedBlock[j] ^ previous[j]);
            }
            System.arraycopy(decrypted, offset, previous, 0, 8);
        }
        return decrypted;
    }
}
