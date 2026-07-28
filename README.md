# wows-replay-wg

World of Warships WG (Wargaming) 服务器回放文件解析库 — 纯 Java 实现，JDK 25。

## 模块结构

| 模块 | 职责 |
|------|------|
| **wows-replay-wg-spec-api** | 基础类型 (EntityId, GameClock, Version...)、RPC 类型系统 (ArgType/ArgValue)、EntitySpec 定义、抽象接口 (EntitySpecProvider / GameParamProvider / GameConstantsProvider) |
| **wows-replay-wg-core** | 回放文件 I/O → Blowfish-CBC 解密 → zlib 解压 → 原始包流迭代 (ReplayFile, RawPacketIterator, PacketTypeId) |
| **wows-replay-wg-packets** | 30+ 包类型定义 (PositionPacket, EntityCreatePacket, CameraPacket...) + 基于 EntitySpec 的载荷解码器 (PacketParser) |
| **wows-replay-wg-analyzer** | 高层分析 + JSON 报告生成 (ReplayAnalyzer, BattleReport) |

## 依赖关系

```
wows-replay-wg-spec-api   (无内部依赖，仅 Jackson 3)
    ↑
wows-replay-wg-core       (依赖 spec-api)
    ↑
wows-replay-wg-packets    (依赖 spec-api + core)
    ↑
wows-replay-wg-analyzer   (依赖所有)
```

## 快速开始

```java
import com.wows.replay.core.ReplayFile;
import com.wows.replay.analyzer.ReplayAnalyzer;

// 1. 解析回放文件
ReplayFile replay = ReplayFile.fromFile(Path.of("replay.wowsreplay"));

// 2. 快速分析 (无需游戏数据)
String json = ReplayAnalyzer.quick(replay);
System.out.println(json);

// 3. 遍历原始包
replay.packets().forEach(pkt -> {
    System.out.println(pkt.packetType() + " @ " + pkt.clock());
});

// 4. 仅读取元数据 (不解密包流, 极快)
ReplayMeta meta = ReplayFile.metaFromFile(Path.of("replay.wowsreplay"));
```

## 构建

```bash
cd wows-replay-wg
mvn clean compile      # 编译
mvn test               # 测试
mvn package            # 打包
```

## 技术栈

- **JDK 25** — Records, Pattern Matching, Sealed Types, Switch Expressions
- **Jackson 3** — JSON 序列化
- **BouncyCastle** — Blowfish-CBC 解密
- **jlibdeflate** — zlib 解压
- **Maven** — 构建管理

## wowsunpack 解耦

以下功能通过接口抽象，不依赖游戏安装目录：

- `EntitySpecProvider` — 实体定义加载 (对接 wowsunpack 的 .def 文件)
- `GameParamProvider` — 游戏参数查询 (船名/ID 映射)
- `GameConstantsProvider` — 游戏常量查询 (消耗品/战斗阶段/死亡原因名)

默认实现均为空操作，提供降级行为。接入真实游戏数据时只需实现这三个接口。

## 回放文件格式

```
[magic: u32 0x12345678]
[block_count: u32]
[meta_len: u32] [meta_json: UTF-8]
[extra_blocks...]
[decompressed_size: u32]
[compressed_size: u32]
[encrypted_packets: Blowfish-CBC + zlib]

每个包: [size: u32][type: u32][clock: f32][payload: bytes]
```

解密密钥: `29 B7 C9 09 38 3F 84 88 FA 98 EC 4E 13 19 79 FB` (Blowfish-CBC, 全零 IV)
