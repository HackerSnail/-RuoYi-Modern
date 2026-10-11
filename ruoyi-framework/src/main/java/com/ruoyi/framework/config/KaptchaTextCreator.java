package com.ruoyi.framework.config;

import java.security.SecureRandom;
import com.google.code.kaptcha.text.impl.DefaultTextCreator;

/**
 * 验证码文本生成器
 *
 * @author HackerSnail <2306002088@qq.com>
 */
public class KaptchaTextCreator extends DefaultTextCreator {
    /** 算术操作数随机源：SecureRandom 单例（线程安全），避免每次 getText 新建、并提升抗预测性 */
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 生成算术验证码文本：随机取 0-9 两数并按随机运算符（乘/除/加/减）拼成算式，
     * 除法仅在被整除时采用、否则退化为加法，减法保证结果非负；
     * 末尾以 "=?@" 拼接真实答案，校验时由后端截取 @ 之后的数字比对
     */
    @Override
    public String getText() {
        int result;
        int x = RANDOM.nextInt(10);
        int y = RANDOM.nextInt(10);
        StringBuilder suChinese = new StringBuilder();
        int randomoperands = RANDOM.nextInt(3);
        if (randomoperands == 0) {
            result = x * y;
            suChinese.append(x);
            suChinese.append("*");
            suChinese.append(y);
        } else if (randomoperands == 1) {
            if ((x != 0) && y % x == 0) {
                result = y / x;
                suChinese.append(y);
                suChinese.append("/");
                suChinese.append(x);
            } else {
                result = x + y;
                suChinese.append(x);
                suChinese.append("+");
                suChinese.append(y);
            }
        } else {
            if (x >= y) {
                result = x - y;
                suChinese.append(x);
                suChinese.append("-");
                suChinese.append(y);
            } else {
                result = y - x;
                suChinese.append(y);
                suChinese.append("-");
                suChinese.append(x);
            }
        }
        suChinese.append("=?@").append(result);
        return suChinese.toString();
    }
}
