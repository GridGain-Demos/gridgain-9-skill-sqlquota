package com.example.gg9quota.support;

import org.apache.ignite.client.IgniteClient;

public final class IgniteClients {

    private IgniteClients() {}

    public static IgniteClient connect(Gg9Container container) {
        return IgniteClient.builder()
            .addresses(container.clientHost() + ":" + container.clientPort())
            .build();
    }
}
