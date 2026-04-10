package com.ganghuan.myRPCVersion6.loadbalance;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 轮询负载均衡
 */
public class RoundLoadBalance implements LoadBalance{
    private AtomicInteger choose = new AtomicInteger(0);
    @Override
    public String balance(List<String> addressList) {
        int index = Math.abs(choose.getAndIncrement() % addressList.size());
        return addressList.get(index);
    }
}
