package com.bloxbean.cardano.yano.appchain.spring;

import com.bloxbean.cardano.yano.appchain.client.AppChainClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.MethodIntrospector;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Wires {@link AppChainListener} methods to SSE subscriptions on the
 * auto-configured {@link AppChainClient} (ADR app-layer/006 E1.4). One
 * subscription per annotated method, started after all singletons exist and
 * closed on context shutdown. Listener errors are logged, never fatal to the
 * stream.
 */
public class AppChainListenerProcessor
        implements BeanPostProcessor, SmartInitializingSingleton, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(AppChainListenerProcessor.class);

    private final AppChainClient client;
    private final List<Registration> registrations = new ArrayList<>();
    private final List<AutoCloseable> subscriptions = new ArrayList<>();

    private record Registration(Object bean, Method method, AppChainListener annotation) {
    }

    public AppChainListenerProcessor(AppChainClient client) {
        this.client = client;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        Class<?> targetClass = AopUtils.getTargetClass(bean);
        Map<Method, AppChainListener> annotated = MethodIntrospector.selectMethods(targetClass,
                (MethodIntrospector.MetadataLookup<AppChainListener>) method ->
                        method.getAnnotation(AppChainListener.class));
        for (Map.Entry<Method, AppChainListener> entry : annotated.entrySet()) {
            validateSignature(entry.getKey());
            registrations.add(new Registration(bean, entry.getKey(), entry.getValue()));
        }
        return bean;
    }

    @Override
    public void afterSingletonsInstantiated() {
        for (Registration registration : registrations) {
            AppChainListener annotation = registration.annotation();
            String topic = annotation.topic().isEmpty() ? null : annotation.topic();
            Method method = registration.method();
            method.setAccessible(true);
            AutoCloseable subscription = client.subscribe(annotation.fromHeight(), topic, message -> {
                try {
                    method.invoke(registration.bean(), convert(message, method));
                } catch (Exception e) {
                    log.error("@AppChainListener {}#{} failed for message {}: {}",
                            registration.bean().getClass().getSimpleName(), method.getName(),
                            message.messageId(), e.getCause() != null ? e.getCause() : e);
                }
            });
            subscriptions.add(subscription);
            log.info("@AppChainListener registered: {}#{} (topic: {}, fromHeight: {})",
                    registration.bean().getClass().getSimpleName(), method.getName(),
                    topic != null ? topic : "*", annotation.fromHeight());
        }
    }

    private static void validateSignature(Method method) {
        if (method.getParameterCount() != 1 || !supported(method.getParameterTypes()[0])) {
            throw new IllegalStateException("@AppChainListener method " + method
                    + " must take exactly one parameter of type StreamedMessage, byte[] or String");
        }
    }

    private static boolean supported(Class<?> type) {
        return type == AppChainClient.StreamedMessage.class || type == byte[].class || type == String.class;
    }

    private static Object convert(AppChainClient.StreamedMessage message, Method method) {
        Class<?> type = method.getParameterTypes()[0];
        if (type == byte[].class) {
            return message.body();
        }
        if (type == String.class) {
            return new String(message.body(), StandardCharsets.UTF_8);
        }
        return message;
    }

    @Override
    public void destroy() {
        for (AutoCloseable subscription : subscriptions) {
            try {
                subscription.close();
            } catch (Exception ignored) {
            }
        }
        subscriptions.clear();
    }
}
