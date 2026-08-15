package com.shinoaki.wowsreplay.ingest.mapped;

import com.shinoaki.wowsreplay.core.JsonMapper;
import com.shinoaki.wowsreplay.core.decode.DecodedPayload;
import com.shinoaki.wowsreplay.ingest.BattleWorld;
import com.shinoaki.wowsreplay.ingest.report.BattleReport;

import java.util.ArrayList;
import java.util.List;

/**
 * 映射层：在 {@link BattleWorld} 处理完成后，把视角相关的实体 id 统一解析为全局一致的
 * {@code metaId}，产出 {@link NormalizedReplay}（docs 目标：最终输出以 metaId 为主、
 * accountId 保留在玩家信息）。
 *
 * <p><b>为什么需要本层</b>：同场次各视角中，录制者自身实体 id 会差 ±1（metaPlayers 一致性
 * 测试已证实），因此直接按实体 id 做跨视角去重/输出会漏重或输出不可靠；而 {@code metaId}
 * （战斗内 meta id）跨视角全局一致。</p>
 *
 * <p><b>解析链</b>（entity → metaId）：{@code entityToPlayer} 直查（Avatar/玩家实体）→
 * 未命中走 {@code vehicleToOwner}（船→Avatar）再查 → 仍未命中回 0。</p>
 *
 * <p>本层是单视角输出、多视角合并（{@code ReplayMerger}）与流式合并（{@code MergedSession}）
 * 共享的归一入口：任何拿到已处理 {@link BattleWorld} 的消费方都可调用 {@link #map}。</p>
 */
public final class ReplayMapper {

    private ReplayMapper() {
    }

    /**
     * 把已处理完成的 world + report 映射为规范化输出。
     *
     * <p>不改动 {@link BattleWorld} 内部状态；玩家事件流全部解析为 metaId，玩家信息保留
     * metaId + accountId，非玩家实体与稳定 id 流原样透传。</p>
     */
    public static NormalizedReplay map(BattleWorld world, BattleReport report) {
        return new NormalizedReplay(
            report.arenaId(),
            report.version(),
            report.mapName(),
            report.gameMode(),
            report.gameType(),
            report.matchGroup(),
            report.matchResult(),
            report.finishType(),
            world.winningTeam(),
            report.battleStartClock(),
            world.battleResultClock(),
            world.battleEndClock(),
            report.battleResults() != null ? JsonMapper.toJson(report.battleResults()) : null,

            mapPlayers(world, report),
            mapKills(world),
            mapDamage(world),
            mapChat(world),
            mapConsumables(world),
            mapDeadShips(world),

            world.voiceLineLog(),
            world.ribbonLog(),
            world.firedSalvos(),
            world.torpedoes(),
            world.shotHits(),
            world.teamScores(),
            world.capturePoints(),
            world.buffZones(),
            world.capturedBuffs(),
            world.weatherZones(),
            world.buildings());
    }

    // ── 玩家 ─────────────────────────────────────────────────────────────

    /** 玩家信息：metaId + accountId（保留在用户信息里），去掉 entity_id。 */
    private static List<NormalizedPlayer> mapPlayers(BattleWorld world, BattleReport report) {
        var out = new ArrayList<NormalizedPlayer>(report.players().size());
        for (var p : report.players()) {
            out.add(new NormalizedPlayer(p.metaId(), p.dbId(), p.username(),
                p.teamId(), p.relation(), p.isBot()));
        }
        return out;
    }

    // ── 玩家事件流（metaId 主键）────────────────────────────────────────

    private static List<NormalizedKill> mapKills(BattleWorld world) {
        var out = new ArrayList<NormalizedKill>(world.killLog().size());
        for (var k : world.killLog()) {
            out.add(new NormalizedKill(k.clock(),
                resolve(metaIdOf(world, k.killerEid()), k.killerMetaId()), k.killerName(),
                resolve(metaIdOf(world, k.victimEid()), k.victimMetaId()), k.victimName(),
                k.cause()));
        }
        return out;
    }

    private static List<NormalizedDamage> mapDamage(BattleWorld world) {
        var out = new ArrayList<NormalizedDamage>(world.damageEvents().size());
        for (var d : world.damageEvents()) {
            out.add(new NormalizedDamage(d.clock(),
                metaIdOf(world, d.aggressorId()),
                metaIdOf(world, d.victimId()),
                d.amount()));
        }
        return out;
    }

    private static List<NormalizedChat> mapChat(BattleWorld world) {
        var out = new ArrayList<NormalizedChat>(world.chatLog().size());
        for (var c : world.chatLog()) {
            out.add(new NormalizedChat(c.clock(), c.metaId(), c.senderName(), c.channel(), c.message()));
        }
        return out;
    }

    private static List<NormalizedConsumable> mapConsumables(BattleWorld world) {
        var out = new ArrayList<NormalizedConsumable>(world.consumableLog().size());
        for (var c : world.consumableLog()) {
            out.add(new NormalizedConsumable(c.clock(),
                resolve(metaIdOf(world, c.entityId()), c.metaId()), c.username(),
                c.consumableId(), c.duration(), c.entityId(), typeName(c.kind())));
        }
        return out;
    }

    /** 消耗品使用者类型名（对标 DecodedPayload.ConsumableKind）。 */
    private static String typeName(DecodedPayload.ConsumableKind kind) {
        return switch (kind) {
            case SHIP -> "ship";
            case PLANE -> "plane";
            case OTHER -> "other";
        };
    }

    private static List<NormalizedDeadShip> mapDeadShips(BattleWorld world) {
        var out = new ArrayList<NormalizedDeadShip>(world.deadShips().size());
        for (var d : world.deadShips()) {
            out.add(new NormalizedDeadShip(d.clock(), metaIdOf(world, d.victimId()), d.x(), d.z()));
        }
        return out;
    }

    // ── 解析辅助 ─────────────────────────────────────────────────────────

    /**
     * entity → metaId：Avatar/玩家实体直查 {@code entityToPlayer}；未命中走
     * {@code vehicleToOwner}（Vehicle 船 → Avatar）再查。未知返回 0。
     *
     * <p>供 dumper（minimap 输出）等消费方复用同一解析链。</p>
     */
    public static long metaIdOf(BattleWorld world, int eid) {
        var link = world.entityToPlayer().get(eid);
        if (link != null) return link.metaId();
        Integer owner = world.vehicleToOwner().get(eid);
        if (owner != null) {
            link = world.entityToPlayer().get(owner);
            if (link != null) return link.metaId();
        }
        return 0;
    }

    /** 优先用已解析结果；为 0 时回退到原始记录里已有的 metaId（更完整）。 */
    private static long resolve(long resolved, long fallback) {
        return resolved != 0 ? resolved : fallback;
    }
}
