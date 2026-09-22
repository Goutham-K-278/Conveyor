package com.conveyor.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Configures the Redis client beans used across the application.
 *
 * We use a RedisTemplate<String, String> everywhere — both keys and values
 * are plain strings. This makes the data human-readable when you inspect
 * Redis directly (e.g. via `redis-cli`), which is invaluable for debugging.
 *
 * The connection details (host, port, password) come from application.yml.
 */
@Configuration
public class RedisConfig {

    /**
     * Creates a RedisTemplate that serializes both keys and values as UTF-8 strings.
     *
     * By default, Spring's RedisTemplate uses Java serialization, which produces
     * unreadable binary data. Switching to StringRedisSerializer means you can
     * inspect queue contents with redis-cli LRANGE, KEYS, etc.
     */
    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Use plain string serialization for both keys and values
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setValueSerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setHashValueSerializer(stringSerializer);

        template.afterPropertiesSet();
        return template;
    }
}
