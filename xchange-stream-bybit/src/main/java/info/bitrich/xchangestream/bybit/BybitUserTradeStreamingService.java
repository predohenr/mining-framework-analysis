package info.bitrich.xchangestream.bybit;
import io.reactivex.rxjava3.core.CompletableSource;
import org.knowm.xchange.service.BaseParamsDigest;
import io.reactivex.rxjava3.core.Completable;
import dto.trade.BybitStreamBatchCancelOrdersPayload;
import dto.BybitSubscribeMessage;
import org.knowm.xchange.ExchangeSpecification;
import org.knowm.xchange.bybit.dto.trade.BybitAmendOrderPayload;
import java.util.concurrent.TimeUnit;
import org.knowm.xchange.dto.trade.LimitOrder;
import dto.trade.BybitOrderMessage.BybitHeader;
import io.netty.handler.codec.http.websocketx.extensions.WebSocketClientExtensionHandler;
import info.bitrich.xchangestream.service.netty.WebSocketClientCompressionAllowClientNoContextHandler;
import lombok.Getter;
import org.knowm.xchange.dto.trade.MarketOrder;
import org.knowm.xchange.bybit.dto.trade.BybitCancelOrderPayload;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import io.reactivex.rxjava3.core.Observable;
import com.fasterxml.jackson.core.type.TypeReference;
import dto.trade.BybitStreamBatchAmendOrdersPayload;
import org.slf4j.LoggerFactory;
import org.knowm.xchange.bybit.dto.trade.BybitCancelOrderParams;
import dto.trade.BybitStreamBatchCancelOrdersPayload.BybitStreamBatchCancelOrderPayload;
import info.bitrich.xchangestream.service.netty.JsonNettyStreamingService;
import dto.trade.BybitOrderMessage;
import org.knowm.xchange.exceptions.ExchangeException;
import org.knowm.xchange.bybit.dto.BybitCategory;
import io.reactivex.rxjava3.disposables.Disposable;
import org.knowm.xchange.bybit.dto.trade.BybitPlaceOrderPayload;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.ArrayList;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import static info.bitrich.xchangestream.bybit.BybitStreamAdapters.adaptBatchAmendOrder;
import java.io.IOException;
import java.time.Duration;
import javax.crypto.spec.SecretKeySpec;
import java.util.List;
import static org.knowm.xchange.bybit.BybitAdapters.*;
import static org.knowm.xchange.utils.DigestUtils.bytesToHex;
import java.util.stream.Stream;
import java.security.InvalidKeyException;
import dto.trade.BybitOrderMessage.Header;
import javax.crypto.Mac;
import java.util.stream.Collectors;

public class BybitUserTradeStreamingService extends JsonNettyStreamingService {

  private static final Logger LOG = LoggerFactory.getLogger(BybitUserTradeStreamingService.class);
  private final ExchangeSpecification spec;

  @Override
  public String getSubscriptionUniqueId(String channelName, Object... args) {
    return args[1].toString();
  }
  @Override
  public Completable connect() {
    Completable conn = super.connect();
    return conn.andThen(
        (CompletableSource)
            (completable) -> {
              LOG.info("Connect to BybitUserTradeStream with auth");
              login();
              pingPongDisconnectIfConnected();
              pingPongSubscription =
                  pingPongSrc.subscribe(o -> this.sendMessage("{\"op\":\"ping\"}"));
              completable.onComplete();
            });
  }
  private Disposable pingPongSubscription;
  public static final String BATCH_ORDER_CANCEL = "order.cancel-batch";
  public void pingPongDisconnectIfConnected() {
    if (pingPongSubscription != null && !pingPongSubscription.isDisposed()) {
      pingPongSubscription.dispose();
    }
  }
  @Getter private boolean isAuthorized = false;
  @Override
  public void messageHandler(String message) {
    LOG.debug("Received message: {}", message);
    JsonNode jsonNode;
    try {
      jsonNode = objectMapper.readTree(message);
    } catch (IOException e) {
      LOG.error("Error parsing incoming message to JSON: {}", message);
      return;
    }
    if (jsonNode.has("op")) {
      switch (jsonNode.get("op").asText()) {
        case "auth":
          {
            if (jsonNode.has("retMsg") && jsonNode.get("retMsg").asText().equals("OK")) {
              connId = jsonNode.get("connId").asText();
              isAuthorized = true;
              LOG.info("Successfully authenticated to trade URI");
              return;
            } else {
              throw new ExchangeException(jsonNode.get("retMsg").asText());
            }
          }
        case "pong":
          {
            LOG.debug("Received PONG message: {}", message);
            return;
          }
      }
    }
    handleMessage(jsonNode);
  }
  public static final String BATCH_ORDER_CHANGE = "order.amend-batch";
  @Override
  protected WebSocketClientExtensionHandler getWebSocketClientExtensionHandler() {
    return WebSocketClientCompressionAllowClientNoContextHandler.INSTANCE;
  }
  @Override
  public void sendMessage(String message) {
    if (message != null) {
      super.sendMessage(message);
    }
  }
  public static final String BATCH_ORDER_CREATE = "order.create-batch";
  private String connId;
  public static final String ORDER_CREATE = "order.create";
  @Override
  protected String getChannelNameFromMessage(JsonNode message) throws IOException {
    return message.get("reqId").asText();
  }
  private final Observable<Long> pingPongSrc = Observable.interval(15, 20, TimeUnit.SECONDS);
  @Override
  public String getSubscribeMessage(String channelName, Object... args) throws IOException {
    BybitHeader header = new BybitHeader(String.valueOf(System.currentTimeMillis()), "5000", "");
    BybitCategory category = (BybitCategory) args[2];
    List<BybitPlaceOrderPayload> bybitPlaceOrderPayload = null;
    BybitOrderMessage<?> bybitOrderMessage = null;
    String reqId = args[1].toString();
    switch (channelName) {
      case ORDER_CREATE:
        {
          if (args[0] instanceof LimitOrder) {
            LimitOrder limitOrder = (LimitOrder) args[0];
            bybitPlaceOrderPayload = List.of(adaptLimitOrder(limitOrder, category));
          } else if (args[0] instanceof MarketOrder) {
            MarketOrder marketOrders = (MarketOrder) args[0];
            bybitPlaceOrderPayload = List.of(adaptMarketOrder(marketOrders, category));
          }
          bybitOrderMessage =
              new BybitOrderMessage<>(reqId, header, channelName, bybitPlaceOrderPayload);
          break;
        }
      case ORDER_CHANGE:
        {
          LimitOrder limitOrder = (LimitOrder) args[0];
          List<BybitAmendOrderPayload> bybitAmendOrderPayload =
              List.of(adaptChangeOrder(limitOrder, category));
          bybitOrderMessage =
              new BybitOrderMessage<>(reqId, header, channelName, bybitAmendOrderPayload);
          break;
        }
      case BATCH_ORDER_CHANGE:
        {
          LimitOrder[] limitOrders =
              objectMapper.readValue(args[0].toString(), new TypeReference<>() {});
          List<BybitStreamBatchAmendOrdersPayload> bybitStreamBatchAmendOrdersPayload =
              List.of(adaptBatchAmendOrder(limitOrders, category));
          bybitOrderMessage =
              new BybitOrderMessage<>(
                  reqId, header, channelName, bybitStreamBatchAmendOrdersPayload);
          break;
        }
      case ORDER_CANCEL:
        {
          BybitCancelOrderParams params = (BybitCancelOrderParams) args[0];
          List<BybitCancelOrderPayload> bybitCancelOrderPayload =
              List.of(
                  new BybitCancelOrderPayload(
                      category,
                      convertToBybitSymbol(params.getInstrument()),
                      params.getOrderId(),
                      params.getUserReference()));
          bybitOrderMessage =
              new BybitOrderMessage<>(reqId, header, channelName, bybitCancelOrderPayload);
          break;
        }
      case BATCH_ORDER_CANCEL:
        {
          BybitCancelOrderParams[] params =
              objectMapper.readValue(args[0].toString(), new TypeReference<>() {});
          List<BybitStreamBatchCancelOrderPayload> bybitBatchCancelOrderPayload = new ArrayList<>();
          for (BybitCancelOrderParams param : params) {
            bybitBatchCancelOrderPayload.add(
                new BybitStreamBatchCancelOrderPayload(
                    convertToBybitSymbol(param.getInstrument()),
                    param.getOrderId(),
                    param.getUserReference()));
          }
          List<BybitStreamBatchCancelOrdersPayload> bybitBatchCancelOrdersPayload =
              List.of(
                  new BybitStreamBatchCancelOrdersPayload(category, bybitBatchCancelOrderPayload));
          bybitOrderMessage =
              new BybitOrderMessage<>(reqId, header, channelName, bybitBatchCancelOrdersPayload);
          break;
        }
    }
    return objectMapper.writeValueAsString(bybitOrderMessage);
  }
  public static final String ORDER_CANCEL = "order.cancel";
  @Override
  public String getUnsubscribeMessage(String channelName, Object... args) throws IOException {
    return null;
  }
  public static final String ORDER_CHANGE = "order.amend";
  private void login() {
    String key = spec.getApiKey();
    long expires = Instant.now().toEpochMilli() + 10000;
    String _val = "GET/realtime" + expires;
    try {
      Mac mac = Mac.getInstance(BaseParamsDigest.HMAC_SHA_256);
      final SecretKey secretKey =
          new SecretKeySpec(
              spec.getSecretKey().getBytes(StandardCharsets.UTF_8), BaseParamsDigest.HMAC_SHA_256);
      mac.init(secretKey);
      String signature = bytesToHex(mac.doFinal(_val.getBytes(StandardCharsets.UTF_8)));
      List<String> args =
          Stream.of(key, String.valueOf(expires), signature).collect(Collectors.toList());
      String message = objectMapper.writeValueAsString(new BybitSubscribeMessage("auth", args));
      this.sendMessage(message);
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new ExchangeException("Invalid API secret", e);
    } catch (JsonProcessingException e) {
      throw new RuntimeException(e);
    }
  }
  @Override
  public Completable connect() {
    Completable conn = super.connect();
    return conn.andThen(
        (CompletableSource)
            (completable) -> {
              LOG.info("Connect to BybitUserTradeStream with auth");
              login();
              completable.onComplete();
            });
  }
  @Override
  public void messageHandler(String message) {
    LOG.debug("Received message: {}", message);
    JsonNode jsonNode;
    try {
      jsonNode = objectMapper.readTree(message);
    } catch (IOException e) {
      LOG.error("Error parsing incoming message to JSON: {}", message);
      return;
    }
    if (jsonNode.has("op") && jsonNode.get("op").asText().equals("auth")) {
      if (jsonNode.has("retMsg") && jsonNode.get("retMsg").asText().equals("OK")) {
        connId = jsonNode.get("connId").asText();
        isAuthorized = true;
        LOG.debug("Successfully authenticated to trade URI");
        return;
      } else {
        throw new ExchangeException(jsonNode.get("retMsg").asText());
      }
    }
    handleMessage(jsonNode);
  }
  public BybitUserTradeStreamingService(String apiUrl, ExchangeSpecification spec) {
      super(apiUrl,65536, Duration.ofSeconds(1), Duration.ofMillis(500), 15);
    this.spec = spec;
  }
  @Override
  public String getSubscribeMessage(String channelName, Object... args) throws IOException {
    Header header = new Header(String.valueOf(System.currentTimeMillis()), "5000", "");
    BybitCategory category = (BybitCategory) args[2];
    List<BybitPlaceOrderPayload> bybitPlaceOrderPayload = null;
    BybitOrderMessage<?> bybitOrderMessage = null;
    String reqId = args[1].toString();
    switch (channelName) {
      case ORDER_CREATE:
        {
          if (args[0] instanceof LimitOrder) {
            LimitOrder limitOrder = (LimitOrder) args[0];
            bybitPlaceOrderPayload = List.of(adaptLimitOrder(limitOrder, category));
          } else if (args[0] instanceof MarketOrder) {
            MarketOrder marketOrders = (MarketOrder) args[0];
            bybitPlaceOrderPayload = List.of(adaptMarketOrder(marketOrders, category));
          }
          bybitOrderMessage =
              new BybitOrderMessage<>(reqId, header, channelName, bybitPlaceOrderPayload);
          break;
        }
      case ORDER_CHANGE:
        {
          LimitOrder limitOrder = (LimitOrder) args[0];
          List<BybitAmendOrderPayload> bybitAmendOrderPayload =
              List.of(adaptChangeOrder(limitOrder, category));
          bybitOrderMessage =
              new BybitOrderMessage<>(reqId, header, channelName, bybitAmendOrderPayload);
          break;
        }
      case BATCH_ORDER_CHANGE:
        {
          LimitOrder[] limitOrders =
              objectMapper.readValue(args[0].toString(), new TypeReference<>() {});
          List<BybitStreamBatchAmendOrdersPayload> bybitStreamBatchAmendOrdersPayload =
              List.of(adaptBatchAmendOrder(limitOrders, category));
          bybitOrderMessage =
              new BybitOrderMessage<>(
                  reqId, header, channelName, bybitStreamBatchAmendOrdersPayload);
          break;
        }
      case ORDER_CANCEL:
        {
          BybitCancelOrderParams params = (BybitCancelOrderParams) args[0];
          List<BybitCancelOrderPayload> bybitCancelOrderPayload =
              List.of(
                  new BybitCancelOrderPayload(
                      category,
                      convertToBybitSymbol(params.getInstrument()),
                      params.getOrderId(),
                      params.getUserReference()));
          bybitOrderMessage =
              new BybitOrderMessage<>(reqId, header, channelName, bybitCancelOrderPayload);
          break;
        }
    }
    return objectMapper.writeValueAsString(bybitOrderMessage);
  }
}
