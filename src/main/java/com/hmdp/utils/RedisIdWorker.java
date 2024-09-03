package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {
    /**
     * 开始时间戳
     */
    private static final long BEGIN_TIMESTAMP = 1640995200L;
    /**
     * 序列号的位数
     */
    private static final int COUNT_BITS = 32;

    private StringRedisTemplate stringRedisTemplate;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 根据键前缀生成唯一ID
     * 该方法通过结合时间戳和序列号来生成唯一ID，确保了高并发场景下的高效性和唯一性
     *
     * @param keyPrefix 键的前缀，用于区分不同类型的ID，如"user:"、"order:"
     * @return 唯一ID，由时间戳和序列号组成
     */
    public long nextId(String keyPrefix) {
        // 1.生成时间戳
        // 获取当前时间戳，并减去开始时间戳，以获取从开始时间点到现在的毫秒数
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;

        // 2.生成序列号
        // 2.1.获取当前日期，精确到天
        // 日期格式化，用于每天序列号自增
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        // 2.2.自增长
        // 根据键前缀和日期在Redis中获取自增序列号，保证每天序列号从0开始自增
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);

        // 3.拼接并返回
        // 将时间戳和序列号进行位运算拼接成最终的ID
        return timestamp << COUNT_BITS | count;
    }

}