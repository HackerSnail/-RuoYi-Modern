package com.ruoyi.common.utils.file;

import java.util.Locale;

/**
 * 媒体类型工具类
 *
 * @author HackerSnail <2306002088@qq.com>
 */
public final class MimeTypeUtils {
    public static final String IMAGE_PNG = "image/png";

    /**
     * @deprecated 非标准 MIME（标准是 image/jpeg）。保留仅为兼容历史引用，getExtension 里仍一并识别。
     */
    @Deprecated
    public static final String IMAGE_JPG = "image/jpg";

    public static final String IMAGE_JPEG = "image/jpeg";

    public static final String IMAGE_BMP = "image/bmp";

    public static final String IMAGE_GIF = "image/gif";

    /**
     * 图片扩展名（头像等仅允许图片的场景复用此组）
     */
    public static final String[] IMAGE_EXTENSION = { "bmp", "gif", "jpg", "jpeg", "png" };

    /**
     * @deprecated Flash 已基本退出，swf/flv 不应再作为允许上传的类型。保留仅为兼容旧引用，已不并入默认白名单。
     */
    @Deprecated
    public static final String[] FLASH_EXTENSION = { "swf", "flv" };

    /**
     * 音视频媒体扩展名。已移除与 VIDEO_EXTENSION 重复的 avi、rmvb（统一由 VIDEO_EXTENSION 持有），消除两数组重叠易漏维护的问题。
     */
    public static final String[] MEDIA_EXTENSION = { "swf", "flv", "mp3", "wav", "wma", "wmv", "mid", "mpg", "asf",
            "rm" };

    /**
     * 视频扩展名
     */
    public static final String[] VIDEO_EXTENSION = { "mp4", "avi", "rmvb" };

    /**
     * Office 文档 / 文本 / PDF 扩展名
     */
    public static final String[] OFFICE_EXTENSION = { "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "pdf" };

    /**
     * 压缩包扩展名
     */
    public static final String[] ARCHIVE_EXTENSION = { "rar", "zip", "gz", "bz2" };

    /**
     * 数据导入（Excel）专用扩展名，供导入入口做前置类型拦截
     */
    public static final String[] IMPORT_EXTENSION = { "xls", "xlsx" };

    /**
     * 通用默认允许上传的扩展名 = 图片 + Office + 压缩 + 视频 各场景集合的并集。
     * <p>
     * 保持与历史内联字面量完全相同的元素集合（零行为变更），仅改为由分场景常量组合、形成单一事实来源；
     * 需要更严格策略的端点应引用对应场景常量（如头像用 IMAGE_EXTENSION、导入用 IMPORT_EXTENSION），
     * 而非再依赖这个宽泛的默认集。
     */
    public static final String[] DEFAULT_ALLOWED_EXTENSION = merge(IMAGE_EXTENSION, OFFICE_EXTENSION,
            ARCHIVE_EXTENSION, VIDEO_EXTENSION);

    private MimeTypeUtils() {
    }

    /**
     * 合并多组扩展名为一个数组（保序拼接，用于组合分场景常量成默认全集）
     */
    private static String[] merge(String[]... groups) {
        int total = 0;
        for (String[] group : groups) {
            total += group.length;
        }
        String[] merged = new String[total];
        int pos = 0;
        for (String[] group : groups) {
            System.arraycopy(group, 0, merged, pos, group.length);
            pos += group.length;
        }
        return merged;
    }

    /**
     * 由 MIME 类型推导扩展名。
     * <p>
     * 健壮化：null 安全（原 switch(null) 会 NPE）；忽略大小写与首尾空白；剥离参数后缀（如
     * "image/png; charset=utf-8"）；同时识别非标准 image/jpg 与标准 image/jpeg（分别回 jpg / jpeg，
     * 与历史返回值保持一致，不改既有文件名生成结果）。未知类型返回空串。
     *
     * @param mimeType MIME 类型串
     * @return 扩展名（不含点），无法识别返回空串
     */
    public static String getExtension(String mimeType) {
        if (mimeType == null) {
            return "";
        }
        String type = mimeType.trim();
        int semicolon = type.indexOf(';');
        if (semicolon >= 0) {
            type = type.substring(0, semicolon).trim();
        }
        type = type.toLowerCase(Locale.ROOT);
        switch (type) {
            case IMAGE_PNG:
                return "png";
            case IMAGE_JPEG:
                return "jpeg";
            case IMAGE_JPG:
                return "jpg";
            case IMAGE_BMP:
                return "bmp";
            case IMAGE_GIF:
                return "gif";
            default:
                return "";
        }
    }
}
