package com.ruoyi.generator.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link GenTableColumn} 本轮修复/健壮化点的单元测试：
 * ① 7 组 isXxx 判定各自只反映自身标志字段（覆盖 isEdit() 曾误调 isInsert 的复制粘贴 bug）；
 * ② isSuperColumn 统一引用常量后应含 BaseEntity/TreeEntity（含 children）；
 * ③ readConverterExp 全角括号解析与空注释兜底空串；
 * ④ getCapJavaField 空值守卫。
 */
public class GenTableColumnTest {

    @Test
    @DisplayName("isEdit() 只反映 isEdit 标志，不随 isInsert 变化")
    void isEditReflectsOwnFlag() {
        GenTableColumn column = new GenTableColumn();
        column.setIsEdit("1");
        column.setIsInsert("0");

        assertTrue(column.isEdit(), "isEdit=1 时 isEdit() 应为 true");
        assertFalse(column.isInsert(), "isInsert=0 时 isInsert() 应为 false");

        column.setIsEdit("0");
        column.setIsInsert("1");
        assertFalse(column.isEdit(), "isEdit=0 时 isEdit() 应为 false");
        assertTrue(column.isInsert(), "isInsert=1 时 isInsert() 应为 true");
    }

    @Test
    @DisplayName("isPk/isList/isQuery 等判定各自独立反映自身字段")
    void flagPredicatesAreIndependent() {
        GenTableColumn column = new GenTableColumn();
        column.setIsPk("1");
        column.setIsList("1");
        column.setIsQuery(null);

        assertTrue(column.isPk());
        assertTrue(column.isList());
        assertFalse(column.isQuery(), "null 标志应判为 false");
        assertFalse(column.isRequired(), "未设置的标志应判为 false");
    }

    @Test
    @DisplayName("isSuperColumn 引用常量：含 BaseEntity/TreeEntity（含 children），普通业务字段不在内")
    void isSuperColumnCoversBaseAndTreeFields() {
        assertTrue(GenTableColumn.isSuperColumn("createTime"), "BaseEntity 字段应视为父类字段");
        assertTrue(GenTableColumn.isSuperColumn("remark"));
        assertTrue(GenTableColumn.isSuperColumn("ancestors"), "TreeEntity 字段应视为父类字段");
        assertTrue(GenTableColumn.isSuperColumn("children"), "统一到常量后应含 TreeEntity.children");
        assertFalse(GenTableColumn.isSuperColumn("userName"), "普通业务列不应被判为父类字段");
        assertFalse(GenTableColumn.isSuperColumn("id"));
    }

    @Test
    @DisplayName("readConverterExp：全角括号内取值映射解析为 0=男,1=女")
    void readConverterExpParsesFullWidthParen() {
        GenTableColumn column = new GenTableColumn();
        column.setColumnComment("性别（0男 1女）");
        assertEquals("0=男,1=女", column.readConverterExp());
    }

    @Test
    @DisplayName("readConverterExp：无括号注释原样返回，空/null 注释兜底空串")
    void readConverterExpFallsBackToEmpty() {
        GenTableColumn column = new GenTableColumn();
        column.setColumnComment("用户昵称");
        assertEquals("用户昵称", column.readConverterExp(), "无全角括号应原样返回注释");

        column.setColumnComment(null);
        assertEquals("", column.readConverterExp(), "null 注释应兜底空串而非返回 null");
    }

    @Test
    @DisplayName("getCapJavaField：正常首字母大写，空值原样返回")
    void getCapJavaFieldHandlesEmpty() {
        GenTableColumn column = new GenTableColumn();
        column.setJavaField("userName");
        assertEquals("UserName", column.getCapJavaField());

        column.setJavaField("");
        assertEquals("", column.getCapJavaField());

        column.setJavaField(null);
        assertNull(column.getCapJavaField(), "null javaField 应原样返回 null，不抛异常");
    }
}
