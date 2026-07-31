# wows-replay-wg

World of Warships WG (Wargaming) server replay parser — pure Java, JDK 25.

## Architecture

4-layer pipeline aligned with the reference document:

```
Layer 0: ReplayFile    → file I/O, Blowfish-CBC decrypt, zlib decompress
Layer 1: Parser        → byte stream → framed packets { type, clock, payload }
Layer 2: PacketDecoder → Packet → semantic decode → DecodedPayload
Layer 3: BattleWorld   → DecodedPayload → ECS world (entities, resources, events)
```

## Module structure

| Module | Layer | Description |
|--------|-------|-------------|
| **wows-replay-wg-core** | 0-1 | Replay file I/O, Blowfish-CBC decrypt, zlib decompress, packet framing, type system (ArgType/ArgValue), entity specs, SPI interfaces |
| **wows-replay-wg-decode** | 2 | Semantic decode: PacketDecoder, MethodDecoder, PropertyDecoder, PickleReader |
| **wows-replay-wg-ingest** | 3 | ECS ingest: BattleWorld, EntityManager, EntityState, ReplayAnalyzer, BattleReport |

## Dependencies

```
wows-replay-wg-core        (zero internal deps)
    ↑
wows-replay-wg-decode      (depends on core)
    ↑
wows-replay-wg-ingest      (depends on decode)
```

Layer 3 is optional — if you only need JSON output of decoded packets, depend on `core` + `decode` only.

## Quick start

```java
import com.wows.replay.ReplayFile;
import com.wows.replay.ingest.ReplayAnalyzer;

// 1. Parse replay file
ReplayFile replay = ReplayFile.fromFile(Path.of("replay.wowsreplay"));

// 2. Quick analysis (framing stats only, no game data)
String json = ReplayAnalyzer.quick(replay);

// 3. Full analysis (requires game data for entity specs)
var config = ReplayAnalyzerConfig.DEFAULT;
var analyzer = new ReplayAnalyzer.Builder()
    .specProvider(specProvider)
    .config(config)
    .build();
String report = analyzer.analyze(replay);   // JSON report

// 4. Structured report (BattleReport record tree)
BattleReport report2 = analyzer.buildReport(replay);

// 5. Read metadata only (skip decryption, very fast)
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

## SPI interfaces

The following are abstract interfaces, no game install required:

- `EntitySpecProvider` — entity definition loading (connects to .def files from game data)
- `GameConstantsProvider` — game constant queries (consumable/battle stage/death cause names)
- `DefFileLoader` — abstract filesystem access for .def files

Default implementations are no-ops with graceful degradation.

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
