package com.bloxbean.cardano.yano.appchain.spring;

import com.bloxbean.cardano.yano.appchain.client.AppChainClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.MethodIntrospector;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Wires {@link AppChainListener} methods to SSE subscriptions on the
 * auto-configured {@link AppChainClient} (ADR app-layer/006 E1.4).
 * <p>
 * Lifecycle: implemented as a {@link SmartLifecycle} so subscriptions start
 * after the context is ready and — crucially — STOP in the lifecycle stop
 * phase, i.e. before singleton destruction (no messages are dispatched into
 * already-destroyed beans on shutdown). Beans created after startup (e.g.
 * {@code @Lazy}) are subscribed immediately on creation. Proxied beans are
 * invoked through the proxy via an invocable method resolved with
 * {@link AopUtils#selectInvocableMethod} (JDK proxies and private methods
 * behind CGLIB fail fast at registration with a clear error, matching
 * Spring's own listener processors). Listener errors are logged, never fatal
 * to the stream.
 */
public class AppChainListenerProcessor implements BeanPostProcessor, SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AppChainListenerProcessor.class);

    private final ObjectProvider<AppChainClient> clientProvider;
    private final List<Registration> pending = new CopyOnWriteArrayList<>();
    private final List<AutoCloseable> subscriptions = new CopyOnWriteArrayList<>();
    /** Classes known to carry no @AppChainListener methods (introspection cache). */
    private final Set<Class<?>> nonAnnotated = ConcurrentHashMap.newKeySet();
    private volatile boolean running;

    private record Registration(Object bean, Method invocableMethod, AppChainListener annotation) {
    }

    public AppChainListenerProcessor(ObjectProvider<AppChainClient> clientProvider) {
        this.clientProvider = clientProvider;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        Class<?> targetClass = AopUtils.getTargetClass(bean);
        if (nonAnnotated.contains(targetClass)) {
            return bean;
        }
        Map<Method, AppChainListener> annotated = MethodIntrospector.selectMethods(targetClass,
                (MethodIntrospector.MetadataLookup<AppChainListener>) method ->
                        method.getAnnotation(AppChainListener.class));
        if (annotated.isEmpty()) {
            nonAnnotated.add(targetClass);
            return bean;
        }
        for (Map.Entry<Method, AppChainListener> entry : annotated.entrySet()) {
            validateSignature(entry.getKey());
            // Resolve a method invocable ON THE BEAN AS EXPOSED (the proxy):
            // fails fast for private methods behind JDK/CGLIB proxies instead
            // of failing on every message at runtime.
            Method invocable = AopUtils.selectInvocableMethod(entry.getKey(), bean.getClass());
            Registration registration = new Registration(bean, invocable, entry.getValue());
            if (running) {
                subscribe(registration); // bean created after startup (@Lazy etc.)
            } else {
                pending.add(registration);
            }
        }
        return bean;
    }

    // --- SmartLifecycle: start after context refresh, stop BEFORE bean destruction ---

    @Override
    public void start() {
        running = true;
        for (Registration registration : pending) {
            subscribe(registration);
        }
        pending.clear(); // release references; late beans subscribe directly
    }

    @Override
    public void stop() {
        running = false;
        for (AutoCloseable subscription : subscriptions) {
            try {
                subscription.close();
            } catch (Exception ignored) {
            }
        }
        subscriptions.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void subscribe(Registration registration) {
        AppChainListener annotation = registration.annotation();
        String topic = annotation.topic().isEmpty() ? null : annotation.topic();
        Method method = registration.invocableMethod();
        method.setAccessible(true);
        AutoCloseable subscription = clientProvider.getObject()
                .subscribe(annotation.fromHeight(), topic, message -> {
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
}
