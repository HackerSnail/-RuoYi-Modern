package com.ruoyi.common.xss;

import com.ruoyi.common.utils.StringUtils;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.regex.Pattern;

/**
 * 自定义 xss 校验注解实现。
 * <p>
 * 说明：本校验器只做“是否含 HTML 标签”的轻量黑名单拒绝（命中即判为非法、拦截请求），
 * 并非完整的 XSS 净化方案；需要保留富文本的场景应依赖白名单净化（XssFilter /
 * XssHttpServletRequestWrapper 那条 jsoup 清洗链路），不要把它当作可靠 XSS 防线。
 *
 * @author HackerSnail <2306002088@qq.com>
 */
public class XssValidator implements ConstraintValidator<Xss, String> {
    /** 预编译，避免每次校验都 Pattern.compile */
    private static final Pattern HTML_PATTERN = Pattern.compile("<(\\S*?)[^>]*>.*?|<.*? />");

    @Override
    public boolean isValid(String value, ConstraintValidatorContext constraintValidatorContext) {
        // 空值不拦截（交由 @NotBlank 等其他校验处理），含 HTML 标签则校验不通过
        if (StringUtils.isBlank(value)) {
            return true;
        }
        return !containsHtml(value);
    }

    /**
     * 是否包含 HTML 标签：正则命中任一标签片段即为 true。
     * 旧实现 find() 拼接后再 matches() 属逻辑错误（find 已证明存在标签，matches 却要求整段完全匹配，
     * 多片段且片段内含换行时会漏判），改为直接 find()。
     */
    public static boolean containsHtml(String value) {
        if (value == null) {
            return false;
        }
        return HTML_PATTERN.matcher(value).find();
    }
}
