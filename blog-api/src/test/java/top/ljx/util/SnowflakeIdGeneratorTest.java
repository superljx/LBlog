package top.ljx.util;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 雪花ID生成器单元测试。不加载Spring上下文，仅验证生成器自身的核心保证。
 *
 * @author: ljx
 * @date: 2026-06-02
 */
class SnowflakeIdGeneratorTest {

	/**
	 * 非法的 workerId / datacenterId 应在构造时抛异常。
	 */
	@Test
	void invalidArgsRejected() {
		assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdGenerator(-1, 0));
		assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdGenerator(32, 0));
		assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdGenerator(0, -1));
		assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdGenerator(0, 32));
	}

	/**
	 * 单线程下大批量生成：全部为正数且互不重复，且单调递增。
	 */
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

	/**
	 * 多线程并发生成：不应产生重复ID。
	 */
	@Test
	void concurrentUnique() throws InterruptedException {
		SnowflakeIdGenerator generator = new SnowflakeIdGenerator(5, 7);
		int threads = 16;
		int perThread = 20_000;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch ready = new CountDownLatch(threads);
		CountDownLatch start = new CountDownLatch(1);
		ConcurrentLinkedQueue<Long> all = new ConcurrentLinkedQueue<>();

		for (int t = 0; t < threads; t++) {
			pool.submit(() -> {
				ready.countDown();
				try {
					start.await();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
				for (int i = 0; i < perThread; i++) {
					all.add(generator.nextId());
				}
			});
		}
		ready.await();
		start.countDown();
		pool.shutdown();
		assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "生成任务未在超时时间内完成");

		List<Long> list = new java.util.ArrayList<>(all);
		Set<Long> distinct = new HashSet<>(list);
		assertEquals(threads * perThread, list.size(), "生成数量不符");
		assertEquals(list.size(), distinct.size(), "并发生成出现重复ID");
		assertTrue(Collections.min(list) > 0, "存在非正数ID");
	}
}
