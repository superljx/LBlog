package top.ljx.config.properties;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 雪花ID生成器配置。
 * <p>
 * 在 application 配置文件中通过 {@code snowflake.worker-id} / {@code snowflake.datacenter-id} 指定。
 * 多实例部署时，必须保证每个实例的 (workerId, datacenterId) 组合唯一，否则可能生成重复ID。
 * 未显式配置时，将根据本机网络信息自动推导一个相对稳定的值（单机部署足够，集群部署建议显式指定）。
 *
 * @author: ljx
 * @date: 2026-06-02
 */
@NoArgsConstructor
@Getter
@Setter
@ToString
@Configuration
@ConfigurationProperties(prefix = "snowflake")
public class SnowflakeProperties {
	/**
	 * 机器ID，取值 0~31。为 null 时自动推导。
	 */
	private Long workerId;
	/**
	 * 数据中心ID，取值 0~31。为 null 时自动推导。
	 */
	private Long datacenterId;
}
