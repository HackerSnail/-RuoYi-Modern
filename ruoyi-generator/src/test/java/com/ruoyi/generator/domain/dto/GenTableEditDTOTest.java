package com.ruoyi.generator.domain.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;

import com.ruoyi.generator.domain.GenTable;
import com.ruoyi.generator.domain.GenTableColumn;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

/**
 * {@link GenTableEditDTO} 的单元测试：验证自实体迁移而来的输入校验（@NotBlank/@Pattern/@Valid 级联）
 * 在 DTO 上仍按预期触发，以及经 BeanUtils.copyProperties 复制到 GenTable 后字段完整、契约不变。
 */
public class GenTableEditDTOTest {

    private static final Validator validator;

    static {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    /** 构造一个各必填项合法、枚举取值合法的编辑入参 */
    private GenTableEditDTO validDto() {
        GenTableEditDTO dto = new GenTableEditDTO();
        dto.setTableId(1L);
        dto.setTableName("sys_user");
        dto.setTableComment("用户表");
        dto.setClassName("SysUser");
        dto.setTplCategory("crud");
        dto.setTplWebType("element-plus");
        dto.setPackageName("com.ruoyi.system");
        dto.setModuleName("system");
        dto.setBusinessName("user");
        dto.setFunctionName("用户管理");
        dto.setFunctionAuthor("ruoyi");
        dto.setGenType("0");
        return dto;
    }

    @Test
    @DisplayName("合法编辑入参：无任何校验违规")
    void validDtoHasNoViolations() {
        Set<ConstraintViolation<GenTableEditDTO>> violations = validator.validate(validDto());
        assertTrue(violations.isEmpty(), "合法入参不应产生违规，实际：" + violations);
    }

    @Test
    @DisplayName("表名为空：触发 @NotBlank")
    void blankTableNameRejected() {
        GenTableEditDTO dto = validDto();
        dto.setTableName("  ");
        Set<ConstraintViolation<GenTableEditDTO>> violations = validator.validate(dto);
        assertTrue(violations.stream().anyMatch(v -> "tableName".equals(v.getPropertyPath().toString())),
                "tableName 应因 @NotBlank 报错");
    }

    @Test
    @DisplayName("模板类别非法：触发 @Pattern")
    void invalidTplCategoryRejected() {
        GenTableEditDTO dto = validDto();
        dto.setTplCategory("invalid-cat");
        Set<ConstraintViolation<GenTableEditDTO>> violations = validator.validate(dto);
        assertTrue(violations.stream().anyMatch(v -> "tplCategory".equals(v.getPropertyPath().toString())),
                "tplCategory 应因 @Pattern 报错");
    }

    @Test
    @DisplayName("枚举为 null：@Pattern 放过（仅 editSave 前端漏传时不误伤）")
    void nullEnumPassesPattern() {
        GenTableEditDTO dto = validDto();
        dto.setTplCategory(null);
        Set<ConstraintViolation<GenTableEditDTO>> violations = validator.validate(dto);
        assertFalse(violations.stream().anyMatch(v -> "tplCategory".equals(v.getPropertyPath().toString())),
                "tplCategory 为 null 时 @Pattern 不应报错");
    }

    @Test
    @DisplayName("列 javaField 为空：@Valid 级联到 GenTableColumn")
    void columnsCascadeValidation() {
        GenTableEditDTO dto = validDto();
        GenTableColumn column = new GenTableColumn();
        column.setJavaField("");
        List<GenTableColumn> columns = new ArrayList<>();
        columns.add(column);
        dto.setColumns(columns);

        Set<ConstraintViolation<GenTableEditDTO>> violations = validator.validate(dto);
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().startsWith("columns[0]")),
                "应级联校验到 columns[0].javaField");
    }

    @Test
    @DisplayName("copyProperties 到 GenTable：可编辑字段完整迁移，契约不变")
    void copyPropertiesPopulatesEntity() {
        GenTableEditDTO dto = validDto();
        dto.setSubTableName("sys_dept");
        dto.setSubTableFkName("dept_id");
        dto.setFormColNum(2);
        dto.setGenPath("/tmp");
        dto.setTreeCode("dict_code");
        dto.setParentMenuId(3L);

        GenTable entity = new GenTable();
        BeanUtils.copyProperties(dto, entity);

        assertEquals(dto.getTableId(), entity.getTableId());
        assertEquals(dto.getTableName(), entity.getTableName());
        assertEquals(dto.getTableComment(), entity.getTableComment());
        assertEquals(dto.getClassName(), entity.getClassName());
        assertEquals(dto.getTplCategory(), entity.getTplCategory());
        assertEquals(dto.getTplWebType(), entity.getTplWebType());
        assertEquals(dto.getPackageName(), entity.getPackageName());
        assertEquals(dto.getModuleName(), entity.getModuleName());
        assertEquals(dto.getBusinessName(), entity.getBusinessName());
        assertEquals(dto.getFunctionName(), entity.getFunctionName());
        assertEquals(dto.getFunctionAuthor(), entity.getFunctionAuthor());
        assertEquals(dto.getGenType(), entity.getGenType());
        assertEquals(dto.getSubTableName(), entity.getSubTableName());
        assertEquals(dto.getSubTableFkName(), entity.getSubTableFkName());
        assertEquals(dto.getFormColNum(), entity.getFormColNum());
        assertEquals(dto.getGenPath(), entity.getGenPath());
        assertEquals(dto.getTreeCode(), entity.getTreeCode());
        assertEquals(dto.getParentMenuId(), entity.getParentMenuId());
    }
}
