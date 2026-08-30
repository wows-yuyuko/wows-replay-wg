package com.shinoaki.wowsreplay.core.utils;

import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * @author Xun
 * @date 2023/4/3 18:02 星期一
 */
@Slf4j
public class GzipUtils {
    private GzipUtils() {

    }

    public static byte[] compress(String data) {
        return compress(data.getBytes(StandardCharsets.UTF_8), 5);
    }

    /**
     * 真 gzip 压缩（gzip 头 1f 8b + raw deflate + CRC32 尾），与 {@link #uncompress}（GZIPInputStream）双向兼容。
     *
     * <p>GZIPOutputStream 无 Deflater 构造器（JDK 26），用匿名子类设置压缩级别；
     * level 超界（>9）自动收敛到 9（Deflater 合法范围 0-9）。</p>
     */
    public static byte[] compress(byte[] data, int level) {
        int lvl = Math.clamp(level, 0, 9);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out) {
            {
                def.setLevel(lvl);
            }
        }) {
            gzip.write(data);
            gzip.finish();
        } catch (Exception e) {
            log.error("编码数据异常", e);
            return new byte[0];
        }
        return out.toByteArray();
    }


    public static byte[] uncompress(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return new byte[0];
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayInputStream in = new ByteArrayInputStream(bytes);
        try {
            GZIPInputStream ungzip = new GZIPInputStream(in);
            byte[] buffer = new byte[256];
            int n;
            while ((n = ungzip.read(buffer)) >= 0) {
                out.write(buffer, 0, n);
            }
        } catch (Exception e) {
            log.error("解码数据异常", e);
        }
        return out.toByteArray();
    }
}
