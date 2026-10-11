package com.ruoyi.quartz.util;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedList;
import java.util.List;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.common.utils.spring.SpringUtils;
import com.ruoyi.quartz.domain.SysJob;

/**
 * 任务执行工具
 *
 * @author ruoyi
 */
public class JobInvokeUtil {
    /**
     * 解析 invokeTarget 并反射执行目标方法。
     * invokeTarget 支持两种写法：beanName.method(参数) 或 全类名.method(参数)。
     * <p>
     * 安全约束：bean 名写法只从 Spring 容器取 bean；全类名写法在执行点再做一次白名单前缀校验
     * （与写库期 ScheduleUtils.validateJob→whiteList 同源，构成纵深防御），不合即拒，避免调度表被
     * 直接篡改或未来新调用方绕过 save 校验时反射实例化任意类构成 RCE。
     *
     * @param sysJob 系统任务
     */
    public static void invokeMethod(SysJob sysJob) throws Exception {
        String invokeTarget = sysJob.getInvokeTarget();
        String beanName = getBeanName(invokeTarget);
        String methodName = getMethodName(invokeTarget);
        List<Object[]> methodParams = getMethodParams(invokeTarget);

        if (!isValidClassName(beanName)) {
            // 形式一：bean 名取自 Spring 容器（依赖 BeanNameAutoProxyCreator 等已注册的 bean）
            Object bean = SpringUtils.getBean(beanName);
            invokeMethod(bean, methodName, methodParams);
        } else {
            // 形式二：全类名反射实例化。执行点纵深防御—beanName 必须落在白名单包内（边界感知，
            // 与 ScheduleUtils.whiteList 共用 isInTaskPackage 唯一判据），否则拒绝；且校验须在
            // Class.forName 之前，避免实例化任意类。仅允许 public 无参构造。
            if (!ScheduleUtils.isInTaskPackage(beanName)) {
                throw new IllegalArgumentException("不允许通过全类名调用，目标不在白名单包内：" + beanName);
            }
            Object bean = Class.forName(beanName).getConstructor().newInstance();
            invokeMethod(bean, methodName, methodParams);
        }
    }

    /**
     * 调用任务方法
     *
     * @param bean 目标对象
     * @param methodName 方法名称
     * @param methodParams 方法参数
     */
    private static void invokeMethod(Object bean, String methodName, List<Object[]> methodParams)
            throws NoSuchMethodException, SecurityException, IllegalAccessException, IllegalArgumentException,
            InvocationTargetException {
        if (StringUtils.isNotNull(methodParams) && methodParams.size() > 0) {
            // 带参重载：以参数类型数组定位 method，再将参数值数组传入反射调用
            Method method = bean.getClass().getMethod(methodName, getMethodParamsType(methodParams));
            method.invoke(bean, getMethodParamsValue(methodParams));
        } else {
            // 无参方法：直接按方法名查找
            Method method = bean.getClass().getMethod(methodName);
            method.invoke(bean);
        }
    }

    /**
     * 校验给定名称是否为全类名（而非 Spring bean 名）
     * 
     * @param invokeTarget 名称
     * @return true是 false否（包含两个以上“.”则视为全类名而非 bean 名）
     */
    public static boolean isValidClassName(String invokeTarget) {
        return StringUtils.countMatches(invokeTarget, ".") > 1;
    }

    /**
     * 获取bean名称
     * 
     * @param invokeTarget 目标字符串
     * @return bean名称
     */
    public static String getBeanName(String invokeTarget) {
        String beanName = StringUtils.substringBefore(invokeTarget, "(");
        return StringUtils.substringBeforeLast(beanName, ".");
    }

    /**
     * 获取bean方法
     * 
     * @param invokeTarget 目标字符串
     * @return method方法
     */
    public static String getMethodName(String invokeTarget) {
        String methodName = StringUtils.substringBefore(invokeTarget, "(");
        return StringUtils.substringAfterLast(methodName, ".");
    }

    /**
     * 获取method方法参数相关列表
     * 
     * @param invokeTarget 目标字符串
     * @return method方法相关参数列表
     */
    public static List<Object[]> getMethodParams(String invokeTarget) {
        String methodStr = StringUtils.substringBetweenLast(invokeTarget, "(", ")");
        if (StringUtils.isEmpty(methodStr)) {
            return null;
        }
        // 按逗号分割参数，但正向前瞻排除引号内部的逗号（避免把 'a,b' 误拆为两个参数）
        String[] methodParams = methodStr.split(",(?=([^\"']*[\"'][^\"']*[\"'])*[^\"']*$)");
        List<Object[]> params = new LinkedList<>();
        for (int i = 0; i < methodParams.length; i++) {
            String str = StringUtils.trimToEmpty(methodParams[i]);
            // String字符串类型，以'或"开头（去掉首尾引号后作为字面量值）
            if (StringUtils.startsWithAny(str, "'", "\"")) {
                params.add(new Object[] { StringUtils.substring(str, 1, str.length() - 1), String.class });
            }
            // boolean布尔类型，等于true或者false
            else if ("true".equalsIgnoreCase(str) || "false".equalsIgnoreCase(str)) {
                params.add(new Object[] { Boolean.valueOf(str), Boolean.class });
            }
            // long长整形，以L结尾（去掉尾部 L 后按 Long 解析，以匹配目标方法的 long 形参）
            else if (StringUtils.endsWith(str, "L")) {
                params.add(new Object[] { Long.valueOf(StringUtils.substring(str, 0, str.length() - 1)), Long.class });
            }
            // double浮点类型，以D结尾
            else if (StringUtils.endsWith(str, "D")) {
                params.add(new Object[] { Double.valueOf(StringUtils.substring(str, 0, str.length() - 1)), Double.class });
            }
            // 其他类型归类为整形
            else {
                try {
                    params.add(new Object[] { Integer.valueOf(str), Integer.class });
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("无法将参数解析为整型：" + str, e);
                }
            }
        }
        return params;
    }

    /**
     * 获取参数类型
     * 
     * @param methodParams 参数相关列表
     * @return 参数类型列表
     */
    public static Class<?>[] getMethodParamsType(List<Object[]> methodParams) {
        Class<?>[] types = new Class<?>[methodParams.size()];
        int index = 0;
        for (Object[] os : methodParams) {
            types[index] = (Class<?>) os[1];
            index++;
        }
        return types;
    }

    /**
     * 获取参数值
     * 
     * @param methodParams 参数相关列表
     * @return 参数值列表
     */
    public static Object[] getMethodParamsValue(List<Object[]> methodParams) {
        Object[] args = new Object[methodParams.size()];
        int index = 0;
        for (Object[] os : methodParams) {
            args[index] = (Object) os[0];
            index++;
        }
        return args;
    }
}
