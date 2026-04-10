package com.ganghuan.myRPCVersion6.register;

import org.apache.curator.RetryPolicy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.cache.PathChildrenCache;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.zookeeper.CreateMode;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ZkServiceRegister implements ServiceRegister {
    // curator 提供的zookeeper客户端
    private final CuratorFramework client;
    // zookeeper根路径节点
    private static final String ROOT_PATH = "MyRPC";
    // 客户端本地缓存
    private final ServiceCache serviceCache = new ServiceCache();
    // 每个服务仅注册一次监听器
    private final Map<String, PathChildrenCache> watchMap = new ConcurrentHashMap<>();

    // 这里负责zookeeper客户端的初始化，并与zookeeper服务端建立连接
    public ZkServiceRegister(){
        // 指数时间重试
        RetryPolicy policy = new ExponentialBackoffRetry(1000, 3);
        // zookeeper的地址固定，不管是服务提供者还是，消费者都要与之建立连接
        // sessionTimeoutMs 与 zoo.cfg中的tickTime 有关系，
        // zk还会根据minSessionTimeout与maxSessionTimeout两个参数重新调整最后的超时值。默认分别为tickTime 的2倍和20倍
        // 使用心跳监听状态
        this.client = CuratorFrameworkFactory.builder().connectString("127.0.0.1:2181")
                .sessionTimeoutMs(40000).retryPolicy(policy).namespace(ROOT_PATH).build();
        this.client.start();
        System.out.println("zookeeper 连接成功");
    }

    @Override
    public void register(String serviceName, InetSocketAddress serverAddress){
        try {
            // serviceName创建成永久节点，服务提供者下线时，不删服务名，只删地址
            if(client.checkExists().forPath("/" + serviceName) == null){
                client.create().creatingParentsIfNeeded().withMode(CreateMode.PERSISTENT).forPath("/" + serviceName);
            }
            // 路径地址，一个/代表一个节点
            String path = "/" + serviceName +"/"+ getServiceAddress(serverAddress);
            // 临时节点，服务器下线就删除节点
            client.create().creatingParentsIfNeeded().withMode(CreateMode.EPHEMERAL).forPath(path);
        } catch (Exception e) {
            System.out.println("此服务已存在");
        }
    }
    // 根据服务名返回地址,服务发现
    @Override
    public InetSocketAddress serviceDiscovery(String serviceName) {
        List<InetSocketAddress> addressList = getServiceAddresses(serviceName);
        if (addressList.isEmpty()) {
            throw new RuntimeException("服务发现失败，未找到可用服务: " + serviceName);
        }
        return addressList.get(0);
    }

    @Override
    public List<InetSocketAddress> getServiceAddresses(String serviceName) {
        List<String> addressList = serviceCache.getServiceAddresses(serviceName);
        if (addressList.isEmpty()) {
            addressList = refreshServiceAddresses(serviceName, false);
            if (!addressList.isEmpty()) {
                registerWatcher(serviceName);
            }
        } else if (!watchMap.containsKey(serviceName)) {
            registerWatcher(serviceName);
        }
        List<InetSocketAddress> result = new ArrayList<>(addressList.size());
        for (String address : addressList) {
            result.add(parseAddress(address));
        }
        return result;
    }

    /**
     * @param preserveCacheOnFailure true 时表示如果注册中心暂时不可用，则保留旧缓存继续服务
     */
    private List<String> refreshServiceAddresses(String serviceName, boolean preserveCacheOnFailure) {
        List<String> cachedAddresses = serviceCache.getServiceAddresses(serviceName);
        try {
            String servicePath = "/" + serviceName;
            if (client.checkExists().forPath(servicePath) == null) {
                serviceCache.removeServiceAddresses(serviceName);
                return Collections.emptyList();
            }
            List<String> addressList = client.getChildren().forPath(servicePath);
            serviceCache.updateServiceAddresses(serviceName, addressList);
            return addressList;
        } catch (Exception e) {
            if (preserveCacheOnFailure && !cachedAddresses.isEmpty()) {
                System.out.println("刷新服务缓存失败，继续使用旧缓存: " + serviceName + "，原因: " + e.getMessage());
                return cachedAddresses;
            }
            throw new RuntimeException("刷新服务缓存失败: " + serviceName, e);
        }
    }

    private void registerWatcher(String serviceName) {
        watchMap.computeIfAbsent(serviceName, this::createWatcher);
    }

    private PathChildrenCache createWatcher(String serviceName) {
        String servicePath = "/" + serviceName;
        PathChildrenCache watcher = new PathChildrenCache(client, servicePath, true);
        watcher.getListenable().addListener((curatorClient, event) -> {
            try {
                switch (event.getType()) {
                    case CHILD_ADDED:
                    case CHILD_REMOVED:
                    case CHILD_UPDATED:
                    case CONNECTION_RECONNECTED:
                    case INITIALIZED:
                        refreshServiceAddresses(serviceName, true);
                        break;
                    default:
                        break;
                }
            } catch (Exception e) {
                // 监听线程只负责更新缓存，不应把异常扩散到业务调用路径
                System.out.println("监听服务节点刷新失败: " + serviceName + "，原因: " + e.getMessage());
            }
        });
        try {
            watcher.start(PathChildrenCache.StartMode.BUILD_INITIAL_CACHE);
            refreshServiceAddresses(serviceName, true);
            return watcher;
        } catch (Exception e) {
            throw new RuntimeException("注册服务监听失败: " + serviceName, e);
        }
    }

    // 地址 -> XXX.XXX.XXX.XXX:port 字符串
    private String getServiceAddress(InetSocketAddress serverAddress) {
        return serverAddress.getHostName() +
                ":" +
                serverAddress.getPort();
    }
    // 字符串解析为地址
    private InetSocketAddress parseAddress(String address) {
        String[] result = address.split(":");
        return new InetSocketAddress(result[0], Integer.parseInt(result[1]));
    }
}
