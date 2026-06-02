package top.ljx.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 雪花ID主键标识。
 * <p>
 * 标注在实体/DTO的主键字段上，配合 {@code SnowflakeIdInterceptor} 使用：
 * 当执行 INSERT 且被标注字段的值为 {@code null} 时，自动填充一个全局唯一的雪花ID，
 * 用以替代数据库自增主键，避免主键可被顺序枚举（爬虫遍历）。
 * <p>
 * 仅支持 {@link Long}/{@code long} 与 {@link String} 类型的字段。
 *
 * @author: ljx
 * @date: 2026-06-02
 */
@Documented
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface SnowflakeId {
}
