package com.ruoyi.common.utils;

import java.lang.management.ManagementFactory;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Arrays;
import java.util.Date;
import java.util.TimeZone;
import org.apache.commons.lang3.time.DateFormatUtils;

/**
 * 时间工具类
 * 
 * @author ruoyi
 */
public class DateUtils {
    /** 默认时区：固定东八区，避免随容器/CI 的 JVM 默认时区漂移导致 Date↔Local 转换结果偏移 */
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");

    /** {@code DEFAULT_ZONE} 对应的 TimeZone 形式，供 SimpleDateFormat / DateFormatUtils 显式设时区复用 */
    private static final TimeZone DEFAULT_TZ = TimeZone.getTimeZone(DEFAULT_ZONE);

    public static String YYYY = "yyyy";

    public static String YYYY_MM = "yyyy-MM";

    public static String YYYY_MM_DD = "yyyy-MM-dd";

    public static String YYYYMMDDHHMMSS = "yyyyMMddHHmmss";

    public static String YYYY_MM_DD_HH_MM_SS = "yyyy-MM-dd HH:mm:ss";

    private static String[] parsePatterns = {
            "yyyy-MM-dd", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM",
            "yyyy/MM/dd", "yyyy/MM/dd HH:mm:ss", "yyyy/MM/dd HH:mm", "yyyy/MM",
            "yyyy.MM.dd", "yyyy.MM.dd HH:mm:ss", "yyyy.MM.dd HH:mm", "yyyy.MM" };

    /**
     * parsePatterns 的预编译形式（DateTimeFormatter 不可变且线程安全，避免每次解析都重建）。
     * <p>
     * 采用 {@link ResolverStyle#STRICT}：日历上不存在的日期（如 2 月 30 日、 13 月）一律解析失败而非静默进位/钳位，
     * 最终由 parseDate 归为 null。注意 STRICT 下 yyyy 是 year-of-era、缺 era 无法解析为 proleptic year，
     * 故模式里的 yyyy 统一换为 uuuu（proleptic-year）；对正常年份两者数值等价。
     */
    private static final DateTimeFormatter[] FORMATTERS = Arrays.stream(parsePatterns)
            .map(p -> p.replace("yyyy", "uuuu"))
            .map(p -> DateTimeFormatter.ofPattern(p).withResolverStyle(ResolverStyle.STRICT))
            .toArray(DateTimeFormatter[]::new);

    /**
     * 获取当前Date型日期
     * 
     * @return Date() 当前日期
     */
    public static Date getNowDate() {
        return new Date();
    }

    /**
     * 获取当前日期, 默认格式为yyyy-MM-dd
     * 
     * @return String
     */
    public static String getDate() {
        return dateTimeNow(YYYY_MM_DD);
    }

    public static final String getTime() {
        return dateTimeNow(YYYY_MM_DD_HH_MM_SS);
    }

    public static final String dateTimeNow() {
        return dateTimeNow(YYYYMMDDHHMMSS);
    }

    public static final String dateTimeNow(final String format) {
        return parseDateToStr(format, new Date());
    }

    public static final String dateTime(final Date date) {
        return parseDateToStr(YYYY_MM_DD, date);
    }

    public static final String parseDateToStr(final String format, final Date date) {
        SimpleDateFormat sdf = new SimpleDateFormat(format);
        sdf.setTimeZone(DEFAULT_TZ);
        return sdf.format(date);
    }

    public static final Date dateTime(final String format, final String ts) {
        SimpleDateFormat sdf = new SimpleDateFormat(format);
        sdf.setTimeZone(DEFAULT_TZ);
        try {
            return sdf.parse(ts);
        } catch (ParseException e) {
            throw new IllegalArgumentException("日期解析失败: " + ts, e);
        }
    }

    /**
     * 日期路径 即年/月/日 如2018/08/08
     */
    public static final String datePath() {
        Date now = new Date();
        return DateFormatUtils.format(now, "yyyy/MM/dd", DEFAULT_TZ);
    }

    /**
     * 日期路径 即年/月/日 如20180808
     */
    public static final String dateTime() {
        Date now = new Date();
        return DateFormatUtils.format(now, "yyyyMMdd", DEFAULT_TZ);
    }

    /**
     * 日期型字符串转化为日期（宽松解析，多格式尝试）。
     * <p>
     * 契约：入参为 null 或所有 {@code parsePatterns} 均不匹配时<b>返回 null，不抛异常</b>。
     * 这是刻意保留的宽松语义——{@code BaseController} 的 {@code @InitBinder} 表单 Date 绑定、
     * {@code ExcelUtil} 导入的日期单元格、{@code ReflectUtils} 的 Date 参数转换三处都依赖
     * “留空/不合法即为 null”，<b>勿改成抛异常</b>，否则会中断表单绑定/整表导入。调用方须自行判空。
     */
    public static Date parseDate(Object str) {
        if (str == null) {
            return null;
        }
        String s = str.toString().trim();
        if (s.isEmpty()) {
            return null;
        }
        // 彻底拆继承：用 JDK DateTimeFormatter 逐格式尝试，不再委托 commons。
        // 每个模式依次试：带时分秒(LocalDateTime) -> 纯日期(LocalDate) -> 年月(YearMonth)。
        // YearMonth 分支专供 parsePatterns 里的 yyyy-MM / yyyy/MM / yyyy.MM 三个不含"日"的模式，
        // 否则纯年月串在 LocalDateTime/LocalDate 两条 try 下都会失败而漏解析（原 commons 能解为当月 1 号零点）。
        // 全部不匹配则返回 null，保持"宽松解析器失败返 null"契约不变（见上方 javadoc）。
        for (DateTimeFormatter fmt : FORMATTERS) {
            try {
                return Date.from(LocalDateTime.parse(s, fmt).atZone(DEFAULT_ZONE).toInstant());
            } catch (DateTimeParseException ignored) {
            }
            try {
                return Date.from(LocalDate.parse(s, fmt).atStartOfDay(DEFAULT_ZONE).toInstant());
            } catch (DateTimeParseException ignored) {
            }
            try {
                return Date.from(YearMonth.parse(s, fmt).atDay(1).atStartOfDay(DEFAULT_ZONE).toInstant());
            } catch (DateTimeParseException ignored) {
            }
        }
        return null;
    }

    /**
     * 获取服务器启动时间
     */
    public static Date getServerStartDate() {
        long time = ManagementFactory.getRuntimeMXBean().getStartTime();
        return new Date(time);
    }

    /**
     * 计算相差天数
     */
    public static int differentDaysByMillisecond(Date date1, Date date2) {
        return Math.abs((int) ((date2.getTime() - date1.getTime()) / (1000 * 3600 * 24)));
    }

    /**
     * 计算时间差（endDate 与 startTime 之间的间隔）。
     * <p>
     * 对毫秒差取绝对值，输出恒为非负（天/小时/分钟），不依赖两入参先后顺序；调用方无需保证 endDate 较晚。
     *
     * @param endDate   时间一端
     * @param startTime 时间另一端
     * @return 时间差（天/小时/分钟，非负）
     */
    public static String timeDistance(Date endDate, Date startTime) {
        long nd = 1000 * 24 * 60 * 60;
        long nh = 1000 * 60 * 60;
        long nm = 1000 * 60;
        // long ns = 1000;
        // 获得两个时间的毫秒时间差异（取绝对值，输出恒非负）
        long diff = Math.abs(endDate.getTime() - startTime.getTime());
        // 计算差多少天
        long day = diff / nd;
        // 计算差多少小时
        long hour = diff % nd / nh;
        // 计算差多少分钟
        long min = diff % nd % nh / nm;
        // 计算差多少秒//输出结果
        // long sec = diff % nd % nh % nm / ns;
        return day + "天" + hour + "小时" + min + "分钟";
    }

    /**
     * LocalDateTime ==> Date。
     * <p>
     * 时区：使用固定默认时区东八区 {@code Asia/Shanghai}（常量 {@code DEFAULT_ZONE}），不再随 JVM/容器默认时区漂移；
     * 需其他时区请改用 {@link #toDate(LocalDateTime, ZoneId)} 显式传入。
     */
    public static Date toDate(LocalDateTime temporalAccessor) {
        return toDate(temporalAccessor, DEFAULT_ZONE);
    }

    /**
     * LocalDateTime ==> Date（显式时区）。
     */
    public static Date toDate(LocalDateTime temporalAccessor, ZoneId zone) {
        ZonedDateTime zdt = temporalAccessor.atZone(zone);
        return Date.from(zdt.toInstant());
    }

    /**
     * LocalDate ==> Date（当天 00:00:00）。
     * <p>
     * 时区：使用固定默认时区东八区 {@code Asia/Shanghai}（常量 {@code DEFAULT_ZONE}）；需其他时区请改用 {@link #toDate(LocalDate, ZoneId)}。
     */
    public static Date toDate(LocalDate temporalAccessor) {
        return toDate(temporalAccessor, DEFAULT_ZONE);
    }

    /**
     * LocalDate ==> Date（当天 00:00:00，显式时区）。
     */
    public static Date toDate(LocalDate temporalAccessor, ZoneId zone) {
        LocalDateTime localDateTime = LocalDateTime.of(temporalAccessor, LocalTime.of(0, 0, 0));
        ZonedDateTime zdt = localDateTime.atZone(zone);
        return Date.from(zdt.toInstant());
    }

    /**
     * Date ==> LocalDateTime。
     * <p>
     * 时区：使用固定默认时区东八区 {@code Asia/Shanghai}（常量 {@code DEFAULT_ZONE}）；需其他时区请改用 {@link #toLocalDateTime(Date, ZoneId)}。
     *
     * @return date 为 null 时返回 null
     */
    public static LocalDateTime toLocalDateTime(Date date) {
        return toLocalDateTime(date, DEFAULT_ZONE);
    }

    /**
     * Date ==> LocalDateTime（显式时区）。
     *
     * @return date 为 null 时返回 null
     */
    public static LocalDateTime toLocalDateTime(Date date, ZoneId zone) {
        return date == null ? null : LocalDateTime.ofInstant(date.toInstant(), zone);
    }
}
