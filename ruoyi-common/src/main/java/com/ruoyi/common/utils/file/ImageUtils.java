package com.ruoyi.common.utils.file;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.Arrays;
import org.apache.poi.util.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.constant.Constants;
import com.ruoyi.common.utils.StringUtils;

/**
 * 图片处理工具类
 *
 * @author ruoyi
 */
public class ImageUtils {
    private static final Logger log = LoggerFactory.getLogger(ImageUtils.class);

    /**
     * 读取图片文件为字节数组
     *
     * <p>
     * 内部使用 try-with-resources 管理输入流，异常时返回 null 并记录日志。
     * </p>
     *
     * @param imagePath 图片路径（支持 http/local 两种形式）
     * @return 图片字节数组，失败返回 null
     */
    public static byte[] getImage(String imagePath) {
        try (InputStream is = getFile(imagePath)) {
            return IOUtils.toByteArray(is);
        } catch (Exception e) {
            log.error("图片加载异常 {}", e);
            return null;
        }
    }

    /**
     * 获取图片的 InputStream（内部委托 readFile 读取后包装为 ByteArrayInputStream）
     *
     * @param imagePath 图片路径
     * @return 输入流，失败返回 null
     */
    public static InputStream getFile(String imagePath) {
        try {
            byte[] result = readFile(imagePath);
            result = Arrays.copyOf(result, result.length);
            return new ByteArrayInputStream(result);
        } catch (Exception e) {
            log.error("获取图片异常 {}", e);
        }
        return null;
    }

    /**
     * 读取文件为字节数据
     *
     * <p>
     * 支持 http(s) 网络地址与本地文件路径两种分支，各自用独立 TWR 块管理 InputStream。
     * </p>
     *
     * @param url 地址（以 "http" 开头则为网络地址，否则为本机路径）
     * @return 字节数据，失败返回 null
     */
    public static byte[] readFile(String url) {
        try {
            if (url.startsWith("http")) {
                // 网络地址
                URL urlObj = new URL(url);
                URLConnection urlConnection = urlObj.openConnection();
                urlConnection.setConnectTimeout(30 * 1000);
                urlConnection.setReadTimeout(60 * 1000);
                urlConnection.setDoInput(true);
                try (InputStream in = urlConnection.getInputStream()) {
                    return IOUtils.toByteArray(in);
                }
            } else {
                // 本机地址
                String localPath = RuoYiConfig.getProfile();
                String downloadPath = localPath + StringUtils.substringAfter(url, Constants.RESOURCE_PREFIX);
                try (InputStream in = new FileInputStream(downloadPath)) {
                    return IOUtils.toByteArray(in);
                }
            }
        } catch (Exception e) {
            log.error("获取文件路径异常 {}", e);
            return null;
        }
    }
}
