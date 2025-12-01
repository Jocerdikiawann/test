package com.company.queue.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Properties;

@ApplicationScoped
public class KafkaAdminConfig {

    @ConfigProperty(name = "kafka.bootstrap.servers")
    String bootstrapServers;

    @ConfigProperty(name = "kafka.topic.replication-factor", defaultValue = "1")
    short replicationFactor;

    @ConfigProperty(name = "kafka.topic.partitions", defaultValue = "10")
    int partitions;

    @Produces
    @ApplicationScoped
    public AdminClient adminClient() {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 30000);
        props.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 60000);

        return AdminClient.create(props);
    }

    public short getReplicationFactor() {
        return replicationFactor;
    }

    public int getPartitions() {
        return partitions;
    }
}
