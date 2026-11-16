package cn.blockforge.generated.moderncompanionsexpand.config;

import cn.blockforge.generated.moderncompanionsexpand.util.SafeGuard;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 技能优先级表：config/modern_companions_expand/skill_priority.json
 *
 * 只管两件事（按约定：数值参数全部留在 TOML 里，避免同一数值两处维护）：
 * 1. priority：技能 id 的顺序数组——每 tick 扫描时按这个顺序找第一个“可放”的技能；
 * 2. global.silence_ticks：任一技能成功释放后，该同伴的全局沉默 tick 数（防同 tick 连放/刷屏）。
 *
 * 生命周期：
 * - 首次使用（服务端 tick 或命令）时若文件不存在 → 落盘默认表；
 * - 文件损坏 → 回退内置默认表并写日志，不炸游戏；
 * - /mce skills reload → 热重载（改完文件立即生效，不用重启）。
 */
public final class SkillPriorityConfig {

    public static final String SKILL_BULWARK = "bulwark_of_the_flame";
    public static final String SKILL_INCINERATOR = "the_incinerator";
    public static final String SKILL_INFERNAL_FORGE = "infernal_forge";
    public static final String SKILL_SOUL_RENDER = "soul_render";
    public static final String SKILL_ASTRAPE = "astrape";
    public static final String SKILL_CERAUNUS = "ceraunus";
    public static final String SKILL_VOID_FORGE = "void_forge";
    public static final String SKILL_BRONTES = "brontes";
    public static final String SKILL_MEAT_SHREDDER = "meat_shredder";
    public static final String SKILL_SOUL_GREAT_SWORD = "soul_great_sword"; // r28 传奇怪物·灵魂巨剑
    public static final String SKILL_TESSERACT = "tesseract";               // r30 传奇怪物·超立方体
    public static final String SKILL_BUCKLER = "buckler";                   // r32 传奇怪物·湮灭圆盾（拍板：bulwark 之后）
    public static final String SKILL_MONSTROUS_ANCHOR = "monstrous_anchor"; // r33 传奇怪物·怪怖之锚（拍板：贴脸系，meat_shredder 附近）
    public static final String SKILL_AXE_OF_LIGHTNING = "axe_of_lightning"; // r33 传奇怪物·雷霆之钺（拍板：远程系，ceraunus 附近）

    /** 代码里已注册的全部技能 id：加载老表时缺了谁就按默认顺序插到对应位置（r10 起支持增量升级） */
    private static final List<String> KNOWN_SKILL_IDS = List.of(
            SKILL_BULWARK, SKILL_BUCKLER, SKILL_MEAT_SHREDDER, SKILL_MONSTROUS_ANCHOR,
            SKILL_SOUL_GREAT_SWORD, SKILL_SOUL_RENDER,
            SKILL_CERAUNUS, SKILL_AXE_OF_LIGHTNING, SKILL_ASTRAPE, SKILL_TESSERACT, SKILL_VOID_FORGE, SKILL_BRONTES,
            SKILL_INCINERATOR, SKILL_INFERNAL_FORGE);

    private static final List<String> DEFAULT_PRIORITY = List.of(
            SKILL_BULWARK, SKILL_BUCKLER, SKILL_MEAT_SHREDDER, SKILL_MONSTROUS_ANCHOR,
            SKILL_SOUL_GREAT_SWORD, SKILL_SOUL_RENDER,
            SKILL_CERAUNUS, SKILL_AXE_OF_LIGHTNING, SKILL_ASTRAPE, SKILL_TESSERACT, SKILL_VOID_FORGE, SKILL_BRONTES,
            SKILL_INCINERATOR, SKILL_INFERNAL_FORGE);
    private static final int DEFAULT_SILENCE_TICKS = 10;

    private static volatile List<String> priority = DEFAULT_PRIORITY;
    private static volatile int silenceTicks = DEFAULT_SILENCE_TICKS;
    private static volatile boolean loaded = false;

    private SkillPriorityConfig() {}

    /** 文件路径：config/modern_companions_expand/skill_priority.json */
    public static Path filePath() {
        return FMLPaths.CONFIGDIR.get()
                .resolve("modern_companions_expand")
                .resolve("skill_priority.json");
    }

    /** 惰性加载：第一次用到时读文件（没有就先写默认表） */
    public static void ensureLoaded() {
        if (!loaded) {
            reload();
        }
    }

    /** 重新读盘。返回给用户看的说明文字（命令回显用）。任何异常都兜底回默认表。 */
    public static synchronized String reload() {
        Path file = filePath();
        try {
            if (!Files.exists(file)) {
                writeDefault(file);
                priority = DEFAULT_PRIORITY;
                silenceTicks = DEFAULT_SILENCE_TICKS;
                loaded = true;
                SafeGuard.info("skill priority table: file not found, the default table was generated " + file);
                return "default priority table generated: " + file;
            }
            List<String> nextPriority;
            int nextSilence;
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                JsonElement root = new Gson().fromJson(reader, JsonElement.class);
                if (root == null || !root.isJsonObject()) {
                    throw new IOException("the root node is not a JSON object");
                }
                JsonObject obj = root.getAsJsonObject();
                List<String> parsed = new ArrayList<>();
                if (obj.has("priority") && obj.get("priority").isJsonArray()) {
                    obj.get("priority").getAsJsonArray().forEach(el -> {
                        if (el.isJsonPrimitive()) {
                            parsed.add(el.getAsString());
                        }
                    });
                }
                nextPriority = parsed.isEmpty() ? new ArrayList<>(DEFAULT_PRIORITY) : ensureKnownSkills(parsed);
                nextSilence = DEFAULT_SILENCE_TICKS;
                if (obj.has("global") && obj.get("global").isJsonObject()) {
                    JsonObject g = obj.getAsJsonObject("global");
                    if (g.has("silence_ticks") && g.get("silence_ticks").isJsonPrimitive()) {
                        nextSilence = Math.max(0, Math.min(2000, g.get("silence_ticks").getAsInt()));
                    }
                }
            }
            priority = Collections.unmodifiableList(nextPriority);
            silenceTicks = nextSilence;
            loaded = true;
            SafeGuard.info("skill priority table: loaded successfully " + priority + ", silence_ticks=" + silenceTicks);
            return "loaded " + file.getFileName() + "]: order " + priority + ", silence " + silenceTicks + " tick";
        } catch (Throwable t) {
            priority = DEFAULT_PRIORITY;
            silenceTicks = DEFAULT_SILENCE_TICKS;
            loaded = true;
            SafeGuard.warn("skill priority table: reading failed, fell back to the built-in default table " + DEFAULT_PRIORITY
                    + " (exception: " + t.getClass().getSimpleName() + " / " + t.getMessage() + ")", t);
            return "read failed, fell back to the default table (see the [MCE] log)";
        }
    }

    private static void writeDefault(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < DEFAULT_PRIORITY.size(); i++) {
            ids.append("    \"").append(DEFAULT_PRIORITY.get(i)).append("\"");
            if (i < DEFAULT_PRIORITY.size() - 1) {
                ids.append(",");
            }
            ids.append("\n");
        }
        String json = "{\n"
                + "  \"global\": {\n"
                + "    \"silence_ticks\": " + DEFAULT_SILENCE_TICKS + "\n"
                + "  },\n"
                + "  \"priority\": [\n"
                + ids
                + "  ]\n"
                + "}\n";
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write(json);
        }
    }

    /**
     * 老表升级（r10 引入、r11 改进）：文件里没有的已注册技能 id 自动插入——
     * 位置按默认优先级表里“排它后面的第一个已知技能”之前，尽量贴近官方推荐顺序；
     * 找不到就追加到末尾。这样老用户升级 jar 后不用手改 skill_priority.json 也能用上新技能。
     */
    private static List<String> ensureKnownSkills(List<String> parsed) {
        List<String> out = new ArrayList<>(parsed);
        for (String known : KNOWN_SKILL_IDS) {
            if (out.contains(known)) {
                continue;
            }
            int canonical = DEFAULT_PRIORITY.indexOf(known);
            int insertAt = out.size();
            for (int i = 0; i < out.size(); i++) {
                int rank = DEFAULT_PRIORITY.indexOf(out.get(i));
                if (canonical >= 0 && rank > canonical) {
                    insertAt = i;
                    break;
                }
            }
            out.add(insertAt, known);
            SafeGuard.info("skill priority table: the skill '" + known + "' is missing from the table, inserted at position "
                    + (insertAt + 1) + " following the default order (old table upgrade; to reorder, edit " + filePath().getFileName()
                    + " and run /mce skills reload)");
        }
        return out;
    }

    /** 当前生效的技能 id 顺序（不可变） */
    public static List<String> orderedIds() {
        ensureLoaded();
        return priority;
    }

    /** 成功释放任一技能后，该同伴的全局沉默 tick 数 */
    public static int silenceTicks() {
        ensureLoaded();
        return silenceTicks;
    }
}
