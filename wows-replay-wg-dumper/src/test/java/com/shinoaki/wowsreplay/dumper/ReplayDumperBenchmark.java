package com.shinoaki.wowsreplay.dumper;

import ch.qos.logback.classic.Logger;
import com.shinoaki.wowsreplay.core.ReplayException;
import com.shinoaki.wowsreplay.core.ReplayFile;
import com.shinoaki.wowsreplay.core.data.LangProvider;
import com.shinoaki.wowsreplay.core.spec.GameDataCache;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;


@BenchmarkMode(Mode.AverageTime)        // 测量平均耗时
@OutputTimeUnit(TimeUnit.MILLISECONDS)  // 单位：毫秒
@Warmup(iterations = 2, time = 5)       // 预热：3轮，每轮5秒
@Measurement(iterations = 1, time = 5)  // 正式测试：5轮，每轮5秒
@Fork(1)                                // 隔离JVM进程数
@State(Scope.Benchmark)                 // 共享状态（用于准备测试数据）
public class ReplayDumperBenchmark {
    private static final GameDataCache gameDataCache = GameDataCache.withMaxSize(5);
    private static final String WOWS_DATA_BASE = "temp/wows-data";
    private static final org.slf4j.Logger log = LoggerFactory.getLogger(ReplayDumperBenchmark.class);

    private ReplayDumper dumper;
    private List<File> fileList;       // 准备多个不同的 replay 文件路径
    private static final Path base = new File("C:\\Users\\uuz\\Documents\\GitHub\\wows-replay-wg\\temp\\wows-data").toPath();

    @Setup(Level.Trial)                 // 所有测试开始前执行一次
    public void setup() {
        // 初始化 ReplayDumper（可以注入真实依赖）
        setLogLevel();
        fileList = new ArrayList<>();
        String f = "D:\\Games\\World_of_Warships\\replays";
        fileList.addAll(Arrays.asList(Objects.requireNonNull(new File(f).listFiles())));
        log.info("加载了 {} 个 replay 文件用于测试", fileList.size());
        if (!fileList.isEmpty()) {
            try {
                var options = new ReplayDumper.Options(LangProvider.Lang.ZH_SG, true, false, 6);
                var replayDumper = new ReplayDumper(
                        List.of(ReplayFile.fromFile(fileList.getFirst().toPath(), base)),
                        options,
                        gameDataCache
                );
                replayDumper.dump();
                log.info("预热执行完成");
            } catch (Exception e) {
                log.error("预热执行失败", e);
            }
        }
    }

    @Benchmark
    public void testParseReplay() {
        // 这里会执行一次解析操作
        // 为了更接近真实场景，你可以循环解析多个文件
        try {
            for (var filePath : fileList) {
                var options = new ReplayDumper.Options(LangProvider.Lang.ZH_SG, true, false, 6);
                var replayDumper = new ReplayDumper(List.of(ReplayFile.fromFile(filePath.toPath(), base)), options, gameDataCache);
                replayDumper.dump();
            }
        } catch (ReplayException e) {
            log.error("replay解析异常");
        } catch (IOException e) {
            log.error("replay读取文件异常");
        }
    }

    /**
     * 设置 Logback 日志级别
     *
     */
    private void setLogLevel() {
        var level = ch.qos.logback.classic.Level.INFO;
//        Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
//        rootLogger.setLevel(level);

        // 也可以针对特定包设置级别
        Logger packageLogger = (Logger) LoggerFactory.getLogger("com.shinoaki.wowsreplay");
        packageLogger.setLevel(level);

        log.info("日志级别已设置为: {}", level);
    }

    public static void main(String[] args) throws Exception {
        Options options = new OptionsBuilder()
                .include(ReplayDumperBenchmark.class.getSimpleName())
                .jvmArgsAppend("-XX:StartFlightRecording=settings=profile,dumponexit=true,filename=C:/Users/uuz/Documents/GitHub/wows-replay-wg/temp/perf.jfr")
                .build();
        new Runner(options).run();
    }
}
