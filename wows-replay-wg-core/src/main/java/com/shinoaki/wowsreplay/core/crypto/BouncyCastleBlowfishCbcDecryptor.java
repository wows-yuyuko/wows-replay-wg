//package com.shinoaki.wowsreplay.core.crypto;
//
//import org.bouncycastle.jce.provider.BouncyCastleProvider;
//
//import javax.crypto.Cipher;
//import javax.crypto.spec.SecretKeySpec;
//import java.security.GeneralSecurityException;
//import java.security.Security;
//
///**
// * BouncyCastle 实现（保留原实现，供 SunJCE 不可用时回退/强制切换）。
// *
// * <p>仅在本类加载时注册 BC provider；与 SunJCE 实现输出逐字节一致。</p>
// */
//public final class BouncyCastleBlowfishCbcDecryptor extends AbstractBlowfishCbcDecryptor {
//
//    static {
//        Security.addProvider(new BouncyCastleProvider());
//    }
//
//    @Override
//    protected Cipher newDecryptCipher() throws GeneralSecurityException {
//        Cipher cipher = Cipher.getInstance("Blowfish/ECB/NoPadding", "BC");
//        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(BLOWFISH_KEY, "Blowfish"));
//        return cipher;
//    }
//}
