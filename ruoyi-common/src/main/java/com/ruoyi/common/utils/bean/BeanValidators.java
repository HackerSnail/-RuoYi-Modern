package com.ruoyi.common.utils.bean;

import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;

/**
 * bean对象属性验证
 * 
 * @author HackerSnail <2306002088@qq.com>
 */
public final class BeanValidators {
    private static final Logger log = LoggerFactory.getLogger(BeanValidators.class);

    private BeanValidators() {
    }

    public static void validateWithException(Validator validator, Object object, Class<?>... groups)
            throws ConstraintViolationException {
        Set<ConstraintViolation<Object>> constraintViolations = validator.validate(object, groups);
        if (!constraintViolations.isEmpty()) {
            // 抛出前逐条记录违规明细：ConstraintViolationException 的默认 message 会把多条违规
            // 拼成一长串技术文本、且不含输入值上下文，排查时难以定位；这里按字段级留痕后再原样抛出，
            // 不改变校验与抛出语义（错误文案的格式化仍由全局异常处理器负责）。
            for (ConstraintViolation<Object> violation : constraintViolations) {
                log.warn("字段'{}'校验失败，输入值'{}'，原因'{}'", violation.getPropertyPath(), violation.getInvalidValue(),
                        violation.getMessage());
            }
            throw new ConstraintViolationException(constraintViolations);
        }
    }
}
