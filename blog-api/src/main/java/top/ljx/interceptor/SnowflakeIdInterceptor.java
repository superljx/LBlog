package top.ljx.interceptor;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import top.ljx.annotation.SnowflakeId;
import top.ljx.util.SnowflakeIdGenerator;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 雪花ID自动填充拦截器。
 * <p>
 * 拦截 MyBatis 的 INSERT 操作，在执行前扫描参数对象中被 {@link SnowflakeId} 标注、
 * 且当前值为 {@code null} 的主键字段，自动填充雪花ID。已手动赋值的字段不会被覆盖，
 * 因此对更新操作或显式指定主键的场景无副作用。
 * <p>
 * 字段反射结果按类缓存，避免每次插入都重复扫描，降低性能开销。
 *
 * @author: ljx
 * @date: 2026-06-02
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

		// 仅处理 INSERT
		if (mappedStatement.getSqlCommandType() == SqlCommandType.INSERT && parameter != null) {
			fillIds(parameter);
		}
		return invocation.proceed();
	}

	/**
	 * 填充参数对象中需要雪花ID的字段。参数可能是单个实体，也可能是包含多个实体的
	 * Map(使用 @Param 时) 或集合(批量插入)，逐一处理。
	 */
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
					// 已有值，尊重调用方显式赋值，不覆盖
					continue;
				}
				long id = idGenerator.nextId();
				if (field.getType() == String.class) {
					field.set(entity, String.valueOf(id));
				} else {
					// Long / long
					field.set(entity, id);
				}
			} catch (IllegalAccessException e) {
				logger.error("填充雪花ID失败, 字段: {}.{}", entity.getClass().getName(), field.getName(), e);
			}
		}
	}

	/**
	 * 解析类中（含父类）所有被 @SnowflakeId 标注的字段，并设置为可访问。
	 */
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

	/**
	 * 简单类型（如 String、包装类）不可能携带 @SnowflakeId 字段，直接跳过反射。
	 */
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
