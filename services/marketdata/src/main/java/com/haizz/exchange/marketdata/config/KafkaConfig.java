package com.haizz.exchange.marketdata.config;

import com.haizz.exchange.common.kafka.TopicNames;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaConfig {

    @Value("${market.kafka.events-topic.partitions:1}")
    private int eventsTopicPartitions;

    @Value("${market.kafka.events-topic.replication-factor:1}")
    private short eventsTopicReplicationFactor;

    @Value("${market.kafka.events-topic.retention-ms:600000}")
    private long eventsTopicRetentionMs;

    @Value("${market.kafka.events-topic.segment-ms:60000}")
    private long eventsTopicSegmentMs;

    /**
     * KafkaAdmin owned by the producer of {@code market-data.events.v1}. {@code modifyTopicConfigs}
     * is enabled so the short retention below is also applied to the EXISTING topic (which Kafka
     * auto-created with the broker default 7-day retention), not only on first creation.
     */
    @Bean
    public KafkaAdmin kafkaAdmin(KafkaProperties kafkaProperties) {
        Map<String, Object> props = new HashMap<>();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaProperties.getBootstrapServers());
        KafkaAdmin admin = new KafkaAdmin(props);
        admin.setModifyTopicConfigs(true);
        return admin;
    }

    /**
     * Declares {@code market-data.events.v1} with a short retention. It is a high-rate ephemeral
     * firehose (best-effort external trades, bypassing the durable outbox), so it must not grow
     * unbounded. The matching consumer additionally seeks-to-end on start, so it never depends on
     * this backlog anyway. {@code segment.ms} is short so segments roll and retention reclaims them
     * promptly.
     */
    @Bean
    public org.apache.kafka.clients.admin.NewTopic marketDataEventsTopic() {
        return TopicBuilder.name(TopicNames.MARKET_DATA_EVENTS)
                .partitions(eventsTopicPartitions)
                .replicas(eventsTopicReplicationFactor)
                .config(TopicConfig.RETENTION_MS_CONFIG, Long.toString(eventsTopicRetentionMs))
                .config(TopicConfig.SEGMENT_MS_CONFIG, Long.toString(eventsTopicSegmentMs))
                .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE)
                .build();
    }

    @Bean
    public ProducerFactory<String, String> durableProducerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaProperties.getBootstrapServers());
        if (kafkaProperties.getProducer().getProperties() != null) {
            props.putAll(kafkaProperties.getProducer().getProperties());
        }
        // Override with durable settings
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        props.put(ProducerConfig.RETRIES_CONFIG, 10);
        props.put(ProducerConfig.LINGER_MS_CONFIG, 5);
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public ProducerFactory<String, String> ephemeralProducerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaProperties.getBootstrapServers());
        if (kafkaProperties.getProducer().getProperties() != null) {
            props.putAll(kafkaProperties.getProducer().getProperties());
        }
        // Override with ephemeral settings
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, false);
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        props.put(ProducerConfig.LINGER_MS_CONFIG, 0);
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    @Primary
    public KafkaTemplate<String, String> durableKafkaTemplate(
            ProducerFactory<String, String> durableProducerFactory) {
        return new KafkaTemplate<>(durableProducerFactory);
    }

    @Bean
    public KafkaTemplate<String, String> ephemeralKafkaTemplate(
            ProducerFactory<String, String> ephemeralProducerFactory) {
        return new KafkaTemplate<>(ephemeralProducerFactory);
    }
}
