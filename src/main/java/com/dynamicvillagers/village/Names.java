package com.dynamicvillagers.village;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.List;
import java.util.UUID;

public final class Names {
    private static final List<String> COMMON_PREFIXES = List.of(
            "Oak", "River", "Stone", "Iron", "Green", "Ash", "Wolf", "Red", "Pine", "Fox", "Black", "High");
    private static final List<String> COMMON_SUFFIXES = List.of(
            "dale", "brook", "ford", "haven", "cross", "field", "vale", "crest", "wick", "ridge", "moor", "hollow");
    private static final List<String> DESERT_PREFIXES = List.of("Sand", "Sun", "Dune", "Cactus", "Gold", "Dry");
    private static final List<String> DESERT_SUFFIXES = List.of("reach", "rest", "well", "mesa", "spire", "haven");
    private static final List<String> SNOW_PREFIXES = List.of("Snow", "Frost", "Ice", "White", "Winter", "Pine");
    private static final List<String> SNOW_SUFFIXES = List.of("haven", "holm", "watch", "fall", "ridge", "stead");
    private static final List<String> SAVANNA_PREFIXES = List.of("Acacia", "Sun", "Red", "Lion", "Amber", "Tall");
    private static final List<String> SAVANNA_SUFFIXES = List.of("plain", "reach", "rest", "rock", "field", "cross");
    private static final List<String> TAIGA_PREFIXES = List.of("Spruce", "Wolf", "Fern", "Moss", "Cold", "Fox");
    private static final List<String> TAIGA_SUFFIXES = List.of("wood", "grove", "holt", "brook", "ridge", "den");
    private static final List<String> FIRST = List.of(
            "Al", "Ber", "Cor", "Del", "El", "Fen", "Gar", "Hal", "Is", "Jen", "Kel", "Lin", "Mar", "Nor", "Or", "Per", "Quin", "Ren", "Sol", "Tor");
    private static final List<String> LAST = List.of(
            "a", "an", "en", "in", "o", "or", "ra", "ric", "sa", "ton", "us", "wen");

    public static String village(ServerLevel level, BlockPos center) {
        String group = biomeGroup(level, center);
        List<String> prefixes = prefixes(group);
        List<String> suffixes = suffixes(group);
        long value = mix(level.getSeed() ^ center.asLong());
        return prefixes.get(index(value, prefixes.size()))
                + suffixes.get(index(value >>> 24, suffixes.size()));
    }

    public static String villager(UUID uuid) {
        long value = mix(uuid.getMostSignificantBits() ^ uuid.getLeastSignificantBits());
        return FIRST.get(index(value, FIRST.size())) + LAST.get(index(value >>> 24, LAST.size()));
    }

    public static String biomeGroup(ServerLevel level, BlockPos pos) {
        String path = level.getBiome(pos).unwrapKey()
                .map(key -> key.location().getPath()).orElse("plains");
        if (path.contains("desert") || path.contains("badlands")) return "desert";
        if (path.contains("snow") || path.contains("frozen") || path.contains("ice")) return "snowy";
        if (path.contains("savanna")) return "savanna";
        if (path.contains("taiga")) return "taiga";
        return "plains";
    }

    private static List<String> prefixes(String group) {
        return switch (group) {
            case "desert" -> DESERT_PREFIXES;
            case "snowy" -> SNOW_PREFIXES;
            case "savanna" -> SAVANNA_PREFIXES;
            case "taiga" -> TAIGA_PREFIXES;
            default -> COMMON_PREFIXES;
        };
    }

    private static List<String> suffixes(String group) {
        return switch (group) {
            case "desert" -> DESERT_SUFFIXES;
            case "snowy" -> SNOW_SUFFIXES;
            case "savanna" -> SAVANNA_SUFFIXES;
            case "taiga" -> TAIGA_SUFFIXES;
            default -> COMMON_SUFFIXES;
        };
    }

    private static int index(long value, int size) {
        return Math.floorMod((int) (value ^ value >>> 32), size);
    }

    private static long mix(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdl;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53l;
        return value ^ value >>> 33;
    }

    private Names() {
    }
}
