package com.ruoyi.generator.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.apache.velocity.VelocityContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.constant.GenConstants;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.generator.domain.GenTable;

/**
 * {@link VelocityUtils} 健壮性测试：锁死本轮修复的三类崩溃（options 裸解析 NPE、formColNum 拆箱 NPE、
 * genView 拆箱 NPE）、文件名 domain/sub-domain 解耦，以及 getFileName 的路径穿越白名单。纯静态工具，无需 Spring。
 */
public class VelocityUtilsTest {

    private GenTable baseTable() {
        GenTable t = new GenTable();
        t.setTableName("sys_user");
        t.setClassName("User");
        t.setPackageName("com.ruoyi.system");
        t.setModuleName("system");
        t.setBusinessName("user");
        t.setFunctionName("用户");
        t.setTplCategory(GenConstants.TPL_CRUD);
        t.setTplWebType("element-plus");
        t.setColumns(new ArrayList<>());
        return t;
    }

    @Test
    @DisplayName("getColSpan：null 默认单列 24，杜绝 formColNum 拆箱 NPE")
    void getColSpanNullSafe() {
        assertEquals("24", VelocityUtils.getColSpan(null));
        assertEquals("24", VelocityUtils.getColSpan(1));
        assertEquals("12", VelocityUtils.getColSpan(2));
        assertEquals("8", VelocityUtils.getColSpan(3));
    }

    @Test
    @DisplayName("genView：null/缺省/显式 null 值均返 false，对齐 getBooleanValue 不拆箱")
    void genViewNullSafe() {
        assertFalse(VelocityUtils.genView(null));
        assertFalse(VelocityUtils.genView(new JSONObject()));
        assertFalse(VelocityUtils.genView(JSON.parseObject("{\"genView\":null}")));
        assertTrue(VelocityUtils.genView(JSON.parseObject("{\"genView\":true}")));
    }

    @Test
    @DisplayName("prepareContext：options 为空 + formColNum 为空不再 NPE，colSpan 落默认 24")
    void prepareContextToleratesBlankOptionsAndNullFormColNum() {
        GenTable t = baseTable();
        t.setOptions(null);
        t.setFormColNum(null);

        VelocityContext ctx = VelocityUtils.prepareContext(t);
        assertNotNull(ctx, "prepareContext 不应抛异常");
        assertEquals("24", ctx.get("colSpan"));
        assertEquals(Boolean.FALSE, ctx.get("genView"));
    }

    @Test
    @DisplayName("getTemplateList：options 为空不 NPE，返回基础模板且不含详情页")
    void getTemplateListToleratesBlankOptions() {
        GenTable t = baseTable();
        t.setOptions(null);
        List<String> templates = VelocityUtils.getTemplateList(t);
        assertTrue(templates.contains("vm/java/domain.java.vm"));
        assertFalse(templates.contains("vm/vue/v3/view.vue.vm"), "genView 缺省不应追加详情页模板");
    }

    @Test
    @DisplayName("getExpandColumn：options 为空（原 paramsObj.getString 会 NPE）现降级返回 0")
    void getExpandColumnToleratesBlankOptions() {
        GenTable t = baseTable();
        t.setTplCategory(GenConstants.TPL_TREE);
        t.setOptions(null);
        assertEquals(0, VelocityUtils.getExpandColumn(t));
    }

    @Test
    @DisplayName("getFileName：domain 走主类名、sub-domain 走子表类名，二者解耦不再互相覆盖")
    void getFileNameSeparatesDomainAndSubDomain() {
        GenTable t = baseTable();
        t.setTplCategory(GenConstants.TPL_SUB);
        GenTable sub = new GenTable();
        sub.setClassName("Order");
        t.setSubTable(sub);

        // 主表 domain 模板 → 用主类名 User
        assertEquals("main/java/com/ruoyi/system/domain/User.java",
                VelocityUtils.getFileName("vm/java/domain.java.vm", t));
        // 子表 domain 模板 → 用子表类名 Order（此前靠顺序覆盖维持，现显式互斥）
        assertEquals("main/java/com/ruoyi/system/domain/Order.java",
                VelocityUtils.getFileName("vm/java/sub-domain.java.vm", t));
    }

    @Test
    @DisplayName("getFileName：包名/类名/模块名含 ../ 或分隔符一律拒绝，堵死路径穿越")
    void getFileNameRejectsTraversal() {
        GenTable t = baseTable();
        t.setPackageName("com.ruoyi.../../etc");
        assertThrows(ServiceException.class, () -> VelocityUtils.getFileName("vm/java/domain.java.vm", t));

        GenTable t2 = baseTable();
        t2.setClassName("Foo/Bar");
        assertThrows(ServiceException.class, () -> VelocityUtils.getFileName("vm/java/domain.java.vm", t2));

        GenTable t3 = baseTable();
        t3.setModuleName("a\\b");
        assertThrows(ServiceException.class, () -> VelocityUtils.getFileName("vm/xml/mapper.xml.vm", t3));
    }
}
