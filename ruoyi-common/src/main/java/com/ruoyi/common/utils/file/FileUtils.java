package com.ruoyi.common.utils.file;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.ArrayUtils;
import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.constant.Constants;
import com.ruoyi.common.utils.DateUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.common.utils.uuid.IdUtils;

/**
 * 文件处理工具类
 * 
 * @author ruoyi
 */
public final class FileUtils {
    private FileUtils() {
    }

    /**
     * 输出指定文件的 byte 数组到给定的输出流
     *
     * <p>
     * 输入流（本地文件）用 try-with-resources 自动关闭；输出流 {@code os} 由调用方传入，其
     * 生命周期归调用方 / Servlet 容器，本方法只 flush、不关闭，避免与调用方的关流动作产生
     * 双重关闭歧义。
     * </p>
     *
     * @param filePath 文件路径
     * @param os       输出流（本方法既不打开也不关闭，仅在写完后 flush）
     * @throws IOException 文件不存在或读写异常
     */
    public static void writeBytes(String filePath, OutputStream os) throws IOException {
        File file = new File(filePath);
        if (!file.exists()) {
            throw new FileNotFoundException(filePath);
        }
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] b = new byte[1024];
            int length;
            while ((length = fis.read(b)) > 0) {
                os.write(b, 0, length);
            }
            // 只 flush 不 close：确保数据进入容器输出缓冲区，流关闭交由持有方负责
            os.flush();
        }
    }

    /**
     * 写数据到文件中
     *
     * @param data 数据
     * @return 目标文件
     * @throws IOException IO异常
     */
    public static String writeImportBytes(byte[] data) throws IOException {
        return writeBytes(data, RuoYiConfig.getImportPath());
    }

    /**
     * 写数据到文件中
     *
     * @param data      数据
     * @param uploadDir 目标文件
     * @return 目标文件
     * @throws IOException IO异常
     */
    public static String writeBytes(byte[] data, String uploadDir) throws IOException {
        String extension = getFileExtendName(data);
        String pathName = DateUtils.datePath() + "/" + IdUtils.fastUUID() + "." + extension;
        File file = FileUploadUtils.getAbsoluteFile(uploadDir, pathName);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(data);
        }
        return FileUploadUtils.getPathFileName(uploadDir, pathName);
    }

    /**
     * 移除路径中的请求前缀片段
     * 
     * @param filePath 文件路径
     * @return 移除后的文件路径
     */
    public static String stripPrefix(String filePath) {
        return StringUtils.substringAfter(filePath, Constants.RESOURCE_PREFIX);
    }

    /**
     * 删除文件
     * 
     * @param filePath 文件
     * @return
     */
    public static boolean deleteFile(String filePath) {
        boolean flag = false;
        File file = new File(filePath);
        // 路径为文件且不为空则进行删除
        if (file.isFile() && file.exists()) {
            flag = file.delete();
        }
        return flag;
    }

    /**
     * 检查文件是否可下载
     * <p>
     * 两道前置：其一按分隔符（正/反斜杠）逐段拆开后判断是否存在父目录跳转段 ".."——取代历史
     * "整串 contains(..)" 的弱检测，既仍能拦截 "..\"、解码后的 %2e%2e，又不会误伤 "a..b.pdf" 之类
     * 含连续点但无跳转的合法名；其二要求后缀落在允许下载白名单内。真正的基目录锚定由调用方在
     * 拼出最终路径后用 {@link #isPathUnderBase(String, String)} 完成（此处只有相对字符串、无从知晓根）。
     *
     * @param resource 需要下载的文件
     * @return true 正常 false 非法
     */
    public static boolean checkAllowDownload(String resource) {
        // 禁止目录上跳级别
        if (StringUtils.isEmpty(resource) || containsParentTraversal(resource)) {
            return false;
        }

        // 检查允许下载的文件规则
        if (ArrayUtils.contains(MimeTypeUtils.DEFAULT_ALLOWED_EXTENSION, FileTypeUtils.getFileType(resource))) {
            return true;
        }

        // 不在允许下载的文件规则
        return false;
    }

    /**
     * 按文件系统分隔符逐段拆分，任一段等于 ".." 即判定为父目录跳转
     */
    private static boolean containsParentTraversal(String path) {
        for (String segment : path.split("[/\\\\]")) {
            if ("..".equals(segment)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 校验 target 解析（canonical 归一，消解 "."/".."/符号链接）后是否仍位于 baseDir 之内。
     * <p>
     * 用于下载/删除等"拼接出最终路径再落文件系统"的场景做基目录锚定，杜绝通过拼接、编码、
     * 软链接等方式跳出根目录。比较按分隔符边界进行，避免 /download 误配 /downloadbak 之类前缀。
     *
     * @param baseDir 允许访问的基目录
     * @param target  待校验的目标路径
     * @return true 目标在基目录内 false 越界
     * @throws IOException 路径解析失败
     */
    public static boolean isPathUnderBase(String baseDir, String target) throws IOException {
        String canonicalBase = new File(baseDir).getCanonicalPath();
        String canonicalTarget = new File(target).getCanonicalPath();
        return canonicalTarget.equals(canonicalBase)
                || canonicalTarget.startsWith(canonicalBase + File.separator);
    }

    /**
     * 下载文件名重新编码（Content-Disposition，RFC 6266 双轨）
     * <p>
     * filename= 给一个纯 ASCII 兜底名，兼容只识别传统形式的老浏览器/中间层；filename*=UTF-8''
     * 用百分号编码承载原始名，现代浏览器优先取它，非 ASCII 文件名不再依赖 filename= 字段。
     * 另单独暴露 download-filename 头供前端读取。
     *
     * @param response     响应对象
     * @param realFileName 真实文件名
     */
    public static void setAttachmentResponseHeader(HttpServletResponse response, String realFileName)
            throws UnsupportedEncodingException {
        String percentEncodedFileName = percentEncode(realFileName);
        String asciiFallback = toAsciiFilenameFallback(realFileName);

        StringBuilder contentDispositionValue = new StringBuilder();
        contentDispositionValue.append("attachment; filename=\"")
                .append(asciiFallback)
                .append("\";")
                .append("filename*=")
                .append("utf-8''")
                .append(percentEncodedFileName);

        response.addHeader("Access-Control-Expose-Headers", "Content-Disposition,download-filename");
        response.setHeader("Content-disposition", contentDispositionValue.toString());
        response.setHeader("download-filename", percentEncodedFileName);
    }

    /**
     * 生成 Content-Disposition filename= 使用的纯 ASCII 兜底名：非 [a-zA-Z0-9._-] 字符一律替换为下划线
     */
    private static String toAsciiFilenameFallback(String fileName) {
        return StringUtils.isEmpty(fileName) ? "download" : fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    /**
     * 百分号编码工具方法
     *
     * @param s 需要百分号编码的字符串
     * @return 百分号编码后的字符串
     */
    public static String percentEncode(String s) throws UnsupportedEncodingException {
        String encode = URLEncoder.encode(s, StandardCharsets.UTF_8.toString());
        return encode.replaceAll("\\+", "%20");
    }

    /**
     * 获取图像后缀
     * 
     * @param photoByte 图像数据
     * @return 后缀名
     */
    public static String getFileExtendName(byte[] photoByte) {
        // 复用 FileTypeUtils 的文件头魔数识别（含空/短数据回落）；本类历史返回小写后缀，故 toLowerCase 保持语义不变
        return FileTypeUtils.getFileExtendName(photoByte).toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * 获取文件名称 /profile/upload/2022/04/16/ruoyi.png -- ruoyi.png
     * 
     * @param fileName 路径名称
     * @return 没有文件路径的名称
     */
    public static String getName(String fileName) {
        if (fileName == null) {
            return null;
        }
        int lastUnixPos = fileName.lastIndexOf('/');
        int lastWindowsPos = fileName.lastIndexOf('\\');
        int index = Math.max(lastUnixPos, lastWindowsPos);
        return fileName.substring(index + 1);
    }

    /**
     * 获取不带后缀文件名称 /profile/upload/2022/04/16/ruoyi.png -- ruoyi
     * 
     * @param fileName 路径名称
     * @return 没有文件路径和后缀的名称
     */
    public static String getNameNotSuffix(String fileName) {
        if (fileName == null) {
            return null;
        }
        String baseName = FilenameUtils.getBaseName(fileName);
        return baseName;
    }
}
