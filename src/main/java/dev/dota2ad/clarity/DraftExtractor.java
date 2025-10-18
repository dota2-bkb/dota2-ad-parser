package dev.dota2ad.clarity;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import skadistats.clarity.event.Insert;
import skadistats.clarity.model.Entity;
import skadistats.clarity.model.FieldPath;
import skadistats.clarity.processor.entities.Entities;
import skadistats.clarity.processor.entities.OnEntityUpdated;
import skadistats.clarity.processor.entities.UsesEntities;
import skadistats.clarity.processor.runner.Context;
import skadistats.clarity.processor.runner.SimpleRunner;
import skadistats.clarity.source.MappedFileSource;

public class DraftExtractor {
    private static class Result {
        List<Object> poolItems;
        List<Object> heroPool;
        List<Object> picks;
        List<Object> heroPicks;
        List<Object> abilityMappings;

        Result(List<Object> poolItems, List<Object> heroPool, List<Object> picks, List<Object> heroPicks, List<Object> abilityMappings) {
            this.poolItems = poolItems;
            this.heroPool = heroPool;
            this.picks = picks;
            this.heroPicks = heroPicks;
            this.abilityMappings = abilityMappings;
        }
    }

    public static void main(String[] args) throws Exception {
        Path in = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--in" -> in = Path.of(args[++i]);
                default -> {
                    // ignore unknown flags
                }
            }
        }

        if (in == null) {
            System.err.println("Usage: java -jar clarity-ad-parser.jar --in /path/to/match.dem --json");
            System.exit(2);
        }

        if (!Files.exists(in)) {
            System.err.println("Input file not found: " + in);
            System.exit(2);
        }

        ExtractProcessor proc = new ExtractProcessor();
        new SimpleRunner(new MappedFileSource(in.toFile())).runWith(proc);
        Result result = new Result(proc.buildPoolItems(), proc.buildHeroPool(), proc.buildPicks(), proc.buildHeroPicks(), proc.buildAbilityMappings());

        writeJson(result, System.out);
    }

    private static void writeJson(Result res, OutputStream out) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> output = new HashMap<>();
        output.put("pool_items", res.poolItems);
        output.put("hero_pool", res.heroPool);
        output.put("picks", res.picks);
        output.put("hero_picks", res.heroPicks);
        output.put("ability_mappings", res.abilityMappings);

        mapper.writerWithDefaultPrettyPrinter().writeValue(out, output);
    }
}

@UsesEntities
class ExtractProcessor {
    @Insert
    private Entities entities;

    private final List<Map<String, Object>> poolItems = new ArrayList<>();
    private final List<Map<String, Object>> heroPool = new ArrayList<>();
    private final List<Map<String, Object>> picks = new ArrayList<>();
    private final List<Map<String, Object>> heroPicks = new ArrayList<>();
    private final List<Map<String, Object>> abilityMappings = new ArrayList<>();

    // Track which abilities have been picked
    private final Map<Integer, Integer> abilityIndexToPlayerID = new HashMap<>();
    // Track which heroes have been picked
    private final Map<Integer, Integer> heroIndexToPlayerID = new HashMap<>();
    // Track draft ability ID -> (player_slot, ability_slot)
    private final Map<Integer, Map<String, Integer>> draftAbilityAssignments = new HashMap<>();
    // Track resolved abilities: (player_slot, ability_slot) -> ability_key
    private final Map<String, String> resolvedAbilities = new HashMap<>();

    private boolean poolExtracted = false;
    private boolean inDraft = false;
    private boolean draftEnded = false;
    private Entity gamerules = null;

    private static final int GAME_STATE_PICKING = 2;
    private static final int UNPICKED_PLAYER_ID = 20;

    @OnEntityUpdated
    public void onEntityUpdated(Context ctx, Entity entity, FieldPath[] indices, int count) {
        String dtName = entity.getDtClass().getDtName();
        if (dtName == null) return;

        if (dtName.equals("CDOTAGamerulesProxy")) {
            if (gamerules == null) {
                gamerules = entity;
            }

            if (!entity.hasProperty("m_pGameRules.m_nGameState")) {
                return;
            }
            Integer state = safeInt(entity.getProperty("m_pGameRules.m_nGameState"));
            if (state == null) return;

            // Extract pool when draft starts
            if (state == GAME_STATE_PICKING && !poolExtracted) {
                extractPool(entity);
                poolExtracted = true;
                inDraft = true;
            }

            // Track picks during draft
            if (inDraft && state == GAME_STATE_PICKING) {
                trackPicks(ctx, entity);
            }

            // End draft tracking when state changes
            if (inDraft && state != GAME_STATE_PICKING) {
                inDraft = false;
                draftEnded = true;
                captureFinalSlotAssignments(entity);
            }
        }

        // Track HERO entities after draft ends to resolve ability names
        if (draftEnded && dtName.startsWith("CDOTA_Unit_Hero_")) {
            processHeroEntity(entity, dtName);
        }
    }

    private void processHeroEntity(Entity hero, String heroClassName) {
        Integer playerId = safeInt(hero.getProperty("m_iPlayerID"));
        if (playerId == null) {
            playerId = safeInt(hero.getProperty("m_nPlayerID"));
        }

        if (playerId == null) {
            return;
        }

        int playerSlot = convertPlayerIdToSlot(playerId);
        String heroKey = convertHeroClassNameToKey(heroClassName);

        // Update hero_picks with hero_key
        for (Map<String, Object> heroPick : heroPicks) {
            if ((Integer) heroPick.get("player_slot") == playerSlot && !heroPick.containsKey("hero_key")) {
                heroPick.put("hero_key", heroKey);
                break;
            }
        }

        // Extract abilities from m_vecAbilities - slots 0, 1, 2, 5 are the draft abilities
        int[] heroSlots = {0, 1, 2, 5};
        for (int i = 0; i < 4; i++) {
            int abilitySlot = i;
            int heroSlot = heroSlots[i];

            String slotKey = playerSlot + ":" + abilitySlot;

            // Skip if already resolved
            if (resolvedAbilities.containsKey(slotKey)) {
                continue;
            }

            String abilityHandleProp = "m_vecAbilities." + String.format("%04d", heroSlot);
            if (!hero.hasProperty(abilityHandleProp)) {
                continue;
            }

            Integer handle = safeInt(hero.getProperty(abilityHandleProp));
            if (handle == null || handle <= 0) {
                continue;
            }

            Entity abilityEntity = entities.getByHandle(handle);
            if (abilityEntity == null) {
                continue;
            }

            String abilityClassName = abilityEntity.getDtClass().getDtName();
            if (abilityClassName == null || !abilityClassName.startsWith("CDOTA_Ability_")) {
                continue;
            }

            String abilityKey = convertAbilityClassNameToKey(abilityClassName);
            resolvedAbilities.put(slotKey, abilityKey);
        }
    }

    private static String convertAbilityClassNameToKey(String className) {
        // CDOTA_Ability_Tinker_Laser -> tinker_laser
        if (!className.startsWith("CDOTA_Ability_")) {
            return className;
        }

        String withoutPrefix = className.substring("CDOTA_Ability_".length());

        StringBuilder result = new StringBuilder();
        boolean lastWasUnderscore = true;
        for (int i = 0; i < withoutPrefix.length(); i++) {
            char c = withoutPrefix.charAt(i);
            if (c == '_') {
                if (!lastWasUnderscore) {
                    result.append('_');
                    lastWasUnderscore = true;
                }
            } else if (Character.isUpperCase(c)) {
                if (i > 0 && !lastWasUnderscore) {
                    result.append('_');
                }
                result.append(Character.toLowerCase(c));
                lastWasUnderscore = false;
            } else {
                result.append(c);
                lastWasUnderscore = false;
            }
        }

        return result.toString();
    }

    private static String convertHeroClassNameToKey(String className) {
        // CDOTA_Unit_Hero_Pangolier -> pangolier
        if (!className.startsWith("CDOTA_Unit_Hero_")) {
            return className;
        }

        String withoutPrefix = className.substring("CDOTA_Unit_Hero_".length());

        StringBuilder result = new StringBuilder();
        boolean lastWasUnderscore = true;
        for (int i = 0; i < withoutPrefix.length(); i++) {
            char c = withoutPrefix.charAt(i);
            if (c == '_') {
                if (!lastWasUnderscore) {
                    result.append('_');
                    lastWasUnderscore = true;
                }
            } else if (Character.isUpperCase(c)) {
                if (i > 0 && !lastWasUnderscore) {
                    result.append('_');
                }
                result.append(Character.toLowerCase(c));
                lastWasUnderscore = false;
            } else {
                result.append(c);
                lastWasUnderscore = false;
            }
        }

        return result.toString();
    }

    private void extractPool(Entity gamerules) {
        // Read all abilities from m_pGameRules.m_AbilityDraftAbilities array
        for (int i = 0; i < 256; i++) {
            String indexStr = String.format("%04d", i);
            String abilityIdProp = "m_pGameRules.m_AbilityDraftAbilities." + indexStr + ".m_nAbilityID";

            if (!gamerules.hasProperty(abilityIdProp)) {
                break;
            }

            Integer abilityId = safeInt(gamerules.getProperty(abilityIdProp));
            if (abilityId == null || abilityId == 0) {
                break;
            }

            Map<String, Object> poolItem = new HashMap<>();
            poolItem.put("ability_id", abilityId);
            poolItems.add(poolItem);

            abilityIndexToPlayerID.put(i, UNPICKED_PLAYER_ID);
        }

        // Read all heroes from m_pGameRules.m_AbilityDraftHeroes array
        for (int i = 0; i < 20; i++) {
            String indexStr = String.format("%04d", i);
            String heroIdProp = "m_pGameRules.m_AbilityDraftHeroes." + indexStr + ".m_nHeroID";

            if (!gamerules.hasProperty(heroIdProp)) {
                break;
            }

            Integer heroId = safeInt(gamerules.getProperty(heroIdProp));
            if (heroId == null || heroId == 0) {
                break;
            }

            Map<String, Object> hero = new HashMap<>();
            hero.put("hero_id", heroId);
            heroPool.add(hero);

            heroIndexToPlayerID.put(i, UNPICKED_PLAYER_ID);
        }
    }

    private void trackPicks(Context ctx, Entity gamerules) {
        // Track hero picks
        for (int i = 0; i < heroPool.size(); i++) {
            String indexStr = String.format("%04d", i);
            String heroIdProp = "m_pGameRules.m_AbilityDraftHeroes." + indexStr + ".m_nHeroID";
            String playerIdProp = "m_pGameRules.m_AbilityDraftHeroes." + indexStr + ".m_unPlayerID";

            if (!gamerules.hasProperty(playerIdProp)) {
                continue;
            }

            Integer currentPlayerId = safeInt(gamerules.getProperty(playerIdProp));
            if (currentPlayerId == null) {
                continue;
            }

            Integer previousPlayerId = heroIndexToPlayerID.get(i);
            if (previousPlayerId == null) {
                previousPlayerId = UNPICKED_PLAYER_ID;
            }

            // Detect pick: playerID changed from 20 to actual player ID
            if (previousPlayerId == UNPICKED_PLAYER_ID && currentPlayerId != UNPICKED_PLAYER_ID) {
                Integer heroId = safeInt(gamerules.getProperty(heroIdProp));

                if (heroId != null && heroId != 0) {
                    Map<String, Object> heroPick = new HashMap<>();
                    heroPick.put("tick", ctx.getTick());
                    heroPick.put("hero_id", heroId);
                    heroPick.put("player_slot", convertPlayerIdToSlot(currentPlayerId));
                    heroPicks.add(heroPick);

                    heroIndexToPlayerID.put(i, currentPlayerId);
                }
            }
        }

        // Track ability picks
        for (int i = 0; i < poolItems.size(); i++) {
            String indexStr = String.format("%04d", i);
            String playerIdProp = "m_pGameRules.m_AbilityDraftAbilities." + indexStr + ".m_unPlayerID";
            String abilityIdProp = "m_pGameRules.m_AbilityDraftAbilities." + indexStr + ".m_nAbilityID";

            if (!gamerules.hasProperty(playerIdProp)) {
                continue;
            }

            Integer currentPlayerId = safeInt(gamerules.getProperty(playerIdProp));
            if (currentPlayerId == null) {
                continue;
            }

            Integer previousPlayerId = abilityIndexToPlayerID.get(i);
            if (previousPlayerId == null) {
                previousPlayerId = UNPICKED_PLAYER_ID;
            }

            // Detect pick: playerID changed from 20 to actual player ID
            if (previousPlayerId == UNPICKED_PLAYER_ID && currentPlayerId != UNPICKED_PLAYER_ID) {
                Integer abilityId = safeInt(gamerules.getProperty(abilityIdProp));

                if (abilityId != null) {
                    int playerSlot = convertPlayerIdToSlot(currentPlayerId);

                    Map<String, Object> pick = new HashMap<>();
                    pick.put("tick", ctx.getTick());
                    pick.put("ability_id", abilityId);
                    pick.put("player_slot", playerSlot);
                    picks.add(pick);

                    // Track assignment for later mapping
                    Map<String, Integer> assignment = new HashMap<>();
                    assignment.put("player_slot", playerSlot);
                    draftAbilityAssignments.put(abilityId, assignment);

                    abilityIndexToPlayerID.put(i, currentPlayerId);
                }
            }
        }
    }

    private void captureFinalSlotAssignments(Entity gamerules) {
        // Capture final slot assignments after draft ends
        for (int i = 0; i < poolItems.size(); i++) {
            String indexStr = String.format("%04d", i);
            String playerIdProp = "m_pGameRules.m_AbilityDraftAbilities." + indexStr + ".m_unPlayerID";
            String slotProp = "m_pGameRules.m_AbilityDraftAbilities." + indexStr + ".m_unAbilityPlayerSlot";
            String abilityIdProp = "m_pGameRules.m_AbilityDraftAbilities." + indexStr + ".m_nAbilityID";

            if (!gamerules.hasProperty(playerIdProp)) {
                continue;
            }

            Integer playerId = safeInt(gamerules.getProperty(playerIdProp));
            if (playerId == null || playerId == UNPICKED_PLAYER_ID) {
                continue;
            }

            Integer abilityId = safeInt(gamerules.getProperty(abilityIdProp));
            Integer abilitySlot = safeInt(gamerules.getProperty(slotProp));

            if (abilityId == null || abilitySlot == null) {
                continue;
            }

            Map<String, Integer> assignment = draftAbilityAssignments.get(abilityId);
            if (assignment != null) {
                assignment.put("ability_slot", abilitySlot);
            }
        }
    }

    public List<Object> buildPoolItems() {
        return new ArrayList<>(poolItems);
    }

    public List<Object> buildHeroPool() {
        return new ArrayList<>(heroPool);
    }

    public List<Object> buildPicks() {
        return new ArrayList<>(picks);
    }

    public List<Object> buildHeroPicks() {
        return new ArrayList<>(heroPicks);
    }

    public List<Object> buildAbilityMappings() {
        // Build ability mappings from draft ability IDs to resolved ability keys
        for (Map.Entry<Integer, Map<String, Integer>> entry : draftAbilityAssignments.entrySet()) {
            Integer draftAbilityId = entry.getKey();
            Map<String, Integer> assignment = entry.getValue();

            Integer playerSlot = assignment.get("player_slot");
            Integer abilitySlot = assignment.get("ability_slot");

            if (playerSlot == null || abilitySlot == null) {
                continue;
            }

            String slotKey = playerSlot + ":" + abilitySlot;
            String abilityKey = resolvedAbilities.get(slotKey);

            if (abilityKey != null) {
                Map<String, Object> mapping = new HashMap<>();
                mapping.put("draft_ability_id", draftAbilityId);
                mapping.put("player_slot", playerSlot);
                mapping.put("ability_slot", abilitySlot);
                mapping.put("ability_key", abilityKey);
                abilityMappings.add(mapping);
            }
        }

        return new ArrayList<>(abilityMappings);
    }

    private static Integer safeInt(Object o) {
        if (o == null) return null;
        if (o instanceof Integer i) return i;
        if (o instanceof Number n) return n.intValue();
        return null;
    }

    private static int convertPlayerIdToSlot(int playerId) {
        // Convert player_id (from replay) to player_slot (OpenDota standard)
        // player_id: 0,2,4,6,8 (Radiant), 10,12,14,16,18 (Dire)
        // player_slot: 0,1,2,3,4 (Radiant), 128,129,130,131,132 (Dire)
        if (playerId < 10) {
            return playerId / 2;
        } else {
            return 128 + (playerId - 10) / 2;
        }
    }
}
