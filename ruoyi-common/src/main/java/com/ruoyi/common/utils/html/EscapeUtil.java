package com.ruoyi.common.utils.html;

import com.ruoyi.common.utils.StringUtils;

/**
 * 转义和反转义工具类
 * <p>
 * 注意分工：{@link #escape(String)} / {@link #unescape(String)} 实现的是 JavaScript
 * {@code escape()} 风格的 {@code %xx} / {@code %uxxxx} 百分号编码，<b>不是</b> HTML 实体转义，
 * 也不能当作 XSS 防护手段——编码后的串放进 HTML 上下文并不因此安全。真正的 HTML 白名单清洗是
 * {@link #clean(String)}（转发到 {@link HTMLFilter}），HTML/属性/URL 等上下文需要各自的编码策略，
 * 不能靠本类的 escape 一概而论。
 *
 * @author ruoyi
 */
public final class EscapeUtil {
    public static final String RE_HTML_MARK = "(<[^<]*?>)|(<[\\s]*?/[^<]*?>)|(<[^<]*?/[\\s]*?>)";

    private EscapeUtil() {
    }

    /**
     * 对文本做 escape 风格百分号编码（{@code %xx}/{@code %uxxxx}）。
     * <p>
     * 语义提示：这不是 HTML 转义，编码结果不具备 HTML 安全性；若目标是清洗 HTML/XSS，请用
     * {@link #clean(String)}。
     *
     * @param text 被编码的文本
     * @return 百分号编码后的文本
     */
    public static String escape(String text) {
        return encode(text);
    }

    /**
     * 还原 {@link #escape(String)} 生成的百分号编码。
     * <p>
     * 对畸形输入采用宽松策略：无法识别的 {@code %} 序列按字面量原样保留，绝不抛异常。
     *
     * @param content 包含百分号编码的内容
     * @return 解码后的字符串
     */
    public static String unescape(String content) {
        return decode(content);
    }

    /**
     * 清除所有HTML标签，但是不删除标签内的内容
     * <p>
     * 真正的 HTML 白名单过滤逻辑在 {@link HTMLFilter}，本方法是其转发入口，也是本类唯一面向
     * 安全场景（XSS 清洗）应被调用的方法。
     *
     * @param content 文本
     * @return 清除标签后的文本
     */
    public static String clean(String content) {
        return new HTMLFilter().filter(content);
    }

    /**
     * Escape编码（JavaScript escape() 风格的百分号编码，非 HTML 实体转义）
     *
     * @param text 被编码的文本
     * @return 编码后的字符
     */
    private static String encode(String text) {
        if (StringUtils.isEmpty(text)) {
            return StringUtils.EMPTY;
        }

        final StringBuilder tmp = new StringBuilder(text.length() * 6);
        char c;
        for (int i = 0; i < text.length(); i++) {
            c = text.charAt(i);
            if (c < 256) {
                tmp.append("%");
                if (c < 16) {
                    tmp.append("0");
                }
                tmp.append(Integer.toString(c, 16));
            } else {
                tmp.append("%u");
                if (c <= 0xfff) {
                    // issue#I49JU8@Gitee
                    tmp.append("0");
                }
                tmp.append(Integer.toString(c, 16));
            }
        }
        return tmp.toString();
    }

    /**
     * Escape解码（还原 {@link #encode(String)} 的百分号编码）。
     * <p>
     * 硬化点：原实现对 {@code %}/{@code %u} 后续十六进制位不做长度与字符校验，遇到以 {@code %}
     * 结尾、位数不足、或含非十六进制字符的畸形输入，会抛
     * {@link IndexOutOfBoundsException} / {@link NumberFormatException}。现改为逐段校验长度与
     * 十六进制合法性，任何无法识别的 {@code %} 序列按字面量保留并前进一个字符（避免死循环），
     * 对合法输入的结果与原实现完全一致。
     *
     * @param content 被转义的内容
     * @return 解码后的字符串
     */
    public static String decode(String content) {
        if (StringUtils.isEmpty(content)) {
            return content;
        }

        final int len = content.length();
        StringBuilder tmp = new StringBuilder(len);
        int lastPos = 0;
        int pos;
        while (lastPos < len) {
            pos = content.indexOf('%', lastPos);
            if (pos < 0) {
                // 之后再无转义符，原样追加剩余内容
                tmp.append(content, lastPos, len);
                break;
            }
            // 追加当前 '%' 之前的普通文本
            tmp.append(content, lastPos, pos);
            if (pos + 1 < len && content.charAt(pos + 1) == 'u') {
                // %uXXXX：需 "%u" 之后紧跟 4 位十六进制
                if (pos + 6 <= len && isHex(content, pos + 2, 4)) {
                    tmp.append((char) Integer.parseInt(content.substring(pos + 2, pos + 6), 16));
                    lastPos = pos + 6;
                } else {
                    // 畸形：% 按字面量保留，前进一个字符防止死循环
                    tmp.append('%');
                    lastPos = pos + 1;
                }
            } else if (pos + 3 <= len && isHex(content, pos + 1, 2)) {
                // %XX：需 '%' 之后紧跟 2 位十六进制
                tmp.append((char) Integer.parseInt(content.substring(pos + 1, pos + 3), 16));
                lastPos = pos + 3;
            } else {
                // 畸形或 '%' 结尾：按字面量保留
                tmp.append('%');
                lastPos = pos + 1;
            }
        }
        return tmp.toString();
    }

    /**
     * 判断字符串从 start 起的连续 count 个字符是否全部为十六进制数字
     */
    private static boolean isHex(String s, int start, int count) {
        for (int i = start; i < start + count; i++) {
            if (Character.digit(s.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }
}
