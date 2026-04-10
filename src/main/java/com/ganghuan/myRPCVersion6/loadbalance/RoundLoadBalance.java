package com.ganghuan.myRPCVersion6.loadbalance;

import com.ganghuan.myRPCVersion6.common.RPCRequest;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;

/**
 * 轮询负载均衡
 */
public class RoundLoadBalance implements LoadBalance{
    private final AtomicInteger choose = new AtomicInteger(0);

    @Override
    public InetSocketAddress select(List<InetSocketAddress> addressList, RPCRequest request) {
        int index = Math.abs(choose.getAndIncrement() % addressList.size());
        return addressList.get(index);
    }

    @Override
    public String name() {
        return "round";
    }
}
