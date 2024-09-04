package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
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
     * 定义一个默认的Redis脚本用于解锁操作。这个脚本使用Lua脚本语言编写，以确保原子操作。
     * 通过静态初始化块进行初始化，这样确保了类加载时该脚本就已经准备好。
     */
    private static  final DefaultRedisScript<Long> UN_LOCKSCRIPT;
    static {
        // 创建一个新的DefaultRedisScript实例，并设置其类型为Long，用于接收脚本执行结果
        UN_LOCKSCRIPT = new DefaultRedisScript<>();
        // 设置脚本资源位置，这里从类路径下加载名为"unlock.lua"的Lua脚本
        UN_LOCKSCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        // 设置脚本返回值的类型为Long类型
        UN_LOCKSCRIPT.setResultType(Long.class);
    }


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
        /* // 获取线程标示
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        // 获取锁中的标示
        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        // 判断标示是否一致
        if (threadId.equals(id)) {
            // 释放锁
            stringRedisTemplate.delete(KEY_PREFIX + name);
        } */

            // 执行解锁操作
        // 通过RedisTemplate的execute方法执行Lua脚本进行解锁处理
        // 参数说明：
        // - UN_LOCKSCRIPT: 解锁的Lua脚本
        // - 锁的key: 指定锁的唯一标识，由KEY_PREFIX和name拼接而成
        // - 线程标示: 当前线程的ID，用于标识是哪个线程对锁进行操作
        stringRedisTemplate.execute(
                UN_LOCKSCRIPT,
                // 锁的key
                Collections.singletonList(KEY_PREFIX + name),
                // 线程标示
                ID_PREFIX + Thread.currentThread().getId()
        );

    }
}
