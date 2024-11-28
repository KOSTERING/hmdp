package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
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
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    //阻塞队列
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);
    //线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    private void afterinit(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                
                try {
                    //1.获取队列中的消息，如果没有一直阻塞
                    VoucherOrder voucherOrder = orderTasks.take();
                    //2.创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常",e);
                }
            }
        }
    }

    private IVoucherOrderService proxy;
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //1.获取对象
        Long userId = voucherOrder.getUserId();

        RLock lock = redissonClient.getLock("Lock:order:" + userId);

        //获取锁
        boolean bLock = lock.tryLock(); //无参即失败不等待
        if (!bLock) {
            log.error("不允许重复下单");
            return;
        }
        try {
            //获取代理对象
            proxy.createVoucherOrder(voucherOrder);
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }
    }


    @Override
    public Result seckillVoucher(Long voucherId) {
        //获取用户
        Long userId = UserHolder.getUser().getId();
        //1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );
        //2.判断结构为0
        int r = result.intValue();
        //2.1不为0,代表没有购买资格
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }

        //2.2为0，有资格购买，把下单信息保存到阻塞队列
        long orderId = redisIdWorker.nextId("order");

        //TODO:保存阻塞队列

        //3.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setUserId(UserHolder.getUser().getId());

        orderTasks.add(voucherOrder);
        //4.获取代理对象
        proxy = (IVoucherOrderService)AopContext.currentProxy();

        //5.返回订单id
        return Result.ok(orderId);
    }

    /*@Override
    public Result seckillVoucher(Long voucherId) {
        //1.查询优惠卷
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //2.判断秒杀活动是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀尚未开始！");
        }

        //3.判断秒杀活动是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束！");
        }

        //4.判断库存是否充足
        if (voucher.getStock() < 1) {
            return Result.fail("库存不足");
        }
        Long userId = UserHolder.getUser().getId();
        //创建锁对象
        //SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);

        RLock lock = redissonClient.getLock("Lock:order:" + userId);
        boolean b = lock.tryLock();

        //获取锁
        boolean bLock = lock.tryLock(); //无参即失败不等待
        if (!bLock) {
            return Result.fail("获取锁失败，不允许重复下单");
        }
        try {
            //获取代理对象
            IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }
    }*/

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //5.一人一单
        Long userId = voucherOrder.getUserId();

        //5.1查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        //5.2判断是否存在
        if (count > 0) {
            log.error("用户已经购买过一次！");
        }

        //6.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock -1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                //.eq("stock",voucher.getStock()) 乐观锁 将库存作为版本号判断依据 但会有成功率低的问题
                .gt("stock", 0) //只需大于0即可，避免操作数据库时产生超卖
                .update();

        if (!success) {
            //扣减失败
            log.error("库存不足！");
            return;
        }

        //7.创建订单
        save(voucherOrder);
    }
}
