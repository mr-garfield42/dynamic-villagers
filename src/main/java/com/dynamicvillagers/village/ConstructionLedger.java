package com.dynamicvillagers.village;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The village's construction sites. Village-level, not per-villager (unlike mine/quarry
 * sites): a building outlives any single builder, several builders may share one site, and
 * the Phase 5 village manager will post sites itself. Like the storage ledger there is one
 * per level and queries are distance-gated around a villager's anchor; per-block progress is
 * deliberately NOT stored here — builders diff the blueprint against the world (Phase 4
 * decision 3), so the ledger only holds what the world cannot: what is being built, where,
 * which way it faces, and the short-lived claims that keep two builders off the same blocks.
 */
public class ConstructionLedger extends SavedData {
    public static final String DATA_NAME = "dynamicvillagers_construction";
    /** Backstop for claims orphaned by cleared queues/death; planners release explicitly. */
    public static final long CLAIM_TTL = 1200;

    public enum Status {
        OPEN, // being built (or found damaged and reopened)
        DONE; // last full diff pass matched the blueprint

        @Nullable
        public static Status byName(String name) {
            for (Status value : values()) {
                if (value.name().equals(name)) {
                    return value;
                }
            }
            return null;
        }
    }

    /** A short-lived "these blocks are my batch" claim. */
    public record Claim(UUID holder, long expiry) {
    }

    /** One designated building: a blueprint anchored in the world. */
    public static final class ConstructionSite {
        private final int id;
        private final ResourceLocation templateId;
        private final BlockPos origin;
        private final Rotation rotation;
        private final long created;
        private Status status = Status.OPEN;
        @Nullable
        private BlockPos staging; // deliver-to container for this site's material requests
        private final Set<BlockPos> scaffold = new HashSet<>(); // temporary dirt we must tear down
        private final Map<BlockPos, Claim> claims = new HashMap<>();
        private final Map<String, Integer> requests = new HashMap<>(); // item filter → request id

        private ConstructionSite(int id, ResourceLocation templateId, BlockPos origin,
                                 Rotation rotation, long created) {
            this.id = id;
            this.templateId = templateId;
            this.origin = origin;
            this.rotation = rotation;
            this.created = created;
        }

        public int id() {
            return id;
        }

        public ResourceLocation templateId() {
            return templateId;
        }

        public BlockPos origin() {
            return origin;
        }

        public Rotation rotation() {
            return rotation;
        }

        public long created() {
            return created;
        }

        public Status status() {
            return status;
        }

        @Nullable
        public BlockPos staging() {
            return staging;
        }

        public Set<BlockPos> scaffold() {
            return Collections.unmodifiableSet(scaffold);
        }

        /** Material requests this site has posted on the storage ledger, by item filter. */
        public Map<String, Integer> requests() {
            return Collections.unmodifiableMap(requests);
        }

        /** Free for this villager to work: unclaimed, expired, or its own claim. */
        public boolean mayWork(BlockPos pos, UUID villager, long now) {
            Claim claim = claims.get(pos);
            return claim == null || claim.expiry() <= now || claim.holder().equals(villager);
        }
    }

    private static final SavedData.Factory<ConstructionLedger> FACTORY =
            new SavedData.Factory<>(ConstructionLedger::new, ConstructionLedger::load, null);

    private final List<ConstructionSite> sites = new ArrayList<>();
    private int nextSiteId = 1;

    public static ConstructionLedger get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    public ConstructionSite addSite(ResourceLocation templateId, BlockPos origin,
                                    Rotation rotation, long now) {
        ConstructionSite site = new ConstructionSite(nextSiteId++, templateId,
                origin.immutable(), rotation, now);
        sites.add(site);
        setDirty();
        return site;
    }

    @Nullable
    public ConstructionSite getSite(int id) {
        for (ConstructionSite site : sites) {
            if (site.id == id) {
                return site;
            }
        }
        return null;
    }

    public boolean cancelSite(int id) {
        boolean removed = sites.removeIf(site -> site.id == id);
        if (removed) {
            setDirty();
        }
        return removed;
    }

    public List<ConstructionSite> allSites() {
        return Collections.unmodifiableList(sites);
    }

    /** Sites of the given status whose origin lies in range of the anchor, oldest first. */
    public List<ConstructionSite> sitesNear(BlockPos anchor, int range, Status status) {
        return sites.stream()
                .filter(site -> site.status == status)
                .filter(site -> site.origin.closerThan(anchor, range))
                .sorted(Comparator.comparingLong(site -> site.created))
                .toList();
    }

    public void setStatus(ConstructionSite site, Status status) {
        if (site.status != status) {
            site.status = status;
            setDirty();
        }
    }

    public void setStaging(ConstructionSite site, @Nullable BlockPos staging) {
        site.staging = staging == null ? null : staging.immutable();
        setDirty();
    }

    public void setSiteRequest(ConstructionSite site, String filter, int requestId) {
        site.requests.put(filter, requestId);
        setDirty();
    }

    public void clearSiteRequests(ConstructionSite site) {
        if (!site.requests.isEmpty()) {
            site.requests.clear();
            setDirty();
        }
    }

    public void addScaffold(ConstructionSite site, BlockPos pos) {
        if (site.scaffold.add(pos.immutable())) {
            setDirty();
        }
    }

    public void removeScaffold(ConstructionSite site, BlockPos pos) {
        if (site.scaffold.remove(pos)) {
            setDirty();
        }
    }

    /** Claims a work batch. Positions already claimed by someone else are skipped. */
    public void claim(ConstructionSite site, Collection<BlockPos> positions, UUID holder, long now) {
        for (BlockPos pos : positions) {
            if (site.mayWork(pos, holder, now)) {
                site.claims.put(pos.immutable(), new Claim(holder, now + CLAIM_TTL));
            }
        }
        pruneClaims(site, now);
        setDirty();
    }

    /** Called when a builder's batch ends (done or failed) and from the death hook. */
    public void releaseClaims(UUID holder) {
        boolean changed = false;
        for (ConstructionSite site : sites) {
            changed |= site.claims.values().removeIf(claim -> claim.holder().equals(holder));
        }
        if (changed) {
            setDirty();
        }
    }

    private void pruneClaims(ConstructionSite site, long now) {
        site.claims.values().removeIf(claim -> claim.expiry() <= now);
    }

    /** Wipes everything — gametest isolation (the ledger outlives test arenas). */
    public void clear() {
        sites.clear();
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        ListTag list = new ListTag();
        for (ConstructionSite site : sites) {
            CompoundTag siteTag = new CompoundTag();
            siteTag.putInt("id", site.id);
            siteTag.putString("template", site.templateId.toString());
            siteTag.putLong("origin", site.origin.asLong());
            siteTag.putString("rotation", site.rotation.name());
            siteTag.putLong("created", site.created);
            siteTag.putString("status", site.status.name());
            if (site.staging != null) {
                siteTag.putLong("staging", site.staging.asLong());
            }
            ListTag scaffold = new ListTag();
            for (BlockPos pos : site.scaffold) {
                scaffold.add(LongTag.valueOf(pos.asLong()));
            }
            siteTag.put("scaffold", scaffold);
            ListTag claims = new ListTag();
            for (Map.Entry<BlockPos, Claim> entry : site.claims.entrySet()) {
                CompoundTag claimTag = new CompoundTag();
                claimTag.putLong("pos", entry.getKey().asLong());
                claimTag.putUUID("holder", entry.getValue().holder());
                claimTag.putLong("expiry", entry.getValue().expiry());
                claims.add(claimTag);
            }
            siteTag.put("claims", claims);
            ListTag requests = new ListTag();
            for (Map.Entry<String, Integer> entry : site.requests.entrySet()) {
                CompoundTag requestTag = new CompoundTag();
                requestTag.putString("filter", entry.getKey());
                requestTag.putInt("id", entry.getValue());
                requests.add(requestTag);
            }
            siteTag.put("requests", requests);
            list.add(siteTag);
        }
        tag.put("sites", list);
        tag.putInt("next_site_id", nextSiteId);
        return tag;
    }

    private static ConstructionLedger load(CompoundTag tag, HolderLookup.Provider provider) {
        ConstructionLedger ledger = new ConstructionLedger();
        for (Tag entry : tag.getList("sites", Tag.TAG_COMPOUND)) {
            if (!(entry instanceof CompoundTag siteTag)) {
                continue;
            }
            ResourceLocation templateId = ResourceLocation.tryParse(siteTag.getString("template"));
            if (templateId == null) {
                continue;
            }
            Rotation rotation;
            try {
                rotation = Rotation.valueOf(siteTag.getString("rotation"));
            } catch (IllegalArgumentException e) {
                rotation = Rotation.NONE;
            }
            ConstructionSite site = new ConstructionSite(siteTag.getInt("id"), templateId,
                    BlockPos.of(siteTag.getLong("origin")), rotation, siteTag.getLong("created"));
            Status status = Status.byName(siteTag.getString("status"));
            site.status = status != null ? status : Status.OPEN;
            if (siteTag.contains("staging")) {
                site.staging = BlockPos.of(siteTag.getLong("staging"));
            }
            for (Tag pos : siteTag.getList("scaffold", Tag.TAG_LONG)) {
                if (pos instanceof LongTag longTag) {
                    site.scaffold.add(BlockPos.of(longTag.getAsLong()));
                }
            }
            for (Tag claim : siteTag.getList("claims", Tag.TAG_COMPOUND)) {
                if (claim instanceof CompoundTag claimTag) {
                    site.claims.put(BlockPos.of(claimTag.getLong("pos")),
                            new Claim(claimTag.getUUID("holder"), claimTag.getLong("expiry")));
                }
            }
            for (Tag request : siteTag.getList("requests", Tag.TAG_COMPOUND)) {
                if (request instanceof CompoundTag requestTag) {
                    site.requests.put(requestTag.getString("filter"), requestTag.getInt("id"));
                }
            }
            ledger.sites.add(site);
        }
        ledger.nextSiteId = Math.max(1, tag.getInt("next_site_id"));
        return ledger;
    }
}
