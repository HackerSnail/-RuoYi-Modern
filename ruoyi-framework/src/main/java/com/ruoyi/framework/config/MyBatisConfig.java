package com.ruoyi.framework.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import javax.sql.DataSource;
import org.apache.ibatis.io.VFS;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.boot.autoconfigure.SpringBootVFS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.util.ClassUtils;
import com.ruoyi.common.utils.StringUtils;

/**
 * Mybatis支持*匹配扫描包
 * 
 * @author HackerSnail <2306002088@qq.com>
 */
@Configuration
public class MyBatisConfig {
    private static final Logger log = LoggerFactory.getLogger(MyBatisConfig.class);

    private final Environment env;

    static final String DEFAULT_RESOURCE_PATTERN = "**/*.class";

    static {
        // 全局注册 Spring Boot 的 VFS 实现（MyBatis 借此扫描 jar 内资源）；addImplClass 内部去重，放静态块仅注册一次
        VFS.addImplClass(SpringBootVFS.class);
    }

    public MyBatisConfig(Environment env) {
        this.env = env;
    }

    /**
     * 将支持 * 通配的 typeAliasesPackage 展开为真实包名集合：
     * 扫描匹配路径下的 .class 资源，取其所在包去重后回写，供 MyBatis 注册类型别名。
     */
    private static String setTypeAliasesPackage(String typeAliasesPackage) {
        ResourcePatternResolver resolver = (ResourcePatternResolver) new PathMatchingResourcePatternResolver();
        MetadataReaderFactory metadataReaderFactory = new CachingMetadataReaderFactory(resolver);
        List<String> allResult = new ArrayList<>();
        try {
            for (String aliasesPackage : typeAliasesPackage.split(",")) {
                List<String> result = new ArrayList<>();
                aliasesPackage = ResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX
                        + ClassUtils.convertClassNameToResourcePath(aliasesPackage.trim()) + "/"
                        + DEFAULT_RESOURCE_PATTERN;
                Resource[] resources = resolver.getResources(aliasesPackage);
                if (resources != null && resources.length > 0) {
                    MetadataReader metadataReader = null;
                    for (Resource resource : resources) {
                        if (resource.isReadable()) {
                            metadataReader = metadataReaderFactory.getMetadataReader(resource);
                            try {
                                result.add(Class.forName(metadataReader.getClassMetadata().getClassName()).getPackage()
                                        .getName());
                            } catch (ClassNotFoundException e) {
                                log.error("解析类型别名的包名失败", e);
                            }
                        }
                    }
                }
                if (result.size() > 0) {
                    HashSet<String> hashResult = new HashSet<>(result);
                    allResult.addAll(hashResult);
                }
            }
            if (allResult.size() > 0) {
                typeAliasesPackage = String.join(",", (String[]) allResult.toArray(new String[0]));
            } else {
                throw new RuntimeException(
                        "mybatis typeAliasesPackage 路径扫描错误,参数typeAliasesPackage:" + typeAliasesPackage + "未找到任何包");
            }
        } catch (IOException e) {
            // 扫描通配包失败绝不能静默继续：否则未展开的 com.ruoyi.**.domain 会被原样交给 MyBatis，别名注册整体失效且难排查
            throw new UncheckedIOException("扫描 typeAliasesPackage 路径失败: " + typeAliasesPackage, e);
        }
        return typeAliasesPackage;
    }

    /**
     * 解析 mapper XML 位置：按通配模式聚合同类资源，单个路径解析失败时忽略不阻断整体启动
     */
    private Resource[] resolveMapperLocations(String[] mapperLocations) {
        ResourcePatternResolver resourceResolver = new PathMatchingResourcePatternResolver();
        List<Resource> resources = new ArrayList<>();
        if (mapperLocations != null) {
            for (String mapperLocation : mapperLocations) {
                try {
                    Resource[] mappers = resourceResolver.getResources(mapperLocation);
                    resources.addAll(Arrays.asList(mappers));
                } catch (IOException e) {
                    // 单个 mapper 路径解析失败不阻断整体（多模块下某些路径可能不存在），但要留痕便于排查漏加载
                    log.warn("mapper 路径解析失败，已忽略: {}", mapperLocation, e);
                }
            }
        }
        return resources.toArray(new Resource[0]);
    }

    /**
     * 构建 SqlSessionFactory：读取 typeAliasesPackage/mapperLocations/configLocation
     * 配置并手动装配（多数据源下需显式注入 DataSource）
     */
    @Bean
    public SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
        String typeAliasesPackage = env.getProperty("mybatis.typeAliasesPackage");
        String mapperLocations = env.getProperty("mybatis.mapperLocations");
        String configLocation = env.getProperty("mybatis.configLocation");
        // typeAliasesPackage/mapperLocations 为必需项：缺失会导致别名/映射整体加载不到，fail-fast 抛清晰错误，避免 NPE 或静默无 mapper
        if (StringUtils.isEmpty(typeAliasesPackage)) {
            throw new IllegalStateException("mybatis.typeAliasesPackage 未配置");
        }
        if (StringUtils.isEmpty(mapperLocations)) {
            throw new IllegalStateException("mybatis.mapperLocations 未配置");
        }
        typeAliasesPackage = setTypeAliasesPackage(typeAliasesPackage);

        final SqlSessionFactoryBean sessionFactory = new SqlSessionFactoryBean();
        sessionFactory.setDataSource(dataSource);
        sessionFactory.setTypeAliasesPackage(typeAliasesPackage);
        sessionFactory.setMapperLocations(resolveMapperLocations(StringUtils.split(mapperLocations, ",")));
        // configLocation 可选：缺失则跳过（不崩溃），但告警提示全局 mybatis-config.xml 的设置不会生效
        if (StringUtils.isNotEmpty(configLocation)) {
            sessionFactory.setConfigLocation(new DefaultResourceLoader().getResource(configLocation));
        } else {
            log.warn("未配置 mybatis.configLocation，跳过加载全局 mybatis-config.xml，其中的 cacheEnabled/useGeneratedKeys/logImpl 等设置将不生效");
        }
        return sessionFactory.getObject();
    }
}
