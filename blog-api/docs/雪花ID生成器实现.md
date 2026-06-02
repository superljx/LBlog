# 给博客文章主键换上雪花 ID：基于 MyBatis 拦截器的零侵入实现

## 一、背景：自增主键带来的隐患

我的博客后端用的是 Spring Boot 2.2.7 + MyBatis，文章表 `blog` 的主键一直是数据库自增 ID。前台访问一篇文章的 URL 形如：

```
https://lblog.work/blog/12
```

这种连续自增的主键有个老问题：**可被顺序枚举**。任何人写个脚本 `for id in 1..N` 就能把全站文章（包括草稿、私密文章对应的接口）挨个探一遍，爬虫、内容抓取、数据规模刺探都变得轻而易举。同时自增 ID 还会泄露业务体量（看到 id=300 就知道大概发了 300 篇）。

解决思路是把主键换成**全局唯一、不连续、不可预测**的 ID。业界成熟方案就是 Twitter 的雪花算法（Snowflake）。

但 MyBatis 本身不像 MyBatis-Plus 那样自带 ID 生成器，于是我自己实现了一套，目标是：

- **零侵入**：业务代码（Service / Controller）一行都不用改
- **安全**：线程安全、防止生成重复主键
- **高可用**：能容错时钟回拨，多实例部署可扩展

最终方案是：**自定义注解 `@SnowflakeId` + MyBatis 拦截器自动填充**。在主键字段上打个注解，拦截器在 INSERT 执行前自动塞入雪花 ID，业务层无感知。

## 二、雪花算法原理速览

雪花算法生成一个 64 位的 `long`，结构如下：

```
0 - 0000000000 0000000000 0000000000 0000000000 0 - 00000 - 00000 - 000000000000
|   |------------------41位时间戳-----------------|  |-5位-| |-5位-| |---12位序列---|
符号位                                              数据中心   机器      毫秒内序列
```

- **1 位符号位**：始终为 0，保证 ID 为正数；
- **41 位时间戳**：毫秒级，相对一个自定义"纪元"起算，约可用 69 年；
- **5 位数据中心 ID + 5 位机器 ID**：共 10 位，最多支持 1024 个节点；
- **12 位序列号**：同一节点同一毫秒内自增，单毫秒最多 4096 个 ID。

这样设计的好处：
- ID 整体随时间**趋势递增**（对数据库 B+ 树索引友好，插入不会频繁页分裂）；
- 同一毫秒内靠序列号区分，**不连续也不可预测**；
- 不同节点靠 workerId / datacenterId 区分，**分布式下不冲突**。

## 三、整体设计

一共五个新增文件 + 三处修改，职责划分如下：

| 文件 | 作用 |
|------|------|
| `annotation/SnowflakeId.java` | 主键字段标记注解 |
| `util/SnowflakeIdGenerator.java` | 雪花算法核心（线程安全 + 时钟回拨容错） |
| `config/properties/SnowflakeProperties.java` | 读取 `snowflake.worker-id` / `datacenter-id` 配置 |
| `config/SnowflakeConfig.java` | 把生成器注册为 Bean，未配置时自动推导节点 ID |
| `interceptor/SnowflakeIdInterceptor.java` | 拦截 INSERT，给被注解的空字段填充 ID |

修改：

- `model/dto/Blog.java` — `id` 字段加 `@SnowflakeId`
- `mapper/BlogMapper.xml` — `saveBlog` 显式插入 `id` 列，移除 `useGeneratedKeys`
- `application-prod.yml` — 增加雪花配置项说明

数据流：

```
Controller.saveBlog(blog)   // blog.id == null
        │
        ▼
BlogMapper.saveBlog(blog)    // 触发 MyBatis INSERT
        │
        ▼
SnowflakeIdInterceptor       // 拦截到 INSERT，检测到 @SnowflakeId 且 id==null
        │  → idGenerator.nextId() 回填 blog.id
        ▼
真正执行 INSERT，id 列写入雪花值
        │
        ▼
Controller 拿 blog.getId() 维护 blog_tag 关联表   // id 已存在，无感知
```

## 四、代码实现

### 4.1 标记注解 `@SnowflakeId`

```java
package top.ljx.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 雪花ID主键标识。
 * 标注在实体/DTO的主键字段上，配合 SnowflakeIdInterceptor 使用：
 * 当执行 INSERT 且被标注字段的值为 null 时，自动填充一个全局唯一的雪花ID。
 * 仅支持 Long/long 与 String 类型的字段。
 */
@Documented
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface SnowflakeId {
}
```

注意 `@Retention(RUNTIME)`，因为要在运行时靠反射读取它。

### 4.2 雪花算法核心 `SnowflakeIdGenerator`

这是整个方案最关键的部分，安全性和高可用性都在这里。重点关注三个细节：**位运算拼装**、**`synchronized` 保证线程安全**、**时钟回拨容错**。

```java
package top.ljx.util;

/**
 * 雪花算法(Snowflake)ID生成器。
 * 线程安全：所有获取ID的操作通过 synchronized 串行化。
 * 高可用：对时钟回拨做了容错——小幅回拨自旋等待时钟追平，大幅回拨直接抛异常拒绝服务，
 * 避免生成重复ID造成主键冲突这类更隐蔽的数据问题。
 */
public class SnowflakeIdGenerator {
    /**
     * 起始纪元时间戳(2024-01-01 00:00:00 UTC 的毫秒数)。
     * 一经上线请勿修改，否则可能与历史已生成的ID冲突。
     */
    private static final long EPOCH = 1704067200000L;

    private static final long WORKER_ID_BITS = 5L;      // 机器ID位数
    private static final long DATACENTER_ID_BITS = 5L;  // 数据中心ID位数
    private static final long SEQUENCE_BITS = 12L;      // 序列号位数

    private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);          // 31
    private static final long MAX_DATACENTER_ID = ~(-1L << DATACENTER_ID_BITS);  // 31
    private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS);           // 4095

    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;
    private static final long DATACENTER_ID_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS + DATACENTER_ID_BITS;

    /** 可容忍的时钟回拨最大毫秒数，超过则拒绝生成ID */
    private static final long MAX_BACKWARD_MS = 5L;

    private final long workerId;
    private final long datacenterId;
    private long sequence = 0L;
    private long lastTimestamp = -1L;

    public SnowflakeIdGenerator(long workerId, long datacenterId) {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException(
                    String.format("workerId 必须在 0 ~ %d 之间，当前值: %d", MAX_WORKER_ID, workerId));
        }
        if (datacenterId < 0 || datacenterId > MAX_DATACENTER_ID) {
            throw new IllegalArgumentException(
                    String.format("datacenterId 必须在 0 ~ %d 之间，当前值: %d", MAX_DATACENTER_ID, datacenterId));
        }
        this.workerId = workerId;
        this.datacenterId = datacenterId;
    }

    public synchronized long nextId() {
        long timestamp = timeGen();

        // 处理时钟回拨
        if (timestamp < lastTimestamp) {
            long offset = lastTimestamp - timestamp;
            if (offset <= MAX_BACKWARD_MS) {
                timestamp = tilNextMillis(lastTimestamp);  // 小幅回拨：自旋等待
            } else {
                throw new IllegalStateException(            // 大幅回拨：拒绝服务
                        String.format("检测到时钟回拨，拒绝生成ID。回拨 %d 毫秒", offset));
            }
        }

        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;      // 同毫秒内递增
            if (sequence == 0) {
                timestamp = tilNextMillis(lastTimestamp);   // 序列号用尽，等下一毫秒
            }
        } else {
            sequence = 0L;                                  // 新毫秒，序列归零
        }

        lastTimestamp = timestamp;

        return ((timestamp - EPOCH) << TIMESTAMP_SHIFT)
                | (datacenterId << DATACENTER_ID_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;
    }

    private long tilNextMillis(long lastTimestamp) {
        long timestamp = timeGen();
        while (timestamp <= lastTimestamp) {
            timestamp = timeGen();
        }
        return timestamp;
    }

    private long timeGen() {
        return System.currentTimeMillis();
    }
}
```

几个值得展开的点：

**位掩码 `~(-1L << n)`**：`-1L` 的二进制是全 1，左移 n 位后低 n 位变成 0，再取反，就得到「低 n 位全 1」的掩码。比如 `~(-1L << 12)` = 4095，正好是序列号的最大值，用它做 `& SEQUENCE_MASK` 可以让序列号在 0~4095 之间循环。

**为什么必须 `synchronized`**：`sequence` 和 `lastTimestamp` 是共享可变状态，多线程同时进入会读到脏值、生成重复 ID。加锁后所有调用串行化。雪花算法本身极快（纯内存位运算），锁竞争开销可以忽略。

**时钟回拨为什么要专门处理**：服务器时间可能因为 NTP 校时、运维手动调时而往回跳。一旦 `当前时间 < lastTimestamp`，如果不管它，就可能生成和过去重复的 ID，造成主键冲突——这种 bug 极其隐蔽。这里的策略是：
- 回拨 ≤ 5ms：自旋等到时钟追上来，对调用方表现为轻微延迟；
- 回拨 > 5ms：直接抛异常拒绝生成，**宁可这次插入失败，也绝不生成重复主键**。

### 4.3 配置属性与 Bean 注册

`workerId` / `datacenterId` 用来区分不同节点。单机部署其实可以写死，但为了集群扩展，我把它做成可配置，并在不配置时根据本机 MAC 地址自动推导一个稳定值。

配置属性类：

```java
package top.ljx.config.properties;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 雪花ID生成器配置。
 * 多实例部署时，必须保证每个实例的 (workerId, datacenterId) 组合唯一，否则可能生成重复ID。
 * 未显式配置时，将根据本机网络信息自动推导。
 */
@NoArgsConstructor
@Getter
@Setter
@ToString
@Configuration
@ConfigurationProperties(prefix = "snowflake")
public class SnowflakeProperties {
    /** 机器ID，取值 0~31。为 null 时自动推导。 */
    private Long workerId;
    /** 数据中心ID，取值 0~31。为 null 时自动推导。 */
    private Long datacenterId;
}
```

Bean 注册 + 节点 ID 推导：

```java
package top.ljx.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import top.ljx.config.properties.SnowflakeProperties;
import top.ljx.util.SnowflakeIdGenerator;

import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;

/**
 * 将 SnowflakeIdGenerator 注册为单例 Bean。
 * workerId/datacenterId 优先取配置，未配置时基于本机 MAC 地址推导，保证同一台机器多次重启取值稳定。
 */
@Configuration
public class SnowflakeConfig {
    private static final Logger logger = LoggerFactory.getLogger(SnowflakeConfig.class);
    private static final long MAX_ID = 32L;

    @Bean
    public SnowflakeIdGenerator snowflakeIdGenerator(SnowflakeProperties properties) {
        long workerId = properties.getWorkerId() != null ? properties.getWorkerId() : deriveId(0);
        long datacenterId = properties.getDatacenterId() != null ? properties.getDatacenterId() : deriveId(1);
        logger.info("初始化雪花ID生成器: workerId={}, datacenterId={}", workerId, datacenterId);
        return new SnowflakeIdGenerator(workerId, datacenterId);
    }

    /**
     * 基于本机 MAC 地址推导一个 0~31 的稳定ID。
     * 取不到 MAC 时回退为伪随机值，保证不会抛异常导致启动失败。
     * @param salt 区分 workerId 与 datacenterId，避免两者推导出相同值
     */
    private long deriveId(int salt) {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                byte[] mac = network.getHardwareAddress();
                if (mac != null && mac.length >= 2) {
                    long id = ((0x000000FF & (long) mac[mac.length - 1])
                            | (0x0000FF00 & (((long) mac[mac.length - 2]) << 8))) >> 6;
                    return (id + salt) % MAX_ID;
                }
            }
        } catch (SocketException e) {
            logger.warn("读取本机MAC地址失败，将使用回退值推导雪花ID节点标识", e);
        }
        return Math.abs((System.nanoTime() + salt) % MAX_ID);
    }
}
```

这里把"自动推导"和"回退"都做了兜底：取不到 MAC 也不会让应用启动失败，而是退化成基于 `nanoTime` 的伪随机值（单机无所谓重复）。

### 4.4 核心：MyBatis 拦截器自动填充

这是实现"零侵入"的关键。MyBatis 的 `Interceptor` 插件机制允许我们在 SQL 执行的各个环节插一脚。这里拦截 `Executor.update`（INSERT/UPDATE/DELETE 都走它），只在 INSERT 时给被 `@SnowflakeId` 标注、且值为 `null` 的字段填充雪花 ID。

```java
package top.ljx.interceptor;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import top.ljx.annotation.SnowflakeId;
import top.ljx.util.SnowflakeIdGenerator;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 雪花ID自动填充拦截器。
 * 拦截 INSERT，在执行前扫描参数对象中被 @SnowflakeId 标注、且当前值为 null 的主键字段，自动填充。
 * 已手动赋值的字段不会被覆盖。字段反射结果按类缓存，避免每次插入都重复扫描。
 */
@Component
@Intercepts({
        @Signature(type = Executor.class, method = "update",
                args = {MappedStatement.class, Object.class})
})
public class SnowflakeIdInterceptor implements Interceptor {
    private static final Logger logger = LoggerFactory.getLogger(SnowflakeIdInterceptor.class);

    private final SnowflakeIdGenerator idGenerator;
    /** 类 -> 被 @SnowflakeId 标注的字段列表，缓存反射结果 */
    private final Map<Class<?>, List<Field>> fieldCache = new ConcurrentHashMap<>();

    public SnowflakeIdInterceptor(SnowflakeIdGenerator idGenerator) {
        this.idGenerator = idGenerator;
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        Object[] args = invocation.getArgs();
        MappedStatement mappedStatement = (MappedStatement) args[0];
        Object parameter = args[1];
        if (mappedStatement.getSqlCommandType() == SqlCommandType.INSERT && parameter != null) {
            fillIds(parameter);
        }
        return invocation.proceed();
    }

    /** 参数可能是单个实体，也可能是 Map(@Param 时) 或集合(批量插入)，逐一处理 */
    private void fillIds(Object parameter) {
        if (parameter instanceof Map) {
            for (Object value : ((Map<?, ?>) parameter).values()) {
                fillEntity(value);
            }
        } else if (parameter instanceof Collection) {
            for (Object value : (Collection<?>) parameter) {
                fillEntity(value);
            }
        } else {
            fillEntity(parameter);
        }
    }

    private void fillEntity(Object entity) {
        if (entity == null || isSimpleType(entity.getClass())) {
            return;
        }
        List<Field> fields = fieldCache.computeIfAbsent(entity.getClass(), this::resolveAnnotatedFields);
        for (Field field : fields) {
            try {
                if (field.get(entity) != null) {
                    continue;  // 已有值，尊重调用方显式赋值，不覆盖
                }
                long id = idGenerator.nextId();
                if (field.getType() == String.class) {
                    field.set(entity, String.valueOf(id));
                } else {
                    field.set(entity, id);  // Long / long
                }
            } catch (IllegalAccessException e) {
                logger.error("填充雪花ID失败, 字段: {}.{}", entity.getClass().getName(), field.getName(), e);
            }
        }
    }

    /** 解析类中(含父类)所有被 @SnowflakeId 标注的字段，并设置为可访问 */
    private List<Field> resolveAnnotatedFields(Class<?> clazz) {
        List<Field> result = new ArrayList<>();
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                if (field.isAnnotationPresent(SnowflakeId.class)) {
                    Class<?> type = field.getType();
                    if (type != Long.class && type != long.class && type != String.class) {
                        throw new IllegalStateException(
                                String.format("@SnowflakeId 仅支持 Long/long/String 类型, 非法字段: %s.%s",
                                        clazz.getName(), field.getName()));
                    }
                    field.setAccessible(true);
                    result.add(field);
                }
            }
            current = current.getSuperclass();
        }
        return result;
    }

    /** 简单类型不可能携带 @SnowflakeId 字段，直接跳过反射 */
    private boolean isSimpleType(Class<?> clazz) {
        return clazz.isPrimitive()
                || clazz == String.class
                || Number.class.isAssignableFrom(clazz)
                || clazz == Boolean.class
                || clazz == Character.class;
    }

    @Override
    public Object plugin(Object target) {
        // 仅对 Executor 类型包装代理，其余原样返回，减少不必要的代理开销
        return target instanceof Executor ? Plugin.wrap(target, this) : target;
    }
}
```

设计上的几个考量：

- **只覆盖 `null` 字段**：`field.get(entity) != null` 就跳过。这样手动指定主键、数据迁移、或更新操作都不受影响，副作用最小。
- **反射结果缓存**：每个实体类只在第一次插入时扫描一遍字段，结果存进 `ConcurrentHashMap`，后续直接命中缓存，避免高频反射的性能损耗。
- **兼容多种参数形态**：MyBatis 传进来的参数可能是单个对象、`@Param` 包装的 Map、或批量插入的集合，都做了处理。
- **类型校验前置**：注解打在非 `Long/long/String` 字段上会在解析阶段就抛异常，把错误暴露在早期而非运行时静默失败。
- **`plugin()` 只代理 Executor**：避免给 ParameterHandler 等其它组件套无用的代理。

> 因为拦截器是 `@Component`，`mybatis-spring-boot-starter` 会自动把容器里所有 `Interceptor` 类型的 Bean 注册到 `SqlSessionFactory`，**不需要额外的 XML 或 Java 配置**去手动 `addInterceptor`。

### 4.5 接入业务：改动只有两行实质内容

**第一步**，在文章 DTO 的主键字段上加注解：

```java
// model/dto/Blog.java
public class Blog {
    @SnowflakeId
    private Long id;
    // ... 其余字段不变
}
```

**第二步**，修改 `BlogMapper.xml` 的 `saveBlog`，把 `id` 列加进 INSERT 语句，并去掉 `useGeneratedKeys`（不再依赖数据库自增回填）：

```xml
<!-- 改之前：依赖自增，回填主键 -->
<insert id="saveBlog" parameterType="top.ljx.model.dto.Blog" useGeneratedKeys="true" keyProperty="id">
    insert into blog (title, first_picture, ...)
    values (#{title}, #{firstPicture}, ...)
</insert>

<!-- 改之后：显式插入拦截器填好的 id -->
<insert id="saveBlog" parameterType="top.ljx.model.dto.Blog">
    insert into blog (id, title, first_picture, ...)
    values (#{id}, #{title}, #{firstPicture}, ...)
</insert>
```

Service 层、Controller 层**一行都没动**。原本 Controller 在保存后还要用 `blog.getId()` 去维护 `blog_tag` 关联表：

```java
blogService.saveBlog(blog);
for (Tag t : tags) {
    blogService.saveBlogTag(blog.getId(), t.getId());  // 这里依然能拿到 id
}
```

因为拦截器在 INSERT 执行**之前**就已经把雪花 ID 回填进了 `blog` 对象，所以 `blog.getId()` 照常可用，无感知替换。

### 4.6 配置文件

`application-prod.yml` 增加可选配置（默认注释掉，走自动推导）：

```yaml
# 雪花ID生成器配置(用于文章主键，避免自增ID被顺序枚举)
# 单机部署可不配置，系统会根据本机MAC地址自动推导；
# 多实例集群部署时，必须为每个实例指定唯一的 (worker-id, datacenter-id) 组合，取值均为 0~31
snowflake:
#  worker-id: 0
#  datacenter-id: 0
```

## 五、数据库层的注意点

雪花 ID 是 19 位的 `long`（最大约 9.2×10¹⁸），所以主键列**必须是 `BIGINT`**，`INT` 会直接溢出。

检查建表脚本后确认，`blog.id` 和关联表 `blog_tag.blog_id` 本来就是 `bigint`，容量足够，不需要改列类型：

```sql
CREATE TABLE `blog`  (
  `id` bigint(0) NOT NULL AUTO_INCREMENT,
  ...
);
CREATE TABLE `blog_tag`  (
  `blog_id` bigint(0) NOT NULL,
  `tag_id` bigint(0) NOT NULL
);
```

`id` 列上残留的 `AUTO_INCREMENT` 属性**不会报错**——因为现在 INSERT 显式提供了 id 值，MySQL 会用我们给的值，而不是自增值。如果想让语义更干净，可以手动去掉这个属性（可选，非必须）：

```sql
ALTER TABLE `blog` MODIFY `id` bigint NOT NULL;
```

> 这属于修改生产表结构的操作，建议先备份再执行。

## 六、测试验证

雪花算法最核心的保证是**唯一性**和**并发安全**，必须用测试压一压。写了个纯单元测试（不加载 Spring 上下文，避免依赖 DB/Redis）：

```java
package top.ljx.util;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class SnowflakeIdGeneratorTest {

    /** 非法参数应在构造时抛异常 */
    @Test
    void invalidArgsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdGenerator(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdGenerator(32, 0));
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdGenerator(0, -1));
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdGenerator(0, 32));
    }

    /** 单线程 20 万次：全部为正、互不重复、单调递增 */
    @Test
    void singleThreadUniqueAndPositive() {
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1, 1);
        int count = 200_000;
        Set<Long> ids = new HashSet<>(count * 2);
        long last = -1L;
        for (int i = 0; i < count; i++) {
            long id = generator.nextId();
            assertTrue(id > 0, "ID必须为正数: " + id);
            assertTrue(id > last, "ID必须单调递增");
            assertTrue(ids.add(id), "ID出现重复: " + id);
            last = id;
        }
        assertEquals(count, ids.size());
    }

    /** 16 线程 * 2 万次并发：不应产生重复 ID */
    @Test
    void concurrentUnique() throws InterruptedException {
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(5, 7);
        int threads = 16, perThread = 20_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<Long> all = new ConcurrentLinkedQueue<>();

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                ready.countDown();
                try { start.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                for (int i = 0; i < perThread; i++) all.add(generator.nextId());
            });
        }
        ready.await();
        start.countDown();   // 所有线程就绪后同时起跑，最大化竞争
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));

        List<Long> list = new ArrayList<>(all);
        Set<Long> distinct = new HashSet<>(list);
        assertEquals(threads * perThread, list.size());
        assertEquals(list.size(), distinct.size(), "并发生成出现重复ID");
        assertTrue(Collections.min(list) > 0);
    }
}
```

用 `CountDownLatch` 让 16 个线程先全部就绪、再同时起跑，最大化并发竞争。运行结果：

```
[INFO] Running top.ljx.util.SnowflakeIdGeneratorTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.265 s
[INFO] BUILD SUCCESS
```

单线程 20 万 + 并发 32 万次生成，全部唯一、为正、无冲突。

## 七、踩坑记录：JDK 17 + 老版 Lombok 编译报错

我本机只装了 JDK 17，而项目是 Java 8 + Spring Boot 2.2.7（自带 Lombok 1.18.12）。直接 `mvn compile` 会炸：

```
java.lang.IllegalAccessError: class lombok.javac.apt.LombokProcessor
  cannot access class com.sun.tools.javac.processing.JavacProcessingEnvironment
  because module jdk.compiler does not export com.sun.tools.javac.processing
```

原因是 JDK 9+ 引入模块系统后，`com.sun.tools.javac.*` 这些内部包默认不对外开放，而老版本 Lombok 靠反射硬闯这些包。两个正经解法：

1. **用 JDK 8 编译**（最省事，和项目目标版本一致）；
2. **升级 Lombok** 到 1.18.20+（兼容新 JDK）。

如果只是临时验证，也可以给编译 JVM 加 `--add-exports` / `--add-opens` 把相关包开放出来。这个坑跟雪花 ID 本身无关，但值得记一笔。

## 八、小结

| 维度 | 做法 |
|------|------|
| **防枚举** | 主键不再连续，爬虫无法 `id+1` 顺序遍历 |
| **零侵入** | 注解 + 拦截器，Service/Controller 零改动 |
| **线程安全** | `nextId()` 全程 `synchronized`，并发 32 万次无重复 |
| **时钟回拨容错** | 小幅自旋等待，大幅拒绝服务，绝不生成重复主键 |
| **性能** | 纯内存位运算 + 反射结果缓存 |
| **可扩展** | workerId/datacenterId 可配置，支持多实例集群 |

这套方案的精髓在于**用 MyBatis 拦截器把"生成 ID"这件横切关注点从业务代码里彻底抽离**。以后想给动态（moment）、评论或任何其它表换雪花主键，只要在对应字段加一个 `@SnowflakeId`、把 INSERT 语句带上主键列即可，一处实现处处复用。

