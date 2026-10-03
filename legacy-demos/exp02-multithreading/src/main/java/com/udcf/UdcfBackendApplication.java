package com.udcf;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point for the Unified Distributed Coordination Framework backend.
 *
 * <p>One codebase, several running instances. The active Spring profile
 * (node1 / node2 / node3 / gateway) decides the port, the node id and the role.
 * Nothing about a node's identity is hard-coded in Java.</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class UdcfBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(UdcfBackendApplication.class, args);
    }
}
