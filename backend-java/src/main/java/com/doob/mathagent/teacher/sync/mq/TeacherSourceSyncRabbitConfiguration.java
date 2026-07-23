package com.doob.mathagent.teacher.sync.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Declares the durable command/DLQ topology and uses bounded listener work to protect CPU and vector workers. */
@Configuration
@EnableConfigurationProperties(TeacherSourceSyncRabbitProperties.class)
public class TeacherSourceSyncRabbitConfiguration {

    /** Main exchange owns both the command route and the dead-letter route. */
    @Bean
    DirectExchange teacherSourceSyncExchange(TeacherSourceSyncRabbitProperties properties) {
        return new DirectExchange(properties.exchange(), true, false);
    }

    /** Main queue is durable and sends unrecoverable infrastructure failures to the explicitly visible DLQ. */
    @Bean
    Queue teacherSourceSyncCommandQueue(TeacherSourceSyncRabbitProperties properties) {
        return org.springframework.amqp.core.QueueBuilder.durable(properties.queue())
                .deadLetterExchange(properties.exchange())
                .deadLetterRoutingKey(properties.routingKey() + ".dead")
                .build();
    }

    /** DLQ retains messages for operator inspection instead of silently dropping failed commands. */
    @Bean
    Queue teacherSourceSyncDeadLetterQueue(TeacherSourceSyncRabbitProperties properties) {
        return org.springframework.amqp.core.QueueBuilder.durable(properties.deadLetterQueue()).build();
    }

    @Bean
    Binding teacherSourceSyncCommandBinding(
            Queue teacherSourceSyncCommandQueue,
            DirectExchange teacherSourceSyncExchange,
            TeacherSourceSyncRabbitProperties properties) {
        return BindingBuilder.bind(teacherSourceSyncCommandQueue).to(teacherSourceSyncExchange).with(properties.routingKey());
    }

    @Bean
    Binding teacherSourceSyncDeadLetterBinding(
            Queue teacherSourceSyncDeadLetterQueue,
            DirectExchange teacherSourceSyncExchange,
            TeacherSourceSyncRabbitProperties properties) {
        return BindingBuilder.bind(teacherSourceSyncDeadLetterQueue).to(teacherSourceSyncExchange)
                .with(properties.routingKey() + ".dead");
    }

    /** JSON makes the broker contract inspectable and language-neutral for a future Python worker. */
    @Bean
    Jackson2JsonMessageConverter teacherSourceSyncMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    /** Publisher confirms prevent an unroutable/broker-rejected command from being reported as dispatched. */
    @Bean
    RabbitTemplate teacherSourceSyncRabbitTemplate(
            CachingConnectionFactory connectionFactory,
            Jackson2JsonMessageConverter teacherSourceSyncMessageConverter) {
        connectionFactory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(teacherSourceSyncMessageConverter);
        template.setMandatory(true);
        return template;
    }

    /** Bounded prefetch avoids allowing one backend instance to reserve an unbounded number of expensive parses. */
    @Bean(name = "teacherSourceSyncRabbitListenerContainerFactory")
    SimpleRabbitListenerContainerFactory teacherSourceSyncRabbitListenerContainerFactory(
            CachingConnectionFactory connectionFactory,
            Jackson2JsonMessageConverter teacherSourceSyncMessageConverter,
            TeacherSourceSyncRabbitProperties properties) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(teacherSourceSyncMessageConverter);
        factory.setConcurrentConsumers(properties.consumerConcurrency());
        factory.setMaxConcurrentConsumers(properties.consumerMaxConcurrency());
        factory.setPrefetchCount(properties.prefetch());
        factory.setDefaultRequeueRejected(false);
        return factory;
    }
}
