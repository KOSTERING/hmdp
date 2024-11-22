package com.hmdp.utils;


import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.RedisData;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        //设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));

        //写入redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));
    }

    /**
     *
     * @param id user id
     * @param type return tyoe
     * @return
     * @param <R>
     */
    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback,Long time, TimeUnit unit) {

        String key = keyPrefix + id;
        //1.从redis查询缓存商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);

        //2.判断其是否存在
        if (StrUtil.isNotBlank(json)) {
            //3.存在直接返回
            return JSONUtil.toBean(json, type);
        }

        //判断命中的是否是空值
        if (json.equals("") ) {
            return  null;
        }

        //4.不存在查询数据库
        R r = dbFallback.apply(id);

        //5.不存在返回错误
        if (r == null) {
            //将空值写入redis
            stringRedisTemplate.opsForValue().set(key, "",CACHE_NULL_TTL, TimeUnit.MINUTES);

            return  null;
        }

        //6.存在写入redis
        this.set(key,r,time,unit);

        return r;
    }

    public <R,ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback,Long time, TimeUnit unit) {

        String key = CACHE_SHOP_KEY + id;
        //1.从redis查询缓存商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);

        //2.判断其是否不存在，因为存在数据预热，缓存不存在即代表在数据库也不存在
        if (StrUtil.isBlank(json)) {
            //3.不存在直接返回
            return null;
        }

        // 4.命中，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 5.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 5.1.未过期，直接返回店铺信息
            return r;
        }
        // 5.2.已过期，需要缓存重建
        // 6.缓存重建
        // 6.1.获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        // 6.2.判断是否获取锁成功
        if (isLock) {
            CACHE_REBUILD_EXECUTOR.submit(() -> {

                try {
                    //查询数据库
                    R newR = dbFallback.apply(id);

                    //重建缓存
                    this.setWithLogicalExpire(key, newR,time,unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(lockKey);
                }
            });
        }
        // 6.4.返回过期的商铺信息
        return r;
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "互斥锁", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private boolean unlock(String key) {
        return stringRedisTemplate.delete(key);
    }



}
