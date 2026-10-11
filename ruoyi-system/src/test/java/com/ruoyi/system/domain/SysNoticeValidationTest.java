package com.ruoyi.system.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

/**
 * SysNotice 的 noticeType/status 枚举约束（@Pattern）测试，无 Spring。
 *
 * <p>
 * 用 Jakarta Validator 直测 @Pattern 语义：合法值放行、非法值拒绝、null 放行（兼容可空/部分更新）。
 * 按属性路径过滤断言，避免 noticeTitle 上的 @NotBlank/@Xss 干扰计数。add/edit 走 @Validated 会触发本约束；
 * GET 列表查询参数不走 @Validated，故不影响以 status/noticeType 作筛选条件。
 * </p>
 */
public class SysNoticeValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        if (factory != null) {
            factory.close();
        }
    }

    private static SysNotice notice(String type, String status) {
        SysNotice n = new SysNotice();
        n.setNoticeTitle("标题"); // 满足 @NotBlank/@Xss，隔离到只考察 type/status
        n.setNoticeType(type);
        n.setStatus(status);
        return n;
    }

    private static Set<String> msgsOn(SysNotice n, String prop) {
        return validator.validate(n).stream()
                .filter(v -> prop.equals(v.getPropertyPath().toString()))
                .map(v -> v.getMessage())
                .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("noticeType=1/2、status=0/1 合法，无 violation")
    void validValuesPass() {
        assertTrue(msgsOn(notice("1", "0"), "noticeType").isEmpty());
        assertTrue(msgsOn(notice("2", "1"), "noticeType").isEmpty());
        assertTrue(msgsOn(notice("1", "0"), "status").isEmpty());
        assertTrue(msgsOn(notice("2", "1"), "status").isEmpty());
    }

    @Test
    @DisplayName("noticeType/status 非法值各被拒一次")
    void invalidValuesRejected() {
        assertEquals(1, msgsOn(notice("9", "0"), "noticeType").size());
        assertEquals(1, msgsOn(notice("1", "2"), "status").size());
    }

    @Test
    @DisplayName("noticeType/status 为 null 放行（@Pattern 不拦 null）")
    void nullPasses() {
        assertTrue(msgsOn(notice(null, null), "noticeType").isEmpty());
        assertTrue(msgsOn(notice(null, null), "status").isEmpty());
    }
}
