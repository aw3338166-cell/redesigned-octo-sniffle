package dev.clarity.aibuild;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public class AiBuildMod implements ModInitializer {
    public static final String MOD_ID = "minecraft_ai_build";
    private static final Gson GSON = new Gson();
    private static final int MAX_BLOCKS = 10_000;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).build();

    private static final class PlannedBlock {
        int x, y, z;
        String block;
        PlannedBlock(int x, int y, int z, String block) {
            this.x = x; this.y = y; this.z = z; this.block = block;
        }
    }
    private static final class Blueprint {
        String name = "Imported blueprint";
        List<PlannedBlock> blocks = new ArrayList<>();
    }
    private static final class BuildJob {
        final ServerPlayer owner;
        final ServerLevel level;
        final BlockPos origin;
        final List<PlannedBlock> blocks;
        int cursor = 0;
        int blocksPerTick = 1;
        boolean running = false;
        BuildJob(ServerPlayer owner, ServerLevel level, BlockPos origin, List<PlannedBlock> blocks) {
            this.owner = owner; this.level = level; this.origin = origin; this.blocks = blocks;
        }
    }
    private static volatile Blueprint currentBlueprint;
    private static volatile BuildJob currentJob;

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(literal("aibuild")
                .then(literal("demo").requires(s -> s.hasPermission(2)).executes(ctx -> {
                    currentBlueprint = demoBlueprint();
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "Loaded demo blueprint (" + currentBlueprint.blocks.size() + " blocks). Stand where its origin should be, then run /aibuild start."), false);
                    return 1;
                }))
                .then(literal("load").requires(s -> s.hasPermission(2))
                    .then(argument("file", StringArgumentType.word()).executes(ctx -> {
                        String file = StringArgumentType.getString(ctx, "file");
                        try {
                            Path dir = blueprintDir(ctx.getSource().getServer());
                            Path path = dir.resolve(file + ".json").normalize();
                            if (!path.startsWith(dir.normalize())) throw new IOException("Invalid file name.");
                            Blueprint loaded = GSON.fromJson(Files.readString(path), Blueprint.class);
                            validate(loaded);
                            currentBlueprint = loaded;
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "Loaded " + loaded.name + " (" + loaded.blocks.size() + " blocks)."), false);
                            return 1;
                        } catch (Exception e) {
                            ctx.getSource().sendFailure(Component.literal("Could not load blueprint: " + e.getMessage()));
                            return 0;
                        }
                    })))
                .then(literal("save-demo").requires(s -> s.hasPermission(2)).executes(ctx -> {
                    try {
                        Path dir = blueprintDir(ctx.getSource().getServer());
                        Files.createDirectories(dir);
                        Files.writeString(dir.resolve("demo.json"), GSON.toJson(demoBlueprint()));
                        ctx.getSource().sendSuccess(() -> Component.literal("Wrote demo.json to " + dir), false);
                        return 1;
                    } catch (IOException e) {
                        ctx.getSource().sendFailure(Component.literal("Save failed: " + e.getMessage()));
                        return 0;
                    }
                }))
                .then(literal("prompt").requires(s -> s.hasPermission(2))
                    .then(argument("description", StringArgumentType.greedyString()).executes(ctx -> {
                        String prompt = StringArgumentType.getString(ctx, "description");
                        String key = System.getenv("OPENAI_API_KEY");
                        if (key == null || key.isBlank()) {
                            ctx.getSource().sendFailure(Component.literal("Set OPENAI_API_KEY in the server environment first."));
                            return 0;
                        }
                        ctx.getSource().sendSuccess(() -> Component.literal("Asking AI for a blueprint…"), false);
                        MinecraftServer server = ctx.getSource().getServer();
                        var source = ctx.getSource();
                        CompletableFuture.runAsync(() -> {
                            try {
                                Blueprint generated = requestBlueprint(key, prompt);
                                validate(generated);
                                Path dir = blueprintDir(server);
                                Files.createDirectories(dir);
                                String safeName = "ai_" + System.currentTimeMillis();
                                Files.writeString(dir.resolve(safeName + ".json"), GSON.toJson(generated));
                                server.execute(() -> {
                                    currentBlueprint = generated;
                                    source.sendSuccess(() -> Component.literal(
                                            "AI blueprint ready: " + generated.blocks.size() + " blocks. Loaded in memory; also saved as " + safeName + ".json"), false);
                                });
                            } catch (Exception e) {
                                server.execute(() -> source.sendFailure(Component.literal("AI request failed: " + e.getMessage())));
                            }
                        });
                        return 1;
                    })))
                .then(literal("start").requires(s -> s.hasPermission(2))
                    .then(argument("blocksPerTick", IntegerArgumentType.integer(1, 5)).executes(ctx ->
                        startBuild(ctx.getSource().getPlayerOrException(), ctx.getSource().getServer(),
                                IntegerArgumentType.getInteger(ctx, "blocksPerTick"), ctx.getSource())))
                    .executes(ctx -> startBuild(ctx.getSource().getPlayerOrException(),
                            ctx.getSource().getServer(), 1, ctx.getSource())))
                .then(literal("pause").requires(s -> s.hasPermission(2)).executes(ctx -> {
                    if (currentJob == null) { ctx.getSource().sendFailure(Component.literal("No build job.")); return 0; }
                    currentJob.running = false;
                    ctx.getSource().sendSuccess(() -> Component.literal("Build paused at block " + currentJob.cursor + "."), false);
                    return 1;
                }))
                .then(literal("resume").requires(s -> s.hasPermission(2)).executes(ctx -> {
                    if (currentJob == null) { ctx.getSource().sendFailure(Component.literal("No build job.")); return 0; }
                    currentJob.running = true;
                    ctx.getSource().sendSuccess(() -> Component.literal("Build resumed."), false);
                    return 1;
                }))
                .then(literal("stop").requires(s -> s.hasPermission(2)).executes(ctx -> {
                    currentJob = null;
                    ctx.getSource().sendSuccess(() -> Component.literal("Build stopped. Existing blocks were left in place."), false);
                    return 1;
                }))
                .then(literal("status").requires(s -> s.hasPermission(2)).executes(ctx -> {
                    BuildJob j = currentJob;
                    if (j == null) {
                        ctx.getSource().sendSuccess(() -> Component.literal("No active build. Blueprint: " +
                                (currentBlueprint == null ? "none" : currentBlueprint.name)), false);
                    } else {
                        ctx.getSource().sendSuccess(() -> Component.literal("Build " + (j.running ? "running" : "paused") +
                                ": " + j.cursor + "/" + j.blocks.size() + " blocks."), false);
                    }
                    return 1;
                }))
            );
        });

        ServerTickEvents.END_SERVER_TICK.register(AiBuildMod::tickBuild);
    }

    private static int startBuild(ServerPlayer player, MinecraftServer server, int blocksPerTick,
                                  net.minecraft.commands.CommandSourceStack source) {
        if (!source.hasPermission(2)) {
            source.sendFailure(Component.literal("Operator permission required."));
            return 0;
        }
        if (currentBlueprint == null || currentBlueprint.blocks.isEmpty()) {
            source.sendFailure(Component.literal("No blueprint loaded. Use /aibuild demo or /aibuild prompt <description>."));
            return 0;
        }
        if (currentJob != null) {
            source.sendFailure(Component.literal("A build job already exists. Use /aibuild stop first."));
            return 0;
        }
        currentJob = new BuildJob(player, player.serverLevel(), player.blockPosition().above(), new ArrayList<>(currentBlueprint.blocks));
        currentJob.blocksPerTick = Math.max(1, Math.min(5, blocksPerTick));
        currentJob.running = true;
        source.sendSuccess(() -> Component.literal("Build started at your feet + 1, " +
                currentJob.blocks.size() + " blocks, " + currentJob.blocksPerTick + " block(s)/tick."), false);
        return 1;
    }

    private static void tickBuild(MinecraftServer server) {
        BuildJob job = currentJob;
        if (job == null || !job.running) return;
        if (!job.owner.isAlive() || job.owner.serverLevel() != job.level) {
            job.running = false;
            job.owner.sendSystemMessage(Component.literal("Build paused because you died or changed dimensions."));
            return;
        }
        int count = 0;
        while (count < job.blocksPerTick && job.cursor < job.blocks.size()) {
            PlannedBlock p = job.blocks.get(job.cursor++);
            BlockPos pos = job.origin.offset(p.x, p.y, p.z);
            // Avoid out-of-height or out-of-border placement. Commands still obey server-side world state.
            if (!job.level.isInWorldBounds(pos) || !job.level.getWorldBorder().isWithinBounds(pos)) {
                job.owner.sendSystemMessage(Component.literal("Stopped: blueprint reached outside world bounds."));
                currentJob = null;
                return;
            }
            Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.tryParse(p.block));
            if (block == null || block == net.minecraft.world.level.block.Blocks.AIR) continue;
            BlockState state = block.defaultBlockState();
            job.level.setBlock(pos, state, 3);
            count++;
        }
        if (job.cursor >= job.blocks.size()) {
            job.owner.sendSystemMessage(Component.literal("Build complete."));
            currentJob = null;
        }
    }

    private static Blueprint requestBlueprint(String apiKey, String description) throws Exception {
        String system = "You create safe Minecraft block blueprints. Return ONLY valid JSON matching " +
                "{\"name\":\"short name\",\"blocks\":[{\"x\":0,\"y\":0,\"z\":0,\"block\":\"minecraft:stone\"}]}. " +
                "Use relative integer coordinates starting at 0. Max 5000 blocks. Use only vanilla minecraft:* block IDs. " +
                "No markdown. Keep the design within 32x32x32 blocks. Avoid command blocks, portals, fluids, and entities.";
        JsonObject body = new JsonObject();
        body.addProperty("model", "gpt-4.1-mini");
        body.addProperty("temperature", 0.2);
        var messages = new com.google.gson.JsonArray();
        JsonObject sys = new JsonObject(); sys.addProperty("role", "system"); sys.addProperty("content", system);
        JsonObject user = new JsonObject(); user.addProperty("role", "user"); user.addProperty("content", description);
        messages.add(sys); messages.add(user); body.add("messages", messages);
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.openai.com/v1/chat/completions"))
                .timeout(Duration.ofSeconds(90))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8)).build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw new IOException("OpenAI HTTP " + response.statusCode() + ". Check key, billing, and model access.");
        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        String content = root.getAsJsonArray("choices").get(0).getAsJsonObject()
                .getAsJsonObject("message").get("content").getAsString().trim();
        if (content.startsWith("```")) content = content.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("\\s*```$", "");
        return GSON.fromJson(content, Blueprint.class);
    }

    private static void validate(Blueprint b) throws IOException {
        if (b == null || b.blocks == null || b.blocks.isEmpty()) throw new IOException("Blueprint has no blocks.");
        if (b.blocks.size() > MAX_BLOCKS) throw new IOException("Blueprint exceeds " + MAX_BLOCKS + " blocks.");
        for (PlannedBlock p : b.blocks) {
            if (p == null || p.block == null || !p.block.startsWith("minecraft:"))
                throw new IOException("Only vanilla minecraft:* block IDs are accepted.");
            if (Math.abs(p.x) > 32 || Math.abs(p.y) > 32 || Math.abs(p.z) > 32)
                throw new IOException("Coordinates must be within +/-32.");
        }
    }

    private static Path blueprintDir(MinecraftServer server) {
        return server.getServerDirectory().resolve("ai-blueprints").toAbsolutePath().normalize();
    }

    private static Blueprint demoBlueprint() {
        Blueprint b = new Blueprint();
        b.name = "Small oak starter house";
        for (int x = 0; x < 7; x++) for (int z = 0; z < 7; z++) {
            b.blocks.add(new PlannedBlock(x, 0, z, "minecraft:oak_planks"));
            if (x == 0 || x == 6 || z == 0 || z == 6) {
                for (int y = 1; y <= 3; y++) {
                    if (y == 2 && ((x == 0 || x == 6) && z == 3)) continue;
                    if (y == 2 && ((z == 0 || z == 6) && x == 3)) continue;
                    b.blocks.add(new PlannedBlock(x, y, z, "minecraft:oak_planks"));
                }
            }
        }
        for (int x = 0; x < 7; x++) for (int z = 0; z < 7; z++)
            if (x == 0 || x == 6 || z == 0 || z == 6)
                b.blocks.add(new PlannedBlock(x, 4, z, "minecraft:oak_stairs"));
        b.blocks.add(new PlannedBlock(3, 1, 0, "minecraft:air"));
        b.blocks.add(new PlannedBlock(3, 2, 0, "minecraft:air"));
        return b;
    }
}
