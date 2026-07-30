# wows-replay-wg

World of Warships WG (Wargaming) server replay parser — pure Java, JDK 25.

## Module structure

| Module | Description |
|--------|-------------|
| **wows-replay-wg-core** | Self-contained replay parsing — zero internal deps, no game data required. Types (EntityId, GameClock, Version, ArgType/ArgValue...), file I/O (ReplayFile, Blowfish-CBC decrypt, zlib decompress), 30+ packet types + PacketParser, SPI interfaces. |
| **wows-replay-wg-game-data** | Entity spec XML loading from game data directory: XmlEntitySpecProvider, XmlDefParser, GameDataCache. Requires game install. |
| **wows-replay-wg-analyzer** | High-level analysis + JSON output: ReplayAnalyzer, BattleReport, event extraction. |
| **wows-dumper** | CLI entry point + JSON pipeline: DumperPipeline, RichExtractor, PickleDecoder, MinimapData. |

## Dependencies

```
wows-replay-wg-core        (zero internal deps, Jackson 3 only)
    ↑
wows-replay-wg-game-data   (depends on core)
    ↑
wows-replay-wg-analyzer    (depends on core + game-data)
    ↑
wows-dumper                (depends on analyzer)
```

## Quick start

```java
import com.wows.replay.core.ReplayFile;
import com.wows.replay.analyzer.ReplayAnalyzer;

// 1. Parse replay file
ReplayFile replay = ReplayFile.fromFile(Path.of("replay.wowsreplay"));

// 2. Quick analysis (no game data needed)
String json = ReplayAnalyzer.quick(replay);
System.out.println(json);

// 3. Iterate raw packets
replay.packets().forEach(pkt -> {
    System.out.println(pkt.packetType() + " @ " + pkt.clock());
});

// 4. Read metadata only (skip decryption, very fast)
ReplayMeta meta = ReplayFile.metaFromFile(Path.of("replay.wowsreplay"));
```

## Build

```bash
cd wows-replay-wg
mvn clean compile      # compile
mvn test               # test
mvn package            # package
```

## Tech stack

- **JDK 25** — Records, Pattern Matching, Sealed Types, Switch Expressions
- **Jackson 3** — JSON serialization
- **BouncyCastle** — Blowfish-CBC decryption
- **jlibdeflate** — zlib decompression
- **Maven** — build management

## wowsunpack decoupling

The following are abstract interfaces, no game install required:

- `EntitySpecProvider` — entity definition loading (connects to wowsunpack .def files)
- `GameParamProvider` — game parameter queries (ship name/ID mapping)
- `GameConstantsProvider` — game constant queries (consumable/battle stage/death cause names)

Default implementations are no-ops with graceful degradation. Implement these three interfaces to integrate real game data.

## Replay file format

```
[magic: u32 0x12345678]
[block_count: u32]
[meta_len: u32] [meta_json: UTF-8]
[extra_blocks...]
[decompressed_size: u32]
[compressed_size: u32]
[encrypted_packets: Blowfish-CBC + zlib]

Each packet: [size: u32][type: u32][clock: f32][payload: bytes]
```

Decryption key: `29 B7 C9 09 38 3F 84 88 FA 98 EC 4E 13 19 79 FB` (Blowfish-CBC, all-zero IV)
