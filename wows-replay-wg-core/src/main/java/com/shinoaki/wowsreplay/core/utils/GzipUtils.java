package com.shinoaki.wowsreplay.core.utils;

import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;

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

    public static byte[] compress(byte[] data, int level) {
        // 创建 Deflater 并设置压缩等级
        Deflater deflater = new Deflater(level, true);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DeflaterOutputStream gzip = new DeflaterOutputStream(out, deflater)) {
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
