package com.ruoyi.generator.util;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.Velocity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.constant.GenConstants;
import com.ruoyi.generator.domain.GenTable;
import com.ruoyi.generator.domain.GenTableColumn;

/**
 * 代码生成「逐字节金标准（golden）」产出器（批次 16-B② 的 Java oracle）。
 *
 * <p>
 * 这不是断言测试，而是一台<b>确定性的渲染机</b>：读取 {@code <repoRoot>/testdata/gen/fixtures/*.json}
 * 描述的 GenTable 夹具，走与生产 {@code GenTableServiceImpl.previewCode} 完全一致的链路
 * （setPkColumn → {@link VelocityUtils#prepareContext} → {@link VelocityUtils#getTemplateList} →
 * {@code Velocity.getTemplate(...).merge(ctx, writer)}），把每个模板的渲染结果写入
 * {@code <repoRoot>/testdata/gen/golden/<fixtureId>/<templateKey>}。Go 侧
 * （ruoyi-goframe velocity 解释器 + velocity_utils）以这些 golden 为唯一裁判做 bytes.Equal 收敛。
 * </p>
 *
 * <p>
 * 确定性约束：{@code datetime}（DateUtils.getDate() 依赖系统时钟）在写入 context 后被固定覆盖为
 * {@link #FIXED_DATE}，Go 侧同样固定，消除跨运行/跨语言的时间抖动。模板字节两端一致（同一份 .vm，
 * ClasspathResourceLoader 读 target/classes/vm，Go 读 embed 的字节级拷贝）。
 * </p>
 *
 * <p>
 * 复现：{@code mvn -o -pl ruoyi-generator test -Dtest=GenGoldenOracleTest}。可用系统属性
 * {@code -Dgen.fixtures=<dir>} / {@code -Dgen.golden=<dir>} 覆盖默认路径。
 * </p>
 *
 * @author port-oracle
 */
public class GenGoldenOracleTest {

    /** 固定日期，消除 DateUtils.getDate() 的时钟不确定性；Go 侧必须用同一常量。 */
    static final String FIXED_DATE = "2026-01-01";

    @Test
    @DisplayName("产出代码生成 golden（全笛卡尔夹具 × 各自模板列表）")
    void generateGoldens() throws IOException {
        VelocityInitializer.initVelocity();

        Path repoRoot = findRepoRoot();
        Path fixturesDir = resolve(repoRoot, "gen.fixtures", "testdata/gen/fixtures");
        Path goldenDir = resolve(repoRoot, "gen.golden", "testdata/gen/golden");

        List<Path> fixtureFiles = new ArrayList<>();
        try (var stream = Files.list(fixturesDir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .forEach(fixtureFiles::add);
        }
        if (fixtureFiles.isEmpty()) {
            throw new IllegalStateException("未找到任何夹具：" + fixturesDir);
        }

        int total = 0;
        for (Path fx : fixtureFiles) {
            String json = Files.readString(fx, StandardCharsets.UTF_8);
            String fixtureId = fx.getFileName().toString().replaceAll("\\.json$", "");
            GenTable table = JSON.parseObject(json, GenTable.class);
            replicateSetPkColumn(table);

            VelocityContext context = VelocityUtils.prepareContext(table);
            context.put("datetime", FIXED_DATE); // 覆盖时钟值，两端一致

            List<String> templates = VelocityUtils.getTemplateList(table);
            Path outDir = goldenDir.resolve(fixtureId);
            Files.createDirectories(outDir);
            for (String template : templates) {
                StringWriterCatching sw = render(template, context);
                String key = template.replace('/', '_').replace(".vm", "") + ".golden";
                Files.writeString(outDir.resolve(key), sw.text, StandardCharsets.UTF_8);
                total++;
            }
            System.out.println("[golden] " + fixtureId + " -> " + templates.size() + " 模板");
        }
        System.out.println("[golden] 合计写出 " + total + " 个 golden 文件于 " + goldenDir);
    }

    private static StringWriterCatching render(String template, VelocityContext context) {
        java.io.StringWriter sw = new java.io.StringWriter();
        Template tpl = Velocity.getTemplate(template, "UTF-8");
        tpl.merge(context, sw);
        StringWriterCatching r = new StringWriterCatching();
        r.text = sw.toString();
        return r;
    }

    private static final class StringWriterCatching {
        String text;
    }

    /** 复刻 GenTableServiceImpl.setPkColumn（不依赖 DB）：首个 isPk 列，否则首列；子表同理。 */
    private static void replicateSetPkColumn(GenTable table) {
        table.setPkColumn(pickPk(table.getColumns()));
        if (GenConstants.TPL_SUB.equals(table.getTplCategory()) && table.getSubTable() != null) {
            GenTable sub = table.getSubTable();
            sub.setPkColumn(pickPk(sub.getColumns()));
        }
    }

    private static GenTableColumn pickPk(List<GenTableColumn> columns) {
        if (columns == null || columns.isEmpty()) {
            return null;
        }
        for (GenTableColumn c : columns) {
            if (c.isPk()) {
                return c;
            }
        }
        return columns.get(0);
    }

    /** 从 user.dir 向上找同时含 ruoyi-generator 与 ruoyi-goframe 的目录（仓库根）。 */
    private static Path findRepoRoot() {
        Path p = new File("").getAbsoluteFile().toPath();
        while (p != null) {
            if (Files.isDirectory(p.resolve("ruoyi-generator")) && Files.isDirectory(p.resolve("ruoyi-goframe"))) {
                return p;
            }
            p = p.getParent();
        }
        throw new IllegalStateException("无法定位仓库根（未找到含 ruoyi-generator 与 ruoyi-goframe 的祖先目录）");
    }

    private static Path resolve(Path repoRoot, String sysProp, String relDefault) {
        String override = System.getProperty(sysProp);
        if (override != null && !override.isEmpty()) {
            return new File(override).getAbsoluteFile().toPath();
        }
        return repoRoot.resolve(relDefault);
    }
}
