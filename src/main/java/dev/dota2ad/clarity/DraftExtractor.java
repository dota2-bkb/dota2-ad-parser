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
import skadistats.clarity.model.StringTable;
import skadistats.clarity.processor.entities.Entities;
import skadistats.clarity.processor.entities.OnEntityUpdated;
import skadistats.clarity.processor.entities.UsesEntities;
import skadistats.clarity.processor.runner.Context;
import skadistats.clarity.processor.runner.SimpleRunner;
import skadistats.clarity.processor.stringtables.StringTables;
import skadistats.clarity.processor.stringtables.UsesStringTable;
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
@UsesStringTable("EntityNames")
class ExtractProcessor {
    @Insert
    private Entities entities;

    @Insert
    private StringTables stringTables;

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
    // Atomic per-player pairing of draft ability IDs to ability names.
    // Both sides (gamerules m_AbilityDraftAbilities slots, hero entity
    // m_vecAbilities) are read in the same entity-update callback so they
    // describe one arrangement epoch; joining a draft-end gamerules snapshot
    // with an entity vector observed later mispairs keys for players who
    // rearranged their abilities in between. A pairing is committed only
    // after two identical observations on different ticks, so a read that
    // straddles a hero swap or a partial ability grant can't be committed.
    private final Map<Integer, String> abilityIdToKey = new HashMap<>();
    private final Set<Integer> pairedPlayerIds = new HashSet<>();
    private final Map<Integer, String> pendingPairSignature = new HashMap<>();
    private final Map<Integer, Integer> pendingPairTick = new HashMap<>();
    // heroID -> heroKey mapping (stable across swaps, built from entities)
    private final Map<Integer, String> heroIdToHeroKey = new HashMap<>();
    // Track which hero class names we've already processed for heroIdToHeroKey
    private final Set<String> processedHeroClasses = new HashSet<>();
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

    // Per-player connection state, indexed by m_unPlayerID (0,2,4,6,8 radiant;
    // 10,12,14,16,18 dire). Updated whenever CDOTA_PlayerResource fires.
    // DOTAConnectionState_t: 0 UNKNOWN, 1 NOT_YET_CONNECTED, 2 CONNECTED,
    // 3 DISCONNECTED, 4 ABANDONED, 5 LOADING, 6 FAILED.
    // Used at pick time to populate the picker_disconnected flag on each
    // emitted pick: state != CONNECTED at the tick the pick registered, so it
    // also marks never-connected and still-loading seats, not only mid-game
    // drops. Only meaningful when is_random=True; every random pick is a
    // server-side timeout, and this flag splits timeouts by whether the
    // picker was present at that moment.
    private final Map<Integer, Integer> playerIdToConnectionState = new HashMap<>();

    private static final int GAME_STATE_PICKING = 2;
    private static final int UNPICKED_PLAYER_ID = 20;
    private static final int CONNECTION_STATE_CONNECTED = 2;

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
            processHeroEntity(ctx, entity, dtName);
        }

        // Track per-player connection state from CDOTA_PlayerResource.
        // m_vecPlayerData indices 0-4 map to player_id 0,2,4,6,8 (radiant);
        // 5-9 map to player_id 10,12,14,16,18 (dire).
        if (dtName.equals("CDOTA_PlayerResource")) {
            for (int idx = 0; idx < 10; idx++) {
                String key = String.format("m_vecPlayerData.%04d.m_iConnectionState", idx);
                if (!entity.hasProperty(key)) continue;
                Integer state = safeInt(entity.getProperty(key));
                if (state == null) continue;
                int playerId = idx < 5 ? idx * 2 : 10 + (idx - 5) * 2;
                playerIdToConnectionState.put(playerId, state);
            }
        }
    }

    /** Whether the given player was disconnected/abandoned at the current tick. */
    private boolean isPlayerDisconnected(int playerId) {
        Integer state = playerIdToConnectionState.get(playerId);
        return state != null && state != CONNECTION_STATE_CONNECTED;
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

    private void processHeroEntity(Context ctx, Entity hero, String heroClassName) {
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

        // Register heroID -> heroKey from the gamerules hero pool entry
        // matching this entity's playerID. The key comes from the EntityNames
        // string table (the npc_dota_hero_* npc name — the exact name OpenDota
        // keys heroes by, prefix stripped), the same source as ability names;
        // no class-name conversion. Unresolved at this tick -> the class is
        // not marked processed, so a later entity update retries.
        if (!processedHeroClasses.contains(heroClassName)) {
            String heroKey = heroEntityKey(hero);
            if (heroKey != null) {
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
                        break;
                    }
                }
            }
        }

        pairPlayerAbilities(ctx.getTick(), hero, playerId);
    }

    // m_vecAbilities positions of the drafted abilities: draft slots 0,1,2
    // (basics) sit at 0,1,2; the ult (draft slot 3) sits at 5. Positions 3,4
    // hold hidden/linked sub-abilities.
    private static final int[] ABILITY_VECTOR_POSITIONS = {0, 1, 2, 5};

    private void pairPlayerAbilities(int tick, Entity hero, int playerId) {
        if (pairedPlayerIds.contains(playerId) || gamerules == null) {
            return;
        }

        // Entity side: drafted ability names by draft slot, from this hero's
        // current ability vector.
        String[] names = new String[4];
        Set<String> distinct = new HashSet<>();
        for (int slot = 0; slot < 4; slot++) {
            String prop = "m_vecAbilities." + String.format("%04d", ABILITY_VECTOR_POSITIONS[slot]);
            if (!hero.hasProperty(prop)) return;
            Integer handle = safeInt(hero.getProperty(prop));
            if (handle == null || handle <= 0) return;
            Entity abilityEntity = entities.getByHandle(handle);
            if (abilityEntity == null) return;
            String name = entityName(abilityEntity);
            if (name == null || name.equals("generic_hidden") || !distinct.add(name)) return;
            names[slot] = name;
        }

        // Gamerules side: this player's draft ability IDs by current slot,
        // read in the same callback so both sides are at the same tick.
        int[] idBySlot = {-1, -1, -1, -1};
        int found = 0;
        for (int i = 0; i < poolItems.size(); i++) {
            String idx = String.format("%04d", i);
            String base = "m_pGameRules.m_AbilityDraftAbilities." + idx + ".";
            if (!gamerules.hasProperty(base + "m_unPlayerID")) continue;
            Integer pid = safeInt(gamerules.getProperty(base + "m_unPlayerID"));
            if (pid == null || pid != playerId) continue;
            Integer abilityId = safeInt(gamerules.getProperty(base + "m_nAbilityID"));
            Integer slot = safeInt(gamerules.getProperty(base + "m_unAbilityPlayerSlot"));
            if (abilityId == null || slot == null || slot < 0 || slot > 3 || idBySlot[slot] != -1) return;
            idBySlot[slot] = abilityId;
            found++;
        }
        if (found != 4) return;

        StringBuilder sig = new StringBuilder();
        for (int slot = 0; slot < 4; slot++) {
            sig.append(idBySlot[slot]).append('=').append(names[slot]).append(';');
        }
        String signature = sig.toString();

        Integer pendingTick = pendingPairTick.get(playerId);
        if (signature.equals(pendingPairSignature.get(playerId))) {
            if (tick > pendingTick) {
                for (int slot = 0; slot < 4; slot++) {
                    abilityIdToKey.put(idBySlot[slot], names[slot]);
                }
                pairedPlayerIds.add(playerId);
                pendingPairSignature.remove(playerId);
                pendingPairTick.remove(playerId);
            }
        } else {
            pendingPairSignature.put(playerId, signature);
            pendingPairTick.put(playerId, tick);
        }
    }

    private String entityName(Entity e) {
        if (!e.hasProperty("m_pEntity.m_nameStringTableIndex")) return null;
        Integer idx = safeInt(e.getProperty("m_pEntity.m_nameStringTableIndex"));
        if (idx == null || idx < 0) return null;
        StringTable table = stringTables.forName("EntityNames");
        if (table == null || !table.hasIndex(idx)) return null;
        return table.getNameByIndex(idx);
    }

    private String heroEntityKey(Entity hero) {
        // npc_dota_hero_sand_king -> sand_king
        String name = entityName(hero);
        if (name == null) return null;
        if (!name.startsWith("npc_dota_hero_")) {
            throw new IllegalStateException(
                "Hero entity's EntityNames entry is not an npc_dota_hero_* name: " + name);
        }
        return name.substring("npc_dota_hero_".length());
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
                boolean pickerDisconnected = isPlayerDisconnected(currentPlayerId);
                int pickDuration = turnStartTick >= 0 ? ctx.getTick() - turnStartTick : -1;

                Map<String, Object> heroPick = new HashMap<>();
                heroPick.put("tick", ctx.getTick());
                heroPick.put("player_slot", convertPlayerIdToSlot(currentPlayerId));
                heroPick.put("is_random", isRandom);
                heroPick.put("picker_disconnected", pickerDisconnected);
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
                    boolean pickerDisconnected = isPlayerDisconnected(currentPlayerId);
                    int pickDuration = turnStartTick >= 0 ? ctx.getTick() - turnStartTick : -1;

                    Map<String, Object> pick = new HashMap<>();
                    pick.put("tick", ctx.getTick());
                    pick.put("draft_ability_id", abilityId);
                    pick.put("player_slot", playerSlot);
                    pick.put("is_random", isRandom);
                    pick.put("picker_disconnected", pickerDisconnected);
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

            String abilityKey = abilityIdToKey.get(draftAbilityId);

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
