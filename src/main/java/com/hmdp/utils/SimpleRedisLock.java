package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

/**
 * @author RainSoul
 * @create 2024-09-03
 */
public class SimpleRedisLock implements ILock {

    private String name;
    private StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private static final String KEY_PREFIX = "lock:";

    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";

    /**
     * 尝试获取分布式锁
     *
     * @param timeoutSec 锁的超时时间，单位为秒如果在超时时间内未能成功获取锁，方法将返回false
     * @return 如果成功获取锁，则返回true；否则返回false
     */
    @Override
    public boolean tryLock(Long timeoutSec) {
        // 获取当前线程的ID，并将其转换为字符串格式，用于标识锁的持有者
        String threadId = ID_PREFIX + Thread.currentThread().getId() + "";
        // 尝试使用Redis的setIfAbsent方法来获取锁
        // 如果键不存在，则设置键值对，表示成功获取锁
        // 键名由KEY_PREFIX和name拼接而成，确保锁的唯一性
        // 同时设置锁的过期时间，防止死锁
        Boolean isLock = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        // 判断是否成功获取锁
        return Boolean.TRUE.equals(isLock);
    }


    @Override
    public void unlock() {
        // 获取线程标示
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        // 获取锁中的标示
        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        // 判断标示是否一致
        if (threadId.equals(id)) {
            // 释放锁
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }
    }
}
