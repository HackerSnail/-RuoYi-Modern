package com.ruoyi.common.utils.file;

import java.io.File;
import org.apache.commons.lang3.StringUtils;

/**
 * 文件类型工具类
 *
 * @author ruoyi
 */
public class FileTypeUtils {
    /**
     * 获取文件类型
     * <p>
     * 例如: ruoyi.txt, 返回: txt
     * 
     * @param file 文件名
     * @return 后缀（不含".")
     */
    public static String getFileType(File file) {
        if (null == file) {
            return StringUtils.EMPTY;
        }
        return getFileType(file.getName());
    }

    /**
     * 获取文件类型
     * <p>
     * 例如: ruoyi.txt, 返回: txt
     *
     * @param fileName 文件名
     * @return 后缀（不含".")
     */
    public static String getFileType(String fileName) {
        int separatorIndex = fileName.lastIndexOf(".");
        if (separatorIndex < 0) {
            return "";
        }
        return fileName.substring(separatorIndex + 1).toLowerCase();
    }

    /**
     * 获取文件类型
     * 
     * @param photoByte 文件字节码
     * @return 后缀（不含".")
     */
    public static String getFileExtendName(byte[] photoByte) {
        String strFileExtendName = "JPG";
        // 数据为空或长度不足以读取最长文件头（JFIF 需到 index 9）时回落默认类型，避免 NPE / 越界
        if (photoByte == null || photoByte.length < 10) {
            return strFileExtendName;
        }
        // 各图片格式的文件头魔数以对应 ASCII 字符字面量表示，语义自解释
        if (photoByte[0] == 'G' && photoByte[1] == 'I' && photoByte[2] == 'F' && photoByte[3] == '8'
                && (photoByte[4] == '7' || photoByte[4] == '9') && photoByte[5] == 'a') {
            // GIF 签名 "GIF87a" / "GIF89a"
            strFileExtendName = "GIF";
        } else if (photoByte[6] == 'J' && photoByte[7] == 'F' && photoByte[8] == 'I' && photoByte[9] == 'F') {
            // JPEG 的 "JFIF" 应用标记
            strFileExtendName = "JPG";
        } else if (photoByte[0] == 'B' && photoByte[1] == 'M') {
            // BMP 签名 "BM"
            strFileExtendName = "BMP";
        } else if (photoByte[1] == 'P' && photoByte[2] == 'N' && photoByte[3] == 'G') {
            // PNG 签名 0x89 后紧跟 "PNG"
            strFileExtendName = "PNG";
        }
        return strFileExtendName;
    }
}
