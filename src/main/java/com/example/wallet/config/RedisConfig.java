package com.example.wallet.config;

import com.example.wallet.outbox.BalanceEventBroadcaster;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
public class RedisConfig {

    /** Every app instance subscribes, so SSE clients get events whichever instance they are connected to. */
    @Bean
    RedisMessageListenerContainer redisListenerContainer(RedisConnectionFactory factory,
                                                         BalanceEventBroadcaster broadcaster,
                                                         WalletProperties props) {
        var container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.addMessageListener(broadcaster, new ChannelTopic(props.outbox().channel()));
        return container;
    }
}
