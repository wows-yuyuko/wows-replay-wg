package com.shinoaki.wowsreplay.core.crypto;

import java.security.GeneralSecurityException;

/**
 * Blowfish-CBC 解密策略选择器（插件式）。
 *
 * <p>默认 {@link SunJceBlowfishCbcDecryptor}（JDK 内置，零第三方依赖）。可用系统属性
 */
public final class BlowfishCbcDecryptors {


    private static final BlowfishCbcDecryptor SUN_JCE = new SunJceBlowfishCbcDecryptor();


    private BlowfishCbcDecryptors() {
    }


    public static byte[] decrypt(byte[] encrypted) throws GeneralSecurityException {
        return SUN_JCE.decrypt(encrypted);
    }


}
