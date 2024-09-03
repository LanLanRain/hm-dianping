package com.hmdp.utils;

/**
 * @author RainSoul
 * @create 2024-09-03
 */
public interface ILock {
    boolean tryLock(Long timeoutSec);

    void unlock();
}
