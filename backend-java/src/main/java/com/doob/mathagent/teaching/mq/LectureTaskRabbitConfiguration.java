package com.doob.mathagent.teaching.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Dedicated durable topology for top-level lecture tasks, isolated from stage-level Agent Worker queues. */
@Configuration
public class LectureTaskRabbitConfiguration {
    public static final String EXCHANGE = "lecture.task.exchange";
    public static final String QUEUE = "lecture.task.q";
    public static final String RETRY_QUEUE = "lecture.task.retry.q";
    public static final String DLQ = "lecture.task.dlq";
    public static final String ROUTING_KEY = "lecture.task.create";
    public static final String DEAD_ROUTING_KEY = "lecture.task.dead";
    @Bean DirectExchange lectureTaskExchange() { return new DirectExchange(EXCHANGE, true, false); }
    @Bean Queue lectureTaskQueue() { return QueueBuilder.durable(QUEUE).deadLetterExchange(EXCHANGE).deadLetterRoutingKey(DEAD_ROUTING_KEY).build(); }
    @Bean Queue lectureTaskRetryQueue() { return QueueBuilder.durable(RETRY_QUEUE).build(); }
    @Bean Queue lectureTaskDeadLetterQueue() { return QueueBuilder.durable(DLQ).build(); }
    @Bean Binding lectureTaskBinding(DirectExchange lectureTaskExchange, Queue lectureTaskQueue) { return BindingBuilder.bind(lectureTaskQueue).to(lectureTaskExchange).with(ROUTING_KEY); }
    @Bean Binding lectureTaskRetryBinding(DirectExchange lectureTaskExchange, Queue lectureTaskRetryQueue) { return BindingBuilder.bind(lectureTaskRetryQueue).to(lectureTaskExchange).with(ROUTING_KEY + ".retry"); }
    @Bean Binding lectureTaskDeadLetterBinding(DirectExchange lectureTaskExchange, Queue lectureTaskDeadLetterQueue) { return BindingBuilder.bind(lectureTaskDeadLetterQueue).to(lectureTaskExchange).with(DEAD_ROUTING_KEY); }
    @Bean("lectureTaskRabbitTemplate") RabbitTemplate lectureTaskRabbitTemplate(CachingConnectionFactory connectionFactory, ObjectMapper objectMapper) { RabbitTemplate template = new RabbitTemplate(connectionFactory); template.setMessageConverter(new Jackson2JsonMessageConverter(objectMapper)); return template; }
    @Bean("lectureTaskRabbitListenerFactory") SimpleRabbitListenerContainerFactory lectureTaskRabbitListenerFactory(CachingConnectionFactory connectionFactory, ObjectMapper objectMapper) { SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory(); factory.setConnectionFactory(connectionFactory); factory.setMessageConverter(new Jackson2JsonMessageConverter(objectMapper)); factory.setDefaultRequeueRejected(false); factory.setPrefetchCount(1); return factory; }
}
