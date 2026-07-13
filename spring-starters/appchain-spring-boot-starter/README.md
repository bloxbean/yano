# Yano App-Chain Spring Boot Starter

Spring Boot auto-configuration for the Yano app-chain client SDK.

This module provides:

- auto-configured `AppChainClient`
- `AppChainTemplate`
- `@AppChainListener(topic = "...")` for SSE consumption

It is client-side sugar over `appchain-client`; it does not run a Yano node.

See also:

- [App-chain client README](../../appchain/appchain-client/README.md)
- [ADR-006 E1.4](../../adr/app-layer/006-appchain-enterprise-extensions-and-zk.md)

## Configuration

```properties
yano.appchain.client.base-url=http://localhost:7070/api/v1
yano.appchain.client.chain-id=orders-chain
yano.appchain.client.api-key=secret
yano.appchain.client.connect-timeout-seconds=10
```

`chain-id` is optional when the target node hosts exactly one app chain.
`api-key` is only needed when the node enables app-chain REST API-key auth.

## Template Usage

```java
import com.bloxbean.cardano.yano.appchain.spring.AppChainTemplate;
import org.springframework.stereotype.Service;

@Service
class Orders {
    private final AppChainTemplate appChain;

    Orders(AppChainTemplate appChain) {
        this.appChain = appChain;
    }

    String submit(String orderJson) {
        return appChain.send("orders", orderJson);
    }
}
```

## Listener Usage

```java
import com.bloxbean.cardano.yano.appchain.client.AppChainClient;
import com.bloxbean.cardano.yano.appchain.spring.AppChainListener;
import org.springframework.stereotype.Component;

@Component
class OrderListener {

    @AppChainListener(topic = "orders")
    void onOrder(AppChainClient.StreamedMessage message) {
        System.out.println(message.messageId());
    }
}
```

Listeners take exactly one parameter: `AppChainClient.StreamedMessage` (the full
envelope), `byte[]` (the raw body), or `String` (the body as UTF-8). An
unsupported signature fails fast at application startup.

## Proof Verification

```java
var proof = appChain.verifiedMessageProof(messageIdHex);
```

`verifiedProof(...)` and `verifiedMessageProof(...)` throw if the node returns a
proof that fails local MPF verification.

## Test

```bash
./gradlew :appchain-spring-boot-starter:test
```

## Notes

- This starter does not configure or secure the Yano node.
- It is intended for application services that consume an already running Yano
  app-chain REST/SSE endpoint.
