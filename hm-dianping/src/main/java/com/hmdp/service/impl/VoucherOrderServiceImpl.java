package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.util.Collections;
import java.util.concurrent.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Autowired
    private ISeckillVoucherService seckillVoucherService;

    @Autowired
    private RedisIdWorker redisIdWorker;

    @Autowired
    StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedissonClient redissonClient;
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
//               1.获取队列中的订单信息
                try {
                    VoucherOrder voucherOrder = orderTasks.take();
//                    2.创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (InterruptedException e) {
                    log.error("处理订单异常", e);
                }

            }
        }
    }
//    @Override
//    public Result seckillVoucher(Long voucherId) {//串行下单
//        //1.查询优惠券
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
////        2.判断秒杀是否开始
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("秒杀尚未开始");
//        }
//
////        3.判断秒杀是否结束
//        if (!voucher.getEndTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("秒杀已经结束");
//        }
////        4.判断库存是否充足
//        if (voucher.getStock() < 1) {
//            return Result.fail("库存不足");
//        }
//        Long userId = UserHolder.getUser().getId();
////       创建锁对象
////        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
//
//        boolean tryLock = lock.tryLock();
////        判断是否获取成功
//        if (!tryLock) {
////            获取锁失败返回错误或重试
//            return Result.fail("不允许重复下单");
//        }
//        try {
////    获取代理对象（事务）
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();//防止事务失效
//            return proxy.createVoucherOrder(voucherId);
//        } finally {
////            释放锁
//            lock.unlock();
//        }
//
//    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {//提取下单方法，用分布式锁实现一人只能下一单
        //        5.一人一单
        Long userId = voucherOrder.getUserId();
//        5.1查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder).count();
//        5.2判断是否存在
        if (count > 0) {
            log.error("用户已经购买过一次");
            return ;
        }

//        6.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder).gt("stock", 0)//通过加乐观锁判断库存是否足够来防止超卖
                .update();
        if (!success) {
            log.error("库存不足");
            return ;
        }
        save(voucherOrder);
//        7.返回订单id


    }
        private IVoucherOrderService proxy;

        @Override
        public Result seckillVoucher(Long voucherId){//异步下单
            String userId = UserHolder.getUser().getId().toString();
//       1.执行lua脚本
            Long result = stringRedisTemplate.execute(
                    SECKILL_SCRIPT,
                    Collections.emptyList(),
                    voucherId.toString(),
                    userId
            );
//       2.判断结果是否为0
//       2.1不为0，代表没有购买资格
            int r = result.intValue();
            if (r != 0) {
                return Result.fail(r == 1 ? "库存不足" : "不能重复下单");

            }
//       2.2为0，有购买资格，把下单信息保存到阻塞队列中
//       2.3创建订单
            VoucherOrder voucherOrder = new VoucherOrder();
            voucherOrder.setId(redisIdWorker.nextId("order"));
            voucherOrder.setVoucherId(voucherId);
            voucherOrder.setUserId(UserHolder.getUser().getId());
            //    获取代理对象（事务）
            proxy = (IVoucherOrderService) AopContext.currentProxy();//防止事务失效
//       2.4 保存到阻塞队列
            try {
                orderTasks.put(voucherOrder);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }


//       3.返回订单id
            return Result.ok(voucherOrder.getId());
        }



    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
//        创建锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);

        boolean tryLock = lock.tryLock();
//        判断是否获取成功
        if (!tryLock) {
//            获取锁失败返回错误或重试
            log.error("不允许重复下单");
            return;
        }
        try {
            proxy.createVoucherOrder(voucherOrder);
        } finally {
//            释放锁
            lock.unlock();
        }
    }
}
