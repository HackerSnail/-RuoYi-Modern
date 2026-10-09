package com.ruoyi.common.utils;

import java.lang.management.ManagementFactory;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Date;
import org.apache.commons.lang3.time.DateFormatUtils;

/**
 * 时间工具类
 * 
 * @author ruoyi
 */
public class DateUtils extends org.apache.commons.lang3.time.DateUtils {
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
        return new SimpleDateFormat(format).format(date);
    }

    public static final Date dateTime(final String format, final String ts) {
        try {
            return new SimpleDateFormat(format).parse(ts);
        } catch (ParseException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 日期路径 即年/月/日 如2018/08/08
     */
    public static final String datePath() {
        Date now = new Date();
        return DateFormatUtils.format(now, "yyyy/MM/dd");
    }

    /**
     * 日期路径 即年/月/日 如20180808
     */
    public static final String dateTime() {
        Date now = new Date();
        return DateFormatUtils.format(now, "yyyyMMdd");
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
        try {
            return parseDate(str.toString(), parsePatterns);
        } catch (ParseException e) {
            return null;
        }
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
     * 计算时间差（endDate 相对 startTime）。
     * <p>
     * 方向性：假定 {@code endDate} 不早于 {@code startTime}（如“当前时间 - 服务器启动时间”）；
     * 若 endDate 早于 startTime，diff 为负，输出会含负值（如 "-1天-1小时"）。需恒定非负请先保证 endDate 较晚。
     *
     * @param endDate   最后时间
     * @param startTime 开始时间
     * @return 时间差（天/小时/分钟）
     */
    public static String timeDistance(Date endDate, Date startTime) {
        long nd = 1000 * 24 * 60 * 60;
        long nh = 1000 * 60 * 60;
        long nm = 1000 * 60;
        // long ns = 1000;
        // 获得两个时间的毫秒时间差异
        long diff = endDate.getTime() - startTime.getTime();
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
     * 时区依赖：使用 JVM 默认时区 {@link ZoneId#systemDefault()}；若服务器/容器时区变化，转换结果会偏移。
     * 需确定时区的敏感场景请改用 {@link #toDate(LocalDateTime, ZoneId)}。
     */
    public static Date toDate(LocalDateTime temporalAccessor) {
        return toDate(temporalAccessor, ZoneId.systemDefault());
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
     * 时区依赖：使用 JVM 默认时区 {@link ZoneId#systemDefault()}；需确定时区请改用 {@link #toDate(LocalDate, ZoneId)}。
     */
    public static Date toDate(LocalDate temporalAccessor) {
        return toDate(temporalAccessor, ZoneId.systemDefault());
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
     * 时区依赖：使用 JVM 默认时区 {@link ZoneId#systemDefault()}；需确定时区请改用 {@link #toLocalDateTime(Date, ZoneId)}。
     *
     * @return date 为 null 时返回 null
     */
    public static LocalDateTime toLocalDateTime(Date date) {
        return toLocalDateTime(date, ZoneId.systemDefault());
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
