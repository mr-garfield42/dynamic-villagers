package com.dynamicvillagers.village;

import com.dynamicvillagers.villager.role.VillagerRole;
import net.minecraft.core.BlockPos;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

public final class Village {
    public static final int DEFAULT_RADIUS = StorageLedger.NETWORK_RANGE;

    private final int id;
    private final String name;
    private final BlockPos center;
    private final int radius;
    private final long created;
    private boolean autoStaff;
    private boolean initialPopulationSeeded;
    private int population;
    private int adults;
    private int children;
    private int beds;
    private int freeBeds;
    private int houses;
    private int openSites;
    private int guards;
    private final EnumMap<VillagerRole, Integer> roles = new EnumMap<>(VillagerRole.class);

    public Village(int id, String name, BlockPos center, int radius, long created, boolean autoStaff) {
        this.id = id;
        this.name = name;
        this.center = center.immutable();
        this.radius = radius;
        this.created = created;
        this.autoStaff = autoStaff;
    }

    public int id() { return id; }
    public String name() { return name; }
    public BlockPos center() { return center; }
    public BlockPos anchor() { return center; }
    public int radius() { return radius; }
    public int range() { return radius; }
    public long created() { return created; }
    public boolean autoStaff() { return autoStaff; }
    public boolean initialPopulationSeeded() { return initialPopulationSeeded; }
    public int population() { return population; }
    public int adults() { return adults; }
    public int children() { return children; }
    public int beds() { return beds; }
    public int freeBeds() { return freeBeds; }
    public int houses() { return houses; }
    public int openSites() { return openSites; }
    public int guards() { return guards; }
    public Map<VillagerRole, Integer> roles() { return Collections.unmodifiableMap(roles); }

    public void setAutoStaff(boolean enabled) { autoStaff = enabled; }
    public void setInitialPopulationSeeded(boolean seeded) { initialPopulationSeeded = seeded; }

    void setTallies(int adults, int children, int beds, int freeBeds, int houses,
                    int openSites, int guards, Map<VillagerRole, Integer> roleCounts) {
        this.adults = adults;
        this.children = children;
        this.population = adults + children;
        this.beds = beds;
        this.freeBeds = freeBeds;
        this.houses = houses;
        this.openSites = openSites;
        this.guards = guards;
        roles.clear();
        roles.putAll(roleCounts);
    }
}
