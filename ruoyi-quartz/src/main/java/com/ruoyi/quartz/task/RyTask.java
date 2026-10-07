package com.ruoyi.quartz.task;

import org.springframework.stereotype.Component;
import com.ruoyi.common.utils.StringUtils;

/**
 * 定时任务调度测试
 * <p>
 * 注册为 bean “ryTask”，提供三种入参签名的样例方法，供调度配置里的 invokeTarget
 * （如 ryTask.ryMultipleParams('a',true,100L,2.0D,3)）演示参数类型解析与反射调用。
 * 
 * @author ruoyi
 */
@Component("ryTask")
public class RyTask {
    public void ryMultipleParams(String s, Boolean b, Long l, Double d, Integer i) {
        System.out.println(StringUtils.format("执行多参方法： 字符串类型{}，布尔类型{}，长整型{}，浮点型{}，整形{}", s, b, l, d, i));
    }

    public void ryParams(String params) {
        System.out.println("执行有参方法：" + params);
    }

    public void ryNoParams() {
        System.out.println("执行无参方法");
    }
}
