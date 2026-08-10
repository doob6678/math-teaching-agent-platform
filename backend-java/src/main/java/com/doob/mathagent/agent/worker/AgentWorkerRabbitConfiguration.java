package com.doob.mathagent.agent.worker;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Durable Agent Worker command topology. Roles are routing keys, so a worker never receives unsupported work. */
@Configuration
public class AgentWorkerRabbitConfiguration {
    public static final String EXCHANGE = "agent.worker";
    public static final String QUEUE = "agent.worker.courseware.q";
    public static final String DLQ = "agent.worker.courseware.dlq";
    public static final String ROUTING_KEY = "CoursewareAgent";
    /** The migrated Python graph is one lease-protected command, not four independently acknowledged stages. */
    public static final String PYTHON_HANDOUT_AGENT_CODE = "PythonHandoutAgent";
    public static final String PYTHON_HANDOUT_STAGE_CODE = "python_handout";
    public static final java.util.List<String> SUPPORTED_AGENT_CODES = java.util.List.of(
            "CoursewareAgent", "TeacherAssistantAgent", "HandoutFormatterAgent", "QualityCheckAgent",
            PYTHON_HANDOUT_AGENT_CODE);
    @Bean DirectExchange agentWorkerExchange(){ return new DirectExchange(EXCHANGE, true, false); }
    @Bean Queue agentWorkerQueue(){ return org.springframework.amqp.core.QueueBuilder.durable(QUEUE).deadLetterExchange(EXCHANGE).deadLetterRoutingKey(ROUTING_KEY+".dead").build(); }
    @Bean Queue agentWorkerDeadLetterQueue(){ return org.springframework.amqp.core.QueueBuilder.durable(DLQ).build(); }
    @Bean Binding agentWorkerBinding(DirectExchange agentWorkerExchange, Queue agentWorkerQueue){ return BindingBuilder.bind(agentWorkerQueue).to(agentWorkerExchange).with(ROUTING_KEY); }
    /** This first Worker image supports all current writing roles; future role-specific Workers bind their own queues. */
    @Bean Binding teacherAssistantWorkerBinding(DirectExchange agentWorkerExchange, Queue agentWorkerQueue) { return BindingBuilder.bind(agentWorkerQueue).to(agentWorkerExchange).with("TeacherAssistantAgent"); }
    @Bean Binding handoutFormatterWorkerBinding(DirectExchange agentWorkerExchange, Queue agentWorkerQueue) { return BindingBuilder.bind(agentWorkerQueue).to(agentWorkerExchange).with("HandoutFormatterAgent"); }
    @Bean Binding qualityCheckWorkerBinding(DirectExchange agentWorkerExchange, Queue agentWorkerQueue) { return BindingBuilder.bind(agentWorkerQueue).to(agentWorkerExchange).with("QualityCheckAgent"); }
    /** Routes the whole Python handout graph to the existing lease-protected Agent Worker queue. */
    @Bean Binding pythonHandoutWorkerBinding(DirectExchange agentWorkerExchange, Queue agentWorkerQueue) {
        return BindingBuilder.bind(agentWorkerQueue).to(agentWorkerExchange).with(PYTHON_HANDOUT_AGENT_CODE);
    }
    @Bean Binding agentWorkerDeadLetterBinding(DirectExchange agentWorkerExchange, Queue agentWorkerDeadLetterQueue){ return BindingBuilder.bind(agentWorkerDeadLetterQueue).to(agentWorkerExchange).with(ROUTING_KEY+".dead"); }
    /** Uses correlated confirms and mandatory returns; an outbox event is published only after broker ACK. */
    @Bean("agentWorkerRabbitTemplate") RabbitTemplate agentWorkerRabbitTemplate(CachingConnectionFactory connectionFactory, ObjectMapper objectMapper){
        connectionFactory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        connectionFactory.setPublisherReturns(true);
        RabbitTemplate template=new RabbitTemplate(connectionFactory);
        template.setMessageConverter(new Jackson2JsonMessageConverter(objectMapper));
        template.setMandatory(true);
        return template;
    }
    /**
     * Applies the configured bounded concurrency to the consumer itself.  Previously the
     * workflow fanned out three independent writers but Rabbit still consumed one at a time,
     * converting a parallel stage into three sequential provider waits.
     */
    @Bean("agentWorkerRabbitListenerFactory") SimpleRabbitListenerContainerFactory agentWorkerRabbitListenerFactory(
            CachingConnectionFactory connectionFactory, ObjectMapper objectMapper, Environment environment) {
        int concurrency = Math.max(1, environment.getProperty("math-agent.agent-worker.runtime.max-concurrency", Integer.class, 1));
        boolean listenersEnabled = environment.getProperty("math-agent.rabbitmq.listeners-enabled", Boolean.class, false);
        SimpleRabbitListenerContainerFactory factory=new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(new Jackson2JsonMessageConverter(objectMapper));
        factory.setDefaultRequeueRejected(false);
        factory.setPrefetchCount(1);
        factory.setConcurrentConsumers(concurrency);
        factory.setMaxConcurrentConsumers(concurrency);
        // The Spring default listener switch does not control custom factories; bind this factory explicitly so a
        // maintenance/development profile cannot consume AI work merely because the broker happens to be reachable.
        factory.setAutoStartup(listenersEnabled);
        return factory;
    }
}
