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
 * 雪花ID生成器配置类。
 * <p>
 * 将 {@link SnowflakeIdGenerator} 注册为单例 Bean。workerId/datacenterId 优先取配置文件中的值，
 * 未配置时基于本机 MAC 地址推导，保证同一台机器多次重启取值稳定。
 *
 * @author: ljx
 * @date: 2026-06-02
 */
@Configuration
public class SnowflakeConfig {
	private static final Logger logger = LoggerFactory.getLogger(SnowflakeConfig.class);
	/** 5 位ID的取值上限 + 1，用于取模 */
	private static final long MAX_ID = 32L;

	@Bean
	public SnowflakeIdGenerator snowflakeIdGenerator(SnowflakeProperties properties) {
		long workerId = properties.getWorkerId() != null
				? properties.getWorkerId()
				: deriveId(0);
		long datacenterId = properties.getDatacenterId() != null
				? properties.getDatacenterId()
				: deriveId(1);
		logger.info("初始化雪花ID生成器: workerId={}, datacenterId={}", workerId, datacenterId);
		return new SnowflakeIdGenerator(workerId, datacenterId);
	}

	/**
	 * 基于本机 MAC 地址推导一个 0~31 的稳定ID。
	 * 取不到 MAC 时回退为基于 salt 的伪随机值，保证不会抛异常导致启动失败。
	 *
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
		// 回退：基于进程相关信息生成，仍限定在合法范围内
		return Math.abs((System.nanoTime() + salt) % MAX_ID);
	}
}
