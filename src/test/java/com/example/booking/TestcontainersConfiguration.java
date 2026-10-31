
package com.example.booking;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.HashMap;
import java.util.Map;

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(
				DockerImageName.parse("postgres:16")
		);
	}

	@Bean(initMethod = "start", destroyMethod = "stop")
	KafkaContainer kafka() {
		return new KafkaContainer("apache/kafka:3.8.0");
	}

	@Bean
	KafkaTemplate<String, String> kafkaTemplate(KafkaContainer kafka) {
		Map<String, Object> properties = new HashMap<>();

		properties.put(
				ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
				kafka.getBootstrapServers()
		);
		properties.put(
				ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
				StringSerializer.class
		);
		properties.put(
				ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
				StringSerializer.class
		);

		return new KafkaTemplate<>(
				new DefaultKafkaProducerFactory<>(properties)
		);
	}
}
