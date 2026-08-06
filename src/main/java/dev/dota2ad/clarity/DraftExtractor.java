package dev.dota2ad.clarity;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        List<Object> swaps;

        Result(List<Object> poolItems, List<Object> heroPool, List<Object> picks, List<Object> heroPicks, List<Object> abilityMappings, List<Object> swaps) {
            this.poolItems = poolItems;
            this.heroPool = heroPool;
            this.picks = picks;
            this.heroPicks = heroPicks;
            this.abilityMappings = abilityMappings;
            this.swaps = swaps;
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
            System.err.println("Usage: java -jar clarity-ad-parser.jar --in /path/to/match.dem");
            System.exit(2);
        }

        if (!Files.exists(in)) {
            System.err.println("Input file not found: " + in);
            System.exit(2);
        }

        ExtractProcessor proc = new ExtractProcessor();
        new SimpleRunner(new MappedFileSource(in.toFile())).runWith(proc);
        Result result = new Result(proc.buildPoolItems(), proc.buildHeroPool(), proc.buildPicks(), proc.buildHeroPicks(), proc.buildAbilityMappings(), proc.buildSwaps());
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
        if (!res.swaps.isEmpty()) {
            output.put("swaps", res.swaps);
        }

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
    // heroID -> heroKey mapping (stable across swaps, built from entities)
    private final Map<Integer, String> heroIdToHeroKey = new HashMap<>();
    // Track which hero class names we've already processed for heroIdToHeroKey
    private final Set<String> processedHeroClasses = new HashSet<>();
    // Cache heroClassName -> draft-time playerSlot (handles early swaps where entity playerID is post-swap)
    private final Map<String, Integer> heroClassToDraftSlot = new HashMap<>();
    // Track hero pool playerIDs after draft to detect swap timing
    private final Map<Integer, Integer> postDraftHeroPlayerID = new HashMap<>();
    private final List<Map<String, Object>> swaps = new ArrayList<>();
    private boolean poolExtracted = false;
    private boolean inDraft = false;
    private boolean draftEnded = false;
    private Entity gamerules = null;

    // Draft meta tracking
    private int lastRoundNumber = -1;
    private int lastAdvanceSteps = -1;
    private int lastPhase = -1;
    private boolean lastHasPicked = false;
    private int turnStartTick = -1;

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
            // trackPicks runs first so it can read turnStartTick before trackDraftMeta
            // advances it for the next turn (matters for random picks where advance
            // change and playerID change happen in the same entity update)
            if (inDraft && state == GAME_STATE_PICKING) {
                trackPicks(ctx, entity);
                trackDraftMeta(ctx, entity);
            }

            // End draft tracking when state changes
            if (inDraft && state != GAME_STATE_PICKING) {
                inDraft = false;
                draftEnded = true;
                captureFinalSlotAssignments(entity);
                // Snapshot hero pool playerIDs at draft end for swap detection
                for (int i = 0; i < heroPool.size(); i++) {
                    Integer pid = heroIndexToPlayerID.get(i);
                    if (pid != null) {
                        postDraftHeroPlayerID.put(i, pid);
                    }
                }
            }

            // Track hero pool playerID changes after draft to detect swap timing
            if (draftEnded) {
                trackSwaps(ctx, entity);
            }
        }

        // Track HERO entities after draft ends to resolve ability names
        if (draftEnded && dtName.startsWith("CDOTA_Unit_Hero_")) {
            processHeroEntity(entity, dtName);
        }
    }

    /**
     * Build draftPlayerSlot -> finalPlayerSlot swap mapping.
     * Compares who drafted each hero (heroIndexToPlayerID) with who plays it
     * after swaps (gamerules final state). In AD swaps, both hero body and
     * abilities follow the swap.
     */
    private Map<Integer, Integer> buildSwapMap() {
        Map<Integer, Integer> swapMap = new HashMap<>();
        for (int i = 0; i < heroPool.size(); i++) {
            String idx = String.format("%04d", i);
            String playerIdProp = "m_pGameRules.m_AbilityDraftHeroes." + idx + ".m_unPlayerID";
            if (!gamerules.hasProperty(playerIdProp)) continue;
            Integer finalPlayerId = safeInt(gamerules.getProperty(playerIdProp));
            Integer draftPlayerId = heroIndexToPlayerID.get(i);
            if (finalPlayerId == null || draftPlayerId == null) continue;
            if (finalPlayerId == UNPICKED_PLAYER_ID || draftPlayerId == UNPICKED_PLAYER_ID) continue;
            if (!finalPlayerId.equals(draftPlayerId)) {
                int draftSlot = convertPlayerIdToSlot(draftPlayerId);
                int finalSlot = convertPlayerIdToSlot(finalPlayerId);
                swapMap.put(draftSlot, finalSlot);
            }
        }
        return swapMap;
    }

    /**
     * Build playerID -> heroID mapping from gamerules post-swap state.
     */
    private Map<Integer, Integer> buildPlayerIdToHeroId() {
        Map<Integer, Integer> map = new HashMap<>();
        for (int i = 0; i < heroPool.size(); i++) {
            String idx = String.format("%04d", i);
            String heroIdProp = "m_pGameRules.m_AbilityDraftHeroes." + idx + ".m_nHeroID";
            String playerIdProp = "m_pGameRules.m_AbilityDraftHeroes." + idx + ".m_unPlayerID";
            if (!gamerules.hasProperty(heroIdProp)) continue;
            Integer heroId = safeInt(gamerules.getProperty(heroIdProp));
            Integer playerId = safeInt(gamerules.getProperty(playerIdProp));
            if (heroId != null && playerId != null && playerId != UNPICKED_PLAYER_ID) {
                map.put(playerId, heroId);
            }
        }
        return map;
    }

    private void processHeroEntity(Entity hero, String heroClassName) {
        Integer playerId = null;
        if (hero.hasProperty("m_iPlayerID")) {
            playerId = safeInt(hero.getProperty("m_iPlayerID"));
        }
        if (playerId == null && hero.hasProperty("m_nPlayerID")) {
            playerId = safeInt(hero.getProperty("m_nPlayerID"));
        }

        if (playerId == null) {
            return;
        }

        String heroKey = convertHeroClassNameToKey(heroClassName);

        // Find the gamerules hero pool entry matching this entity's playerID.
        // From that, get the draft-time playerID (which may differ for early swaps
        // where entity playerIDs are already post-swap).
        if (!processedHeroClasses.contains(heroClassName)) {
            processedHeroClasses.add(heroClassName);
            for (int i = 0; i < heroPool.size(); i++) {
                String idx = String.format("%04d", i);
                String pidProp = "m_pGameRules.m_AbilityDraftHeroes." + idx + ".m_unPlayerID";
                String hidProp = "m_pGameRules.m_AbilityDraftHeroes." + idx + ".m_nHeroID";
                if (!gamerules.hasProperty(pidProp)) continue;
                Integer grPid = safeInt(gamerules.getProperty(pidProp));
                if (grPid != null && grPid.equals(playerId)) {
                    Integer heroId = safeInt(gamerules.getProperty(hidProp));
                    if (heroId != null) {
                        heroIdToHeroKey.put(heroId, heroKey);
                    }
                    // Cache draft-time slot for ability resolution
                    Integer draftPid = heroIndexToPlayerID.get(i);
                    if (draftPid != null) {
                        heroClassToDraftSlot.put(heroClassName, convertPlayerIdToSlot(draftPid));
                    }
                    break;
                }
            }
        }
        int draftPlayerSlot = heroClassToDraftSlot.getOrDefault(heroClassName, convertPlayerIdToSlot(playerId));

        // Extract abilities from m_vecAbilities - slots 0, 1, 2, 5 are the draft abilities
        int[] heroSlots = {0, 1, 2, 5};
        for (int i = 0; i < 4; i++) {
            int abilitySlot = i;
            int heroSlot = heroSlots[i];

            String slotKey = draftPlayerSlot + ":" + abilitySlot;

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

    private void trackDraftMeta(Context ctx, Entity gamerules) {
        Integer advance = safeInt(gamerules.getProperty("m_pGameRules.m_nAbilityDraftAdvanceSteps"));
        Integer round = safeInt(gamerules.getProperty("m_pGameRules.m_nAbilityDraftRoundNumber"));
        Integer phase = safeInt(gamerules.getProperty("m_pGameRules.m_nAbilityDraftPhase"));
        Object hasPicked = gamerules.getProperty("m_pGameRules.m_bAbilityDraftCurrentPlayerHasPicked");

        int a = advance != null ? advance : -1;
        int r = round != null ? round : -1;
        int p = phase != null ? phase : -1;
        boolean hp = hasPicked instanceof Boolean ? (Boolean) hasPicked : false;

        // New turn starts: advance/round/phase changed and hasPicked=false
        if (!hp && (p == 0 || p == 1) && (a != lastAdvanceSteps || r != lastRoundNumber || p != lastPhase)) {
            turnStartTick = ctx.getTick();
        }

        lastAdvanceSteps = a;
        lastRoundNumber = r;
        lastPhase = p;
        lastHasPicked = hp;
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
            poolItem.put("draft_ability_id", abilityId);
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
        // Read hasPicked once for all pick detections in this update
        Object hasPickedObj = gamerules.getProperty("m_pGameRules.m_bAbilityDraftCurrentPlayerHasPicked");
        boolean hasPicked = hasPickedObj instanceof Boolean ? (Boolean) hasPickedObj : false;

        // Track hero picks
        for (int i = 0; i < heroPool.size(); i++) {
            String indexStr = String.format("%04d", i);
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
                boolean isRandom = !hasPicked;
                int pickDuration = turnStartTick >= 0 ? ctx.getTick() - turnStartTick : -1;

                Map<String, Object> heroPick = new HashMap<>();
                heroPick.put("tick", ctx.getTick());
                heroPick.put("player_slot", convertPlayerIdToSlot(currentPlayerId));
                heroPick.put("is_random", isRandom);
                heroPick.put("pick_duration", pickDuration);
                heroPicks.add(heroPick);

                heroIndexToPlayerID.put(i, currentPlayerId);
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
                    boolean isRandom = !hasPicked;
                    int pickDuration = turnStartTick >= 0 ? ctx.getTick() - turnStartTick : -1;

                    Map<String, Object> pick = new HashMap<>();
                    pick.put("tick", ctx.getTick());
                    pick.put("draft_ability_id", abilityId);
                    pick.put("player_slot", playerSlot);
                    pick.put("is_random", isRandom);
                    pick.put("pick_duration", pickDuration);
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

    private void trackSwaps(Context ctx, Entity gamerules) {
        for (int i = 0; i < heroPool.size(); i++) {
            String idx = String.format("%04d", i);
            String playerIdProp = "m_pGameRules.m_AbilityDraftHeroes." + idx + ".m_unPlayerID";
            if (!gamerules.hasProperty(playerIdProp)) continue;
            Integer currentPlayerId = safeInt(gamerules.getProperty(playerIdProp));
            if (currentPlayerId == null || currentPlayerId == UNPICKED_PLAYER_ID) continue;
            Integer previousPlayerId = postDraftHeroPlayerID.get(i);
            if (previousPlayerId == null) continue;
            if (!currentPlayerId.equals(previousPlayerId)) {
                int fromSlot = convertPlayerIdToSlot(previousPlayerId);
                int toSlot = convertPlayerIdToSlot(currentPlayerId);
                // Only record once per pair (lower slot first)
                if (fromSlot < toSlot) {
                    Map<String, Object> swap = new HashMap<>();
                    swap.put("tick", ctx.getTick());
                    swap.put("slot_a", fromSlot);
                    swap.put("slot_b", toSlot);
                    swaps.add(swap);
                }
                postDraftHeroPlayerID.put(i, currentPlayerId);
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
        Map<Integer, Integer> swapMap = buildSwapMap();
        if (!swapMap.isEmpty()) {
            for (Map<String, Object> pick : picks) {
                int slot = (Integer) pick.get("player_slot");
                int newSlot = swapMap.getOrDefault(slot, slot);
                if (slot != newSlot) {
                    pick.put("original_player_slot", slot);
                }
                pick.put("player_slot", newSlot);
            }
        }
        return new ArrayList<>(picks);
    }

    public List<Object> buildHeroPicks() {
        // Apply swap map first: remap player_slots to post-swap state
        Map<Integer, Integer> swapMap = buildSwapMap();
        if (!swapMap.isEmpty()) {
            for (Map<String, Object> heroPick : heroPicks) {
                int slot = (Integer) heroPick.get("player_slot");
                int newSlot = swapMap.getOrDefault(slot, slot);
                if (slot != newSlot) {
                    heroPick.put("original_player_slot", slot);
                }
                heroPick.put("player_slot", newSlot);
            }
        }
        // Resolve hero_id and hero_key from gamerules final state (after all swaps)
        Map<Integer, Integer> pidToHeroId = buildPlayerIdToHeroId();
        for (Map<String, Object> heroPick : heroPicks) {
            int playerSlot = (Integer) heroPick.get("player_slot");
            int playerId = playerSlot < 128 ? playerSlot * 2 : 10 + (playerSlot - 128) * 2;
            Integer heroId = pidToHeroId.get(playerId);
            if (heroId != null) {
                heroPick.put("hero_id", heroId);
                String heroKey = heroIdToHeroKey.get(heroId);
                if (heroKey != null) {
                    heroPick.put("hero_key", heroKey);
                }
            }
        }
        return new ArrayList<>(heroPicks);
    }

    public List<Object> buildAbilityMappings() {
        Map<Integer, Integer> swapMap = buildSwapMap();
        // Build ability mappings from draft ability IDs to resolved ability keys
        for (Map.Entry<Integer, Map<String, Integer>> entry : draftAbilityAssignments.entrySet()) {
            Integer draftAbilityId = entry.getKey();
            Map<String, Integer> assignment = entry.getValue();

            Integer playerSlot = assignment.get("player_slot");
            Integer abilitySlot = assignment.get("ability_slot");

            if (playerSlot == null || abilitySlot == null) {
                continue;
            }

            // Lookup uses draft-time slot (matches how resolvedAbilities was stored)
            String slotKey = playerSlot + ":" + abilitySlot;
            String abilityKey = resolvedAbilities.get(slotKey);

            if (abilityKey != null) {
                int finalSlot = swapMap.getOrDefault(playerSlot, playerSlot);
                Map<String, Object> mapping = new HashMap<>();
                if (finalSlot != playerSlot) {
                    mapping.put("original_player_slot", playerSlot);
                }
                mapping.put("draft_ability_id", draftAbilityId);
                mapping.put("player_slot", finalSlot);
                mapping.put("ability_slot", abilitySlot);
                mapping.put("ability_key", abilityKey);
                abilityMappings.add(mapping);
            }
        }

        return new ArrayList<>(abilityMappings);
    }

    public List<Object> buildSwaps() {
        return new ArrayList<>(swaps);
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
