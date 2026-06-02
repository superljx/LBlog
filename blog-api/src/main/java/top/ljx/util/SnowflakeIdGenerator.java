package top.ljx.util;

/**
 * 雪花算法(Snowflake)ID生成器。
 * <p>
 * 生成一个 64 位的 long 型唯一ID，结构如下（与 Twitter 原版一致）：
 * <pre>
 * 0 - 0000000000 0000000000 0000000000 0000000000 0 - 00000 - 00000 - 000000000000
 * |   |------------------41位时间戳-----------------|  |-5位-| |-5位-| |---12位序列---|
 * 符号位                                              数据中心   机器      毫秒内序列
 * </pre>
 * <ul>
 *     <li>1 位符号位，始终为 0，保证生成的ID为正数；</li>
 *     <li>41 位毫秒级时间戳（相对自定义纪元），约可使用 69 年；</li>
 *     <li>5 位数据中心ID + 5 位机器ID，最多支持 1024 个节点；</li>
 *     <li>12 位毫秒内序列号，单节点单毫秒最多生成 4096 个ID。</li>
 * </ul>
 * <p>
 * 线程安全：所有获取ID的操作通过 {@code synchronized} 串行化。
 * 高可用：对时钟回拨做了容错——小幅回拨自旋等待时钟追平，大幅回拨直接抛异常拒绝服务，
 * 避免生成重复ID造成主键冲突这类更隐蔽的数据问题。
 *
 * @author: ljx
 * @date: 2026-06-02
 */
public class SnowflakeIdGenerator {
	/**
	 * 起始纪元时间戳(2024-01-01 00:00:00 UTC 的毫秒数)。
	 * 一经上线请勿修改，否则可能与历史已生成的ID冲突。
	 */
	private static final long EPOCH = 1704067200000L;

	/** 机器ID所占位数 */
	private static final long WORKER_ID_BITS = 5L;
	/** 数据中心ID所占位数 */
	private static final long DATACENTER_ID_BITS = 5L;
	/** 序列号所占位数 */
	private static final long SEQUENCE_BITS = 12L;

	/** 机器ID最大值(31) */
	private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);
	/** 数据中心ID最大值(31) */
	private static final long MAX_DATACENTER_ID = ~(-1L << DATACENTER_ID_BITS);
	/** 序列号掩码(4095) */
	private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS);

	/** 机器ID左移位数 */
	private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;
	/** 数据中心ID左移位数 */
	private static final long DATACENTER_ID_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;
	/** 时间戳左移位数 */
	private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS + DATACENTER_ID_BITS;

	/**
	 * 可容忍的时钟回拨最大毫秒数，在此范围内自旋等待时钟追平；
	 * 超过则判定为严重回拨，拒绝生成ID。
	 */
	private static final long MAX_BACKWARD_MS = 5L;

	private final long workerId;
	private final long datacenterId;

	/** 毫秒内序列号 */
	private long sequence = 0L;
	/** 上一次生成ID的时间戳 */
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

	/**
	 * 生成下一个全局唯一ID。
	 *
	 * @return 雪花ID(正数)
	 */
	public synchronized long nextId() {
		long timestamp = timeGen();

		// 处理时钟回拨
		if (timestamp < lastTimestamp) {
			long offset = lastTimestamp - timestamp;
			if (offset <= MAX_BACKWARD_MS) {
				// 小幅回拨：自旋等待时钟追上 lastTimestamp
				timestamp = tilNextMillis(lastTimestamp);
			} else {
				// 大幅回拨：拒绝服务，避免生成重复ID
				throw new IllegalStateException(
						String.format("检测到时钟回拨，拒绝生成ID。回拨 %d 毫秒", offset));
			}
		}

		if (timestamp == lastTimestamp) {
			// 同一毫秒内，递增序列号
			sequence = (sequence + 1) & SEQUENCE_MASK;
			if (sequence == 0) {
				// 当前毫秒序列号用尽，等待下一毫秒
				timestamp = tilNextMillis(lastTimestamp);
			}
		} else {
			// 进入新的毫秒，序列号归零
			sequence = 0L;
		}

		lastTimestamp = timestamp;

		return ((timestamp - EPOCH) << TIMESTAMP_SHIFT)
				| (datacenterId << DATACENTER_ID_SHIFT)
				| (workerId << WORKER_ID_SHIFT)
				| sequence;
	}

	/**
	 * 自旋等待，直到获取到大于 lastTimestamp 的时间戳。
	 */
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
