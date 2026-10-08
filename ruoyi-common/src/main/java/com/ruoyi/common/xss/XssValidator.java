package com.ruoyi.common.xss;

import com.ruoyi.common.utils.StringUtils;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 自定义xss校验注解实现
 * 
 * @author ruoyi
 */
public class XssValidator implements ConstraintValidator<Xss, String> {
    private static final String HTML_PATTERN = "<(\\S*?)[^>]*>.*?|<.*? />";

    @Override
    public boolean isValid(String value, ConstraintValidatorContext constraintValidatorContext) {
        // 空值不拦截（交由 @NotBlank 等其他校验处理），含 HTML 标签则校验不通过
        if (StringUtils.isBlank(value)) {
            return true;
        }
        return !containsHtml(value);
    }

    /**
     * 是否包含 HTML 标签：先找出所有命中标签正则的片段拼接，再整体匹配以判定是否存在 XSS 风险内容
     */
    public static boolean containsHtml(String value) {
        StringBuilder sHtml = new StringBuilder();
        Pattern pattern = Pattern.compile(HTML_PATTERN);
        Matcher matcher = pattern.matcher(value);
        while (matcher.find()) {
            sHtml.append(matcher.group());
        }
        return pattern.matcher(sHtml).matches();
    }
}
