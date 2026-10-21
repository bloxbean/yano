package com.example.yano.indexer;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.yanoproject.api.config.YanoPropertyKeys;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class YanoPropertiesTest {

    @Test
    void globalsKeepDottedYanoKeys() throws IOException {
        StandardEnvironment env = new StandardEnvironment();
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
                .forEach(env.getPropertySources()::addLast);

        YanoProperties props = new Binder(ConfigurationPropertySources.get(env))
                .bind("yano", YanoProperties.class).get();

        assertThat(props.globals())
                .containsKeys(YanoPropertyKeys.Utxo.ENABLED, YanoPropertyKeys.AccountState.ENABLED);
        assertThat(String.valueOf(props.globals().get(YanoPropertyKeys.Utxo.ENABLED))).isEqualTo("false");
    }
}
