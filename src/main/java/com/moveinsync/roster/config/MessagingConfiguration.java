package com.moveinsync.roster.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MessagingConfiguration {
    public static final String EXCHANGE = "roster.employee.events";
    public static final String QUEUE = "roster.employee.events.booking";
    public static final String ROUTING_KEY = "employee.#";

    @Bean
    TopicExchange employeeEventsExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    @Bean
    Queue employeeEventsQueue() {
        return new Queue(QUEUE, true);
    }

    @Bean
    Binding employeeEventsBinding(Queue employeeEventsQueue, TopicExchange employeeEventsExchange) {
        return BindingBuilder.bind(employeeEventsQueue).to(employeeEventsExchange).with(ROUTING_KEY);
    }

    @Bean
    RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
        return new RabbitAdmin(connectionFactory);
    }
}
