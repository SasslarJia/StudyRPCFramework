package com.ganghuan.myRPCVersion6.loadbalance;

import com.ganghuan.myRPCVersion6.common.RPCRequest;

import java.net.InetSocketAddress;
import java.util.Random;
import java.util.List;

/**
 * 随机负载均衡
 */
public class RandomLoadBalance implements  LoadBalance{
    private final Random random = new Random();

    @Override
    public InetSocketAddress select(List<InetSocketAddress> addressList, RPCRequest request) {
        int choose = random.nextInt(addressList.size());
        return addressList.get(choose);
    }

    @Override
    public String name() {
        return "random";
    }
}
