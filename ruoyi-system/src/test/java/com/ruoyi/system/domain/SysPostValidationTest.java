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
 * SysPost 校验注解（B 档）测试，无 Spring：用 Jakarta Validator 直测 @Pattern/@Min 语义。
 *
 * <p>
 * 覆盖本轮新增约束：postCode 字符集白名单、status 枚举 0/1、postSort 非负；并验证 @Size(min=0) 去除无副作用。
 * 合法值放行、非法值拒绝、@Pattern 对 null 放行（status 兼容不传）。按属性路径过滤断言，隔离到单个字段。
 * add/edit 走 @Validated 会触发本约束；GET 列表查询参数不走 @Validated，故不影响以 postCode/status 作筛选条件。
 * </p>
 */
public class SysPostValidationTest {

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

    /** 一条整体合法的岗位，便于只改动被测字段、隔离到单个属性 */
    private static SysPost validPost() {
        SysPost p = new SysPost();
        p.setPostCode("ceo");
        p.setPostName("董事长");
        p.setPostSort(1);
        p.setStatus("0");
        return p;
    }

    private static Set<String> msgsOn(SysPost p, String prop) {
        return validator.validate(p).stream()
                .filter(v -> prop.equals(v.getPropertyPath().toString()))
                .map(v -> v.getMessage())
                .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("整体合法岗位无任何 violation（含字母/下划线/短横线编码）")
    void validPostPasses() {
        assertTrue(validator.validate(validPost()).isEmpty());
        SysPost p = validPost();
        p.setPostCode("dev_ops-1");
        assertTrue(msgsOn(p, "postCode").isEmpty(), "下划线/短横线应放行");
    }

    @Test
    @DisplayName("postCode 含空格/尖括号/中文各被拒一次（字符集白名单）")
    void illegalPostCodeRejected() {
        SysPost space = validPost();
        space.setPostCode("bad code");
        assertEquals(1, msgsOn(space, "postCode").size());

        SysPost angle = validPost();
        angle.setPostCode("a<b");
        assertEquals(1, msgsOn(angle, "postCode").size());

        SysPost cn = validPost();
        cn.setPostCode("中文码");
        assertEquals(1, msgsOn(cn, "postCode").size());
    }

    @Test
    @DisplayName("status 只能 0/1；非法值被拒、null 放行（@Pattern 不拦 null）")
    void statusEnum() {
        SysPost bad = validPost();
        bad.setStatus("2");
        assertEquals(1, msgsOn(bad, "status").size());

        SysPost nullStatus = validPost();
        nullStatus.setStatus(null);
        assertTrue(msgsOn(nullStatus, "status").isEmpty());
    }

    @Test
    @DisplayName("postSort 负数被 @Min(0) 拒绝，0 放行")
    void postSortNonNegative() {
        SysPost neg = validPost();
        neg.setPostSort(-1);
        assertEquals(1, msgsOn(neg, "postSort").size());

        SysPost zero = validPost();
        zero.setPostSort(0);
        assertTrue(msgsOn(zero, "postSort").isEmpty());
    }
}
