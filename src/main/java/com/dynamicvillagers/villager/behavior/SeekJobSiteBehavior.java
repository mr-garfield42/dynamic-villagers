package com.dynamicvillagers.villager.behavior;

import com.dynamicvillagers.villager.VillagerEssence;
import com.dynamicvillagers.villager.role.RoleProfessions;
import com.dynamicvillagers.villager.role.VillagerRole;
import com.google.common.collect.ImmutableMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;

import java.util.Optional;

/**
 * The role→profession bridge (owner decision, 2026-07-10): a villager whose Dynamic Villagers
 * role maps to a vanilla profession it does not yet hold claims the nearest free matching
 * job-site block itself, then hands off to the vanilla pipeline. We do exactly what vanilla's
 * AcquirePoi does — {@link PoiManager#take} reserves the POI ticket and we write
 * POTENTIAL_JOB_SITE — after which vanilla's GoToPotentialJobSite walks the villager there and
 * AssignProfessionFromJobSite grants the profession (and skin) once it arrives within 2 blocks.
 * We never force-set the profession, honoring the "same physical rules" rule and composing with
 * Guard Villagers / Thief. See docs/RESEARCH_PROFESSIONS.md.
 *
 * <p>The memory conditions gate this to villagers with neither a job site nor a pending one, so
 * setting POTENTIAL_JOB_SITE also suppresses vanilla's any-job AcquirePoi (which requires that
 * memory absent) — the villager can't wander off to a lectern instead. Runs at CORE priority 5,
 * ahead of vanilla's AcquirePoi at 6, so our specific claim wins the tick.
 */
public class SeekJobSiteBehavior extends Behavior<Villager> {
    private static final int SEEK_INTERVAL_TICKS = 40; // matches vanilla AcquirePoi's cadence
    private static final int SEEK_RADIUS = 48;         // matches vanilla AcquirePoi's scan

    public SeekJobSiteBehavior() {
        super(ImmutableMap.of(
                MemoryModuleType.JOB_SITE, MemoryStatus.VALUE_ABSENT,
                MemoryModuleType.POTENTIAL_JOB_SITE, MemoryStatus.VALUE_ABSENT));
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, Villager villager) {
        if (villager.isBaby() || villager.tickCount % SEEK_INTERVAL_TICKS != 0) {
            return false;
        }
        VillagerProfession target = RoleProfessions.professionFor(VillagerEssence.get(villager).getRole());
        // Only seek from NONE: a villager that already traded can never drop its profession
        // (vanilla ResetProfession requires xp==0 && level<=1), so pointing a professioned
        // villager at a new job site would claim it without ever changing the profession.
        return target != null && villager.getVillagerData().getProfession() == VillagerProfession.NONE;
    }

    @Override
    protected void start(ServerLevel level, Villager villager, long gameTime) {
        VillagerProfession target = RoleProfessions.professionFor(VillagerEssence.get(villager).getRole());
        if (target == null) {
            return;
        }
        PoiManager poi = level.getPoiManager();
        // one call: find the nearest free matching job site and reserve its ticket
        Optional<BlockPos> claimed = poi.take(
                target.acquirableJobSite(), (type, pos) -> true, villager.blockPosition(), SEEK_RADIUS);
        claimed.ifPresent(pos -> villager.getBrain().setMemory(
                MemoryModuleType.POTENTIAL_JOB_SITE, GlobalPos.of(level.dimension(), pos)));
    }
}
