package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.utils.RedisData;
import lombok.extern.slf4j.Slf4j;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;



    @Override
    public Result queryById(Long id) {
        //预防缓存穿透
        //Shop shop = queryWithPassThough(id);

        //用互斥锁解决缓存击穿
//        Shop shop = queryWithMutex(id);
//        用逻辑过期解决缓存击穿
        Shop shop = queryWithLogicalExpire(id);
        //7.返回
        if (shop == null) {
            log.info("店铺{}不存在", id);
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);

    }

    public Shop queryWithMutex(Long id) {
        //1. 查询redis缓存
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            //3.存在，直接返回

            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //判断命中的是否是空值
        if (shopJson != null) {
            //返回错误信息
            return null;
        }
        //4.实现缓存重建
        //4.1 获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop;
        try {
            boolean isLock = tryLock(lockKey);
            //4.2 判断是否获取到锁
            if (!isLock) {
                //4.3如果失败，则休眠并重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            //4.4如果成功，则根据id查询数据库
            log.info("用户{}获取到锁，开始查询数据库", Thread.currentThread().getId());

            shop = getById(id);
            //模拟查询数据库的耗时
            Thread.sleep(200);
            //5.不存在，返回错误
            if (shop == null) {
                //写入空值到redis，防止缓存穿透
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //6.存在，写入redis缓存
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 7.释放互斥锁
            unlock(lockKey);
        }
        // 8.返回
        return shop;
    }

    public Shop queryWithLogicalExpire(Long id) {
        //1. 查询redis缓存
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //2.判断是否存在
        if (StrUtil.isBlank(shopJson)) {
            //3.不存在，直接返回
            return null;
        }

        //4.命中，先把JSON反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5.判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            //5.1未过期，直接返回
            return shop;
        }


//        6已过期，重建缓存
//        6.1获取互斥锁
        String lockKey=LOCK_SHOP_KEY+id;
        boolean isLock;
        isLock = tryLock(lockKey);
//        6.2判断是否获取锁成功
        if(isLock){
//        6.3成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(()->{
               try {
                    saveShop2Redis(id, 30L);
                }catch (Exception e){
                    throw new RuntimeException(e);
                }finally {
                   //释放锁
                   unlock(lockKey);
               }

            });
        }
//        6.4返回过期的商铺信息
        return shop;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public Shop queryWithPassThough(Long id) {
        //1. 查询redis缓存
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            //3.存在，直接返回

            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //判断命中的是否是空值
        if (shopJson != null) {
            //返回错误信息
            return null;
        }
        //4.不存在，查询数据库
        Shop shop = getById(id);
        //5.不存在，返回错误
        if (shop == null) {
            //写入空值到redis，防止缓存穿透
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //6.存在，写入redis缓存
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //7.返回
        return shop;
    }

    private void saveShop2Redis(Long id, Long expireSeconds) {
        //1.查询店铺数据
        Shop shop = getById(id);
        //2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //3.写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }


    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }


    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        //1.更新数据库
        updateById(shop);
        //2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());
        //3.返回

        return Result.ok();
    }
}
