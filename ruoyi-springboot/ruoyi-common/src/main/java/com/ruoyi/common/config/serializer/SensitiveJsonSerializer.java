package com.ruoyi.common.config.serializer;

import java.util.Objects;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.BeanProperty;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.ser.std.StdSerializer;
import com.ruoyi.common.annotation.Sensitive;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.enums.DesensitizedType;
import com.ruoyi.common.utils.SecurityUtils;

/**
 * 数据脱敏序列化过滤
 *
 * @author HackerSnail <2306002088@qq.com>
 */
public class SensitiveJsonSerializer extends StdSerializer<String> {
    private final DesensitizedType desensitizedType;

    public SensitiveJsonSerializer() {
        super(String.class);
        this.desensitizedType = null;
    }

    public SensitiveJsonSerializer(DesensitizedType desensitizedType) {
        super(String.class);
        this.desensitizedType = desensitizedType;
    }

    @Override
    public void serialize(String value, JsonGenerator gen, SerializationContext ctxt) throws JacksonException {
        if (desensitizedType != null && desensitization()) {
            gen.writeString(desensitizedType.desensitizer().apply(value));
        } else {
            gen.writeString(value);
        }
    }

    /**
     * 序列化前绑定上下文：读取字段上的 @Sensitive 注解，若命中且为 String 类型，
     * 则返回携带对应脱敏类型的序列化器，否则回退默认序列化器
     */
    @Override
    public ValueSerializer<?> createContextual(SerializationContext ctxt, BeanProperty property)
            throws DatabindException {
        Sensitive annotation = property.getAnnotation(Sensitive.class);
        if (Objects.nonNull(annotation) && Objects.equals(String.class, property.getType().getRawClass())) {
            return new SensitiveJsonSerializer(annotation.desensitizedType());
        }
        return ctxt.findValueSerializer(property.getType());
    }

    /**
     * 是否需要脱敏处理
     */
    private boolean desensitization() {
        try {
            LoginUser securityUser = SecurityUtils.getLoginUser();
            // 管理员不脱敏
            return !securityUser.getUser().isAdmin();
        } catch (Exception e) {
            return true;
        }
    }
}
