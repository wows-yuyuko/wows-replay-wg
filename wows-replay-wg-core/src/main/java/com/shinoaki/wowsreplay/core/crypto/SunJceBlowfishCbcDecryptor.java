package com.shinoaki.wowsreplay.core.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;

/**
 * SunJCE（JDK 内置）实现——默认解密策略。
 */
public final class SunJceBlowfishCbcDecryptor extends AbstractBlowfishCbcDecryptor {

    @Override
    protected Cipher newDecryptCipher() throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("Blowfish/ECB/NoPadding", "SunJCE");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(BLOWFISH_KEY, "Blowfish"));
        return cipher;
    }
}
