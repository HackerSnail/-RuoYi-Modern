package com.ruoyi.common.utils.file;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Objects;
import org.apache.commons.io.FilenameUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.multipart.MultipartFile;
import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.constant.Constants;
import com.ruoyi.common.exception.file.FileNameLengthLimitExceededException;
import com.ruoyi.common.exception.file.FileSizeLimitExceededException;
import com.ruoyi.common.exception.file.InvalidExtensionException;
import com.ruoyi.common.utils.DateUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.common.utils.uuid.IdUtils;
import com.ruoyi.common.utils.uuid.Seq;

/**
 * 文件上传工具类
 * 
 * @author ruoyi
 */
public final class FileUploadUtils {
    private static final Logger log = LoggerFactory.getLogger(FileUploadUtils.class);

    /**
     * 默认大小 50M
     */
    public static final long DEFAULT_MAX_SIZE = 50 * 1024 * 1024L;

    /**
     * 默认的文件名最大长度 100
     */
    public static final int DEFAULT_FILE_NAME_LENGTH = 100;

    /**
     * 内容魔数校验时读取的文件头字节数：需覆盖最长签名（OLE2/ PNG 头 8 字节、AVI 的 "AVI " 位于偏移 8 需 12 字节）
     */
    private static final int MAGIC_HEADER_LENGTH = 12;

    /**
     * 默认上传的地址。不在类加载时急切取 RuoYiConfig.getProfile()，避免与本 bean 的
     * @ConfigurationProperties 绑定产生启动顺序耦合（历史实现静态初始化取到 null 会长期滞留）；
     * 未显式设置时由 getDefaultBaseDir() 实时回落。
     */
    private static String defaultBaseDir;

    private FileUploadUtils() {
    }

    public static void setDefaultBaseDir(String defaultBaseDir) {
        FileUploadUtils.defaultBaseDir = defaultBaseDir;
    }

    public static String getDefaultBaseDir() {
        return defaultBaseDir != null ? defaultBaseDir : RuoYiConfig.getProfile();
    }

    /**
     * 以默认配置进行文件上传
     *
     * @param file 上传的文件
     * @return 文件名称
     * @throws Exception
     */
    public static final String upload(MultipartFile file) throws IOException {
        try {
            return upload(getDefaultBaseDir(), file, MimeTypeUtils.DEFAULT_ALLOWED_EXTENSION);
        } catch (Exception e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * 根据文件路径上传
     *
     * @param baseDir 相对应用的基目录
     * @param file    上传的文件
     * @return 文件名称
     * @throws IOException
     */
    public static final String upload(String baseDir, MultipartFile file) throws IOException {
        try {
            return upload(baseDir, file, MimeTypeUtils.DEFAULT_ALLOWED_EXTENSION);
        } catch (Exception e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * 文件上传
     *
     * @param baseDir          相对应用的基目录
     * @param file             上传的文件
     * @param allowedExtension 上传文件类型
     * @return 返回上传成功的文件名
     * @throws FileSizeLimitExceededException       如果超出最大大小
     * @throws FileNameLengthLimitExceededException 文件名太长
     * @throws IOException                          比如读写文件出错时
     * @throws InvalidExtensionException            文件校验异常
     */
    public static final String upload(String baseDir, MultipartFile file, String[] allowedExtension)
            throws FileSizeLimitExceededException, IOException, FileNameLengthLimitExceededException,
            InvalidExtensionException {
        return upload(baseDir, file, allowedExtension, false);
    }

    /**
     * 文件上传
     *
     * @param baseDir          相对应用的基目录
     * @param file             上传的文件
     * @param useCustomNaming  系统自定义文件名
     * @param allowedExtension 上传文件类型
     * @return 返回上传成功的文件名
     * @throws FileSizeLimitExceededException       如果超出最大大小
     * @throws FileNameLengthLimitExceededException 文件名太长
     * @throws IOException                          比如读写文件出错时
     * @throws InvalidExtensionException            文件校验异常
     */
    public static final String upload(String baseDir, MultipartFile file, String[] allowedExtension,
            boolean useCustomNaming)
            throws FileSizeLimitExceededException, IOException, FileNameLengthLimitExceededException,
            InvalidExtensionException {
        int fileNameLength = Objects.requireNonNull(file.getOriginalFilename()).length();
        if (fileNameLength > FileUploadUtils.DEFAULT_FILE_NAME_LENGTH) {
            throw new FileNameLengthLimitExceededException(FileUploadUtils.DEFAULT_FILE_NAME_LENGTH);
        }

        assertAllowed(file, allowedExtension);

        String fileName = useCustomNaming ? uuidFilename(file) : extractFilename(file);

        String absPath = getAbsoluteFile(baseDir, fileName).getAbsolutePath();
        file.transferTo(Paths.get(absPath));
        return getPathFileName(baseDir, fileName);
    }

    /**
     * 编码文件名(日期格式目录 + 原文件名 + 序列值 + 后缀)
     */
    public static final String extractFilename(MultipartFile file) {
        return StringUtils.format("{}/{}_{}.{}", DateUtils.datePath(),
                FilenameUtils.getBaseName(file.getOriginalFilename()), Seq.getId(Seq.uploadSeqType),
                getExtension(file));
    }

    /**
     * 编编码文件名(日期格式目录 + UUID + 后缀)
     */
    public static final String uuidFilename(MultipartFile file) {
        return StringUtils.format("{}/{}.{}", DateUtils.datePath(), IdUtils.fastSimpleUUID(), getExtension(file));
    }

    public static final File getAbsoluteFile(String uploadDir, String fileName) throws IOException {
        File desc = new File(uploadDir + File.separator + fileName);

        // 归一化后核验目标仍在上传目录内：fileName 已由 getBaseName 去目录 + 后缀白名单，
        // 但 baseDir 若被配成含 .. 或指向符号链接仍可跳出，这里做纵深防御，越界直接拒绝写入
        String canonicalBase = new File(uploadDir).getCanonicalPath();
        String canonicalDesc = desc.getCanonicalPath();
        if (!canonicalDesc.startsWith(canonicalBase + File.separator) && !canonicalDesc.equals(canonicalBase)) {
            throw new IOException("非法的文件路径，拒绝写入上传目录之外：" + fileName);
        }

        if (!desc.exists()) {
            if (!desc.getParentFile().exists()) {
                desc.getParentFile().mkdirs();
            }
        }
        return desc;
    }

    /**
     * 返回可供前端访问的虚拟资源路径：去掉本地绝对目录中属于 profile 磁盘路径的前缀，
     * 换成统一的任务前缀 RESOURCE_PREFIX，拼接为 /profile/日期目录/文件名 形式
     * <p>
     * 用 Path.relativize 取代历史 "profile.length() + 1" 的定长截取：后者依赖 profile 恰为
     * uploadDir 前缀且只差一个分隔符，配置末尾斜杠/多段路径变化时易截错；relativize 对
     * 规范化后的真实前缀关系求解，末尾斜杠差异由 normalize 抹平，输出仍统一为正斜杠。
     */
    public static final String getPathFileName(String uploadDir, String fileName) throws IOException {
        Path profilePath = Paths.get(RuoYiConfig.getProfile()).normalize();
        Path uploadPath = Paths.get(uploadDir).normalize();
        String currentDir = profilePath.relativize(uploadPath).toString().replace(File.separatorChar, '/');
        String path = Constants.RESOURCE_PREFIX;
        if (StringUtils.isNotEmpty(currentDir)) {
            path += "/" + currentDir;
        }
        return path + "/" + fileName;
    }

    /**
     * 文件大小、后缀白名单、内容魔数校验
     *
     * @param file 上传的文件
     * @return
     * @throws FileSizeLimitExceededException 如果超出最大大小
     * @throws InvalidExtensionException
     */
    public static final void assertAllowed(MultipartFile file, String[] allowedExtension)
            throws FileSizeLimitExceededException, InvalidExtensionException {
        long size = file.getSize();
        if (size > DEFAULT_MAX_SIZE) {
            throw new FileSizeLimitExceededException(DEFAULT_MAX_SIZE / 1024 / 1024);
        }

        String fileName = file.getOriginalFilename();
        String extension = getExtension(file);
        if (allowedExtension != null && !isAllowedExtension(extension, allowedExtension)) {
            throw selectInvalidExtensionException(allowedExtension, extension, fileName);
        }

        // 后缀只是第一道防线：攻击者可将任意恶意文件改名 .jpg 绕过白名单。这里按后缀对应的
        // 文件头魔数核验真实类型，不符即拒绝；无稳定签名的格式（txt 等）在 matchesMagic 内放行，
        // 交由后缀白名单兜底，避免因缺少公开签名而误拒合法上传。
        if (allowedExtension != null && !matchesMagic(StringUtils.lowerCase(extension), readFileHeader(file))) {
            log.warn("文件内容与后缀不符，疑似伪造: name={}, extension={}", fileName, extension);
            throw selectInvalidExtensionException(allowedExtension, extension, fileName);
        }
    }

    /**
     * 按允许类型选择对应的无效后缀异常子类（文案更精确）。
     * <p>
     * 用 Arrays.equals 做内容比较取代历史的数组引用 ==：引用相等仅在调用方恰好传入
     * MimeTypeUtils 常量本身时成立，一旦传入内容相同的新数组就会误落到通用子类。
     */
    private static InvalidExtensionException selectInvalidExtensionException(String[] allowedExtension,
            String extension, String fileName) {
        if (Arrays.equals(allowedExtension, MimeTypeUtils.IMAGE_EXTENSION)) {
            return new InvalidExtensionException.InvalidImageExtensionException(allowedExtension, extension, fileName);
        } else if (Arrays.equals(allowedExtension, MimeTypeUtils.FLASH_EXTENSION)) {
            return new InvalidExtensionException.InvalidFlashExtensionException(allowedExtension, extension, fileName);
        } else if (Arrays.equals(allowedExtension, MimeTypeUtils.MEDIA_EXTENSION)) {
            return new InvalidExtensionException.InvalidMediaExtensionException(allowedExtension, extension, fileName);
        } else if (Arrays.equals(allowedExtension, MimeTypeUtils.VIDEO_EXTENSION)) {
            return new InvalidExtensionException.InvalidVideoExtensionException(allowedExtension, extension, fileName);
        }
        return new InvalidExtensionException(allowedExtension, extension, fileName);
    }

    /**
     * 校验文件头魔数是否与声称的后缀一致。
     * <p>
     * 仅收录签名稳定、无误拒风险的格式强制核验；其余（txt 及无公开稳定文件头/签名易变的媒体）
     * 返回 true 放行，交由后缀白名单兜底。读不到文件头时（空 header）对需核验的格式判为不匹配，
     * 采取 fail-closed。
     */
    private static boolean matchesMagic(String extension, byte[] header) {
        String ext = extension == null ? "" : extension;
        switch (ext) {
            case "jpg":
            case "jpeg":
                return startsWith(header, 0, 0xFF, 0xD8, 0xFF);
            case "png":
                return startsWith(header, 0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A);
            case "gif":
                return startsWith(header, 0, 0x47, 0x49, 0x46, 0x38);
            case "bmp":
                return startsWith(header, 0, 0x42, 0x4D);
            case "pdf":
                return startsWith(header, 0, 0x25, 0x50, 0x44, 0x46);
            case "zip":
                return startsWith(header, 0, 0x50, 0x4B);
            case "docx":
            case "xlsx":
            case "pptx":
                return startsWith(header, 0, 0x50, 0x4B, 0x03, 0x04);
            case "doc":
            case "xls":
            case "ppt":
                return startsWith(header, 0, 0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1);
            case "rar":
                return startsWith(header, 0, 0x52, 0x61, 0x72, 0x21, 0x1A, 0x07);
            case "gz":
                return startsWith(header, 0, 0x1F, 0x8B);
            case "bz2":
                return startsWith(header, 0, 0x42, 0x5A);
            case "mp4":
                return startsWith(header, 4, 0x66, 0x74, 0x79, 0x70);
            case "avi":
                return startsWith(header, 0, 0x52, 0x49, 0x46, 0x46)
                        && startsWith(header, 8, 0x41, 0x56, 0x49, 0x20);
            default:
                // 无稳定公开签名或签名易变的格式不强校验，放行交由后缀白名单
                return true;
        }
    }

    /**
     * 判断 header 从 offset 起是否匹配给定的魔数字节序列（按无符号比较，规避 byte 符号位问题）
     */
    private static boolean startsWith(byte[] header, int offset, int... magic) {
        if (header == null || header.length < offset + magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if ((header[offset + i] & 0xFF) != (magic[i] & 0xFF)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 读取文件头前若干字节用于魔数核验；读失败返回空数组（由 matchesMagic 对需核验格式判为不匹配，fail-closed）
     */
    private static byte[] readFileHeader(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            byte[] buffer = new byte[MAGIC_HEADER_LENGTH];
            int read = 0;
            int n;
            while (read < MAGIC_HEADER_LENGTH && (n = in.read(buffer, read, MAGIC_HEADER_LENGTH - read)) != -1) {
                read += n;
            }
            return read == MAGIC_HEADER_LENGTH ? buffer : Arrays.copyOf(buffer, read);
        } catch (IOException e) {
            log.warn("读取上传文件头失败，按内容不可信处理: {}", file.getOriginalFilename(), e);
            return new byte[0];
        }
    }

    /**
     * 判断MIME类型是否是允许的MIME类型
     *
     * @param extension
     * @param allowedExtension
     * @return
     */
    public static final boolean isAllowedExtension(String extension, String[] allowedExtension) {
        for (String str : allowedExtension) {
            if (str.equalsIgnoreCase(extension)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取文件名的后缀
     * 
     * @param file 表单文件
     * @return 后缀名
     */
    public static final String getExtension(MultipartFile file) {
        String extension = FilenameUtils.getExtension(file.getOriginalFilename());
        if (StringUtils.isEmpty(extension)) {
            extension = MimeTypeUtils.getExtension(Objects.requireNonNull(file.getContentType()));
        }
        return extension;
    }
}
