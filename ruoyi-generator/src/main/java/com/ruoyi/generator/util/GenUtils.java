package com.ruoyi.generator.util;

import java.util.Arrays;
import org.apache.commons.lang3.RegExUtils;
import org.springframework.stereotype.Component;
import com.ruoyi.common.constant.GenConstants;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.generator.config.GenConfig;
import com.ruoyi.generator.domain.GenTable;
import com.ruoyi.generator.domain.GenTableColumn;

/**
 * 代码生成器 工具类。
 *
 * <p>
 * 依赖 {@link GenConfig} 的 {@code initTable}/{@code convertClassName} 已改为实例方法，配置由 Spring
 * 注入而非读全局静态；其余无状态算法辅助方法保持静态。消费方（{@code GenTableServiceImpl}）注入本 bean 后调用。
 * </p>
 * 
 * @author HackerSnail <2306002088@qq.com>
 */
@Component
public class GenUtils {
    private final GenConfig genConfig;

    public GenUtils(GenConfig genConfig) {
        this.genConfig = genConfig;
    }

    /**
     * 初始化表信息：根据表名与注入的配置推导类名/包名/模块名/业务名/功能名等生成元数据
     *
     * @param genTable 待初始化的生成表
     * @param operName 操作人员（写入 createBy）
     */
    public void initTable(GenTable genTable, String operName) {
        genTable.setClassName(convertClassName(genTable.getTableName()));
        genTable.setPackageName(genConfig.getPackageName());
        genTable.setModuleName(getModuleName(genConfig.getPackageName()));
        genTable.setBusinessName(getBusinessName(genTable.getTableName()));
        genTable.setFunctionName(replaceText(genTable.getTableComment()));
        genTable.setFunctionAuthor(genConfig.getAuthor());
        genTable.setCreateBy(operName);
    }

    /**
     * 初始化列属性字段：根据列类型推导 Java 类型/HTML 控件/是否必填，并根据列名后缀推导查询方式与控件
     *
     * @param column 待初始化的列
     * @param table  所属生成表（提供 tableId/createBy）
     */
    public static void initColumnField(GenTableColumn column, GenTable table) {
        String dataType = getDbType(column.getColumnType());
        String columnName = column.getColumnName();
        column.setTableId(table.getTableId());
        column.setCreateBy(table.getCreateBy());
        // 设置java字段名
        column.setJavaField(StringUtils.toCamelCase(columnName));
        // 设置默认类型
        column.setJavaType(GenConstants.TYPE_STRING);
        column.setQueryType(GenConstants.QUERY_EQ);

        if (arraysContains(GenConstants.COLUMNTYPE_STR, dataType)
                || arraysContains(GenConstants.COLUMNTYPE_TEXT, dataType)) {
            // 字符串长度超过500（经验阈值，长文本输入体验差）设置为文本域
            Integer columnLength = getColumnLength(column.getColumnType());
            String htmlType = columnLength >= 500 || arraysContains(GenConstants.COLUMNTYPE_TEXT, dataType)
                    ? GenConstants.HTML_TEXTAREA
                    : GenConstants.HTML_INPUT;
            column.setHtmlType(htmlType);
        } else if (arraysContains(GenConstants.COLUMNTYPE_TIME, dataType)) {
            column.setJavaType(GenConstants.TYPE_DATE);
            column.setHtmlType(GenConstants.HTML_DATETIME);
        } else if (arraysContains(GenConstants.COLUMNTYPE_NUMBER, dataType)) {
            column.setHtmlType(GenConstants.HTML_INPUT);

            // 如果是浮点型 统一用BigDecimal
            String[] str = StringUtils.split(StringUtils.substringBetween(column.getColumnType(), "(", ")"), ",");
            // 防御：精度/刻度解析失败（畸形 column_type）不应中断整表导入，降级按长整形处理
            try {
                if (str != null && str.length == 2 && Integer.parseInt(str[1]) > 0) {
                    column.setJavaType(GenConstants.TYPE_BIGDECIMAL);
                }
                // 如果是整形（长度<=10 可容纳于 int，经验阈值）
                else if (str != null && str.length == 1 && Integer.parseInt(str[0]) <= 10) {
                    column.setJavaType(GenConstants.TYPE_INTEGER);
                }
                // 长整形
                else {
                    column.setJavaType(GenConstants.TYPE_LONG);
                }
            } catch (NumberFormatException e) {
                column.setJavaType(GenConstants.TYPE_LONG);
            }
        }

        // 插入字段（默认所有字段都需要插入）
        column.setIsInsert(GenConstants.REQUIRE);

        // 编辑字段
        if (!arraysContains(GenConstants.COLUMNNAME_NOT_EDIT, columnName) && !column.isPk()) {
            column.setIsEdit(GenConstants.REQUIRE);
        }
        // 列表字段
        if (!arraysContains(GenConstants.COLUMNNAME_NOT_LIST, columnName) && !column.isPk()) {
            column.setIsList(GenConstants.REQUIRE);
        }
        // 查询字段
        if (!arraysContains(GenConstants.COLUMNNAME_NOT_QUERY, columnName) && !column.isPk()) {
            column.setIsQuery(GenConstants.REQUIRE);
        }

        // 查询字段类型
        if (StringUtils.endsWithIgnoreCase(columnName, "name")) {
            column.setQueryType(GenConstants.QUERY_LIKE);
        }
        // 状态字段设置单选框
        if (StringUtils.endsWithIgnoreCase(columnName, "status")) {
            column.setHtmlType(GenConstants.HTML_RADIO);
        }
        // 类型&性别字段设置下拉框
        else if (StringUtils.endsWithIgnoreCase(columnName, "type")
                || StringUtils.endsWithIgnoreCase(columnName, "sex")) {
            column.setHtmlType(GenConstants.HTML_SELECT);
        }
        // 图片字段设置图片上传控件
        else if (StringUtils.endsWithIgnoreCase(columnName, "image")) {
            column.setHtmlType(GenConstants.HTML_IMAGE_UPLOAD);
        }
        // 文件字段设置文件上传控件
        else if (StringUtils.endsWithIgnoreCase(columnName, "file")) {
            column.setHtmlType(GenConstants.HTML_FILE_UPLOAD);
        }
        // 内容字段设置富文本控件
        else if (StringUtils.endsWithIgnoreCase(columnName, "content")) {
            column.setHtmlType(GenConstants.HTML_EDITOR);
        }
    }

    /**
     * 校验数组是否包含指定值
     * 
     * @param arr         数组
     * @param targetValue 值
     * @return 是否包含
     */
    public static boolean arraysContains(String[] arr, String targetValue) {
        return Arrays.asList(arr).contains(targetValue);
    }

    /**
     * 获取模块名：取包名最后一段（最后一个 "." 之后）。
     * <p>无分隔符（不含 "."）时 lastIndex=-1，substring 返回整个原串。</p>
     * 
     * @param packageName 包名
     * @return 模块名
     */
    public static String getModuleName(String packageName) {
        int lastIndex = packageName.lastIndexOf(".");
        int nameLength = packageName.length();
        return StringUtils.substring(packageName, lastIndex + 1, nameLength);
    }

    /**
     * 获取业务名：取表名最后一段（最后一个 "_" 之后）。
     * <p>无分隔符（不含 "_"）时 lastIndex=-1，substring 返回整个原串。</p>
     * 
     * @param tableName 表名
     * @return 业务名
     */
    public static String getBusinessName(String tableName) {
        int lastIndex = tableName.lastIndexOf("_");
        int nameLength = tableName.length();
        return StringUtils.substring(tableName, lastIndex + 1, nameLength);
    }

    /**
     * 表名转换成Java类名
     * 
     * @param tableName 表名称
     * @return 类名
     */
    public String convertClassName(String tableName) {
        boolean autoRemovePre = genConfig.isAutoRemovePre();
        String tablePrefix = genConfig.getTablePrefix();
        if (autoRemovePre && StringUtils.isNotEmpty(tablePrefix)) {
            String[] searchList = StringUtils.split(tablePrefix, ",");
            tableName = replaceFirst(tableName, searchList);
        }
        return StringUtils.convertToCamelCase(tableName);
    }

    /**
     * 批量替换前缀：逐个比对前缀列表，命中第一个即剔除
     * 
     * @param replacementm 替换值（表名）
     * @param searchList   待剔除的前缀列表
     * @return 剔除前缀后的表名
     */
    public static String replaceFirst(String replacementm, String[] searchList) {
        String text = replacementm;
        for (String searchString : searchList) {
            // 已用字面量 startsWith 确认前缀，直接按长度截取剔除；勿用 replaceFirst（其首参会被当正则，前缀含 . * 等元字符时误匹配）
            if (replacementm.startsWith(searchString)) {
                text = replacementm.substring(searchString.length());
                break;
            }
        }
        return text;
    }

    /**
     * 关键字替换：从表备注中剔除“表”“若依”等噪声词，得到干净的功能名
     * 
     * @param text 需要被替换的名字
     * @return 替换后的名字
     */
    public static String replaceText(String text) {
        return RegExUtils.replaceAll(text, "(?:表|若依)", "");
    }

    /**
     * 获取数据库类型字段
     * 
     * @param columnType 列类型
     * @return 截取后的列类型
     */
    public static String getDbType(String columnType) {
        if (StringUtils.indexOf(columnType, "(") > 0) {
            return StringUtils.substringBefore(columnType, "(");
        } else {
            return columnType;
        }
    }

    /**
     * 获取字段长度
     * 
     * @param columnType 列类型
     * @return 截取后的列类型
     */
    public static Integer getColumnLength(String columnType) {
        if (StringUtils.indexOf(columnType, "(") > 0) {
            String length = StringUtils.substringBetween(columnType, "(", ")");
            // 防御：长度仅用于"是否切 textarea"的经验判断，括号内非数字/缺右括号(→null)不应抛异常阻断整表导入，降级 0
            try {
                return Integer.valueOf(length);
            } catch (NumberFormatException e) {
                return 0;
            }
        } else {
            return 0;
        }
    }
}
