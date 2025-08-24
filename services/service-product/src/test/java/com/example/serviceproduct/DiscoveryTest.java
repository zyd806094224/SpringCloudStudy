package com.example.serviceproduct;

import com.alibaba.cloud.nacos.discovery.NacosServiceDiscovery;
import com.alibaba.nacos.api.exception.NacosException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.client.discovery.DiscoveryClient;

@SpringBootTest
public class DiscoveryTest {

    @Autowired
    DiscoveryClient discoveryClient;

    @Autowired
    private NacosServiceDiscovery nacosServiceDiscovery;

    @Test
    void discoveryClientTest() throws NacosException {
//        for (String service : discoveryClient.getServices()) {
//            System.out.println(service);
//        }

        for (String service : nacosServiceDiscovery.getServices()) {
            System.out.println(service);
        }
    }



}
