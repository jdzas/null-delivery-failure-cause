package org.example;

import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.RedeliveryPolicy;
import org.apache.activemq.broker.region.policy.RedeliveryPolicyMap;
import org.apache.activemq.command.ActiveMQMessage;
import org.example.DlqDeliveryFailureCauseTest.TestApplication.DlqListener;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jms.ConnectionFactoryUnwrapper;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Component;
import org.testcontainers.activemq.ActiveMQContainer;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;
import static org.awaitility.Awaitility.await;

@SpringBootTest
class DlqDeliveryFailureCauseTest {

    @SpringBootApplication
    static class TestApplication {

        @Bean
        @ServiceConnection
        ActiveMQContainer activeMQContainer() {
            return new ActiveMQContainer("apache/activemq-classic:6.1.8");
        }

        @Bean
        DefaultJmsListenerContainerFactory containerFactory(ConnectionFactory connectionFactory) {
            final var factory = new DefaultJmsListenerContainerFactory();
            factory.setSessionTransacted(true);
            factory.setConnectionFactory(connectionFactory);
            factory.setPubSubDomain(false);
            return factory;
        }

        @Bean
        RedeliveryPolicyMap redeliveryPolicyMap() {
            final var map = new RedeliveryPolicyMap();
            final var entry = new RedeliveryPolicy();
            entry.setMaximumRedeliveries(0);
            map.setDefaultEntry(entry);
            return map;
        }

        @Bean
        InitializingBean redeliveryPolicyMapInitializingBean(
                ConnectionFactory connectionFactory,
                RedeliveryPolicyMap redeliveryPolicyMap) {
            return () -> Optional.of(connectionFactory)
                    .map(ConnectionFactoryUnwrapper::unwrap)
                    .map(ActiveMQConnectionFactory.class::cast)
                    .ifPresent(cf -> cf.setRedeliveryPolicyMap(redeliveryPolicyMap));
        }

        @Component
        static class FailingListener {

            @JmsListener(containerFactory = "containerFactory", destination = "d")
            public void onMessage(Message message) throws JMSException {
                throw new UnsupportedOperationException("rejected message " + message.getJMSMessageID());
            }
        }

        @Component
        static class DlqListener {

            ActiveMQMessage received;

            @JmsListener(containerFactory = "containerFactory", destination = "ActiveMQ.DLQ")
            public void onMessage(ActiveMQMessage message) {
                received = message;
            }
        }
    }

    @Autowired
    JmsTemplate template;

    @Autowired
    DlqListener dlqListener;

    /**
     * Wish this test could pass. The value of dlqDeliveryFailureCause is instead (note the "cause:null" at the end):
     *
     * java.lang.Throwable: Delivery[1] exceeds redelivery policy limit:RedeliveryPolicy {destination = null, collisionAvoidanceFactor = 0.15, maximumRedeliveries = 0, maximumRedeliveryDelay = -1, initialRedeliveryDelay = 1000, useCollisionAvoidance = false, useExponentialBackOff = false, backOffMultiplier = 5.0, redeliveryDelay = 1000, preDispatchCheck = true}, cause:null
     */
    @Test
    void test() {
        template.send("d", session -> session.createTextMessage("Hello"));
        await()
                .atMost(Duration.of(2, ChronoUnit.SECONDS))
                .untilAsserted(() -> {
                    final var received = dlqListener.received;
                    assertThat(received)
                            .extracting(m -> {
                                try {
                                    return m.getStringProperty("dlqDeliveryFailureCause");
                                } catch (JMSException e) {
                                    throw new RuntimeException(e);
                                }
                            })
                            .asInstanceOf(STRING)
                            .contains("rejected message " + received.getJMSMessageID());
                });
    }
}
