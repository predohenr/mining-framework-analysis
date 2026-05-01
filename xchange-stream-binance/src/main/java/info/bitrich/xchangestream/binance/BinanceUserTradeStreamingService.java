package info.bitrich.xchangestream.binance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.spec.PKCS8EncodedKeySpec;
import io.reactivex.rxjava3.core.Completable;
import org.knowm.xchange.binance.dto.trade.TimeInForce;
import org.knowm.xchange.binance.dto.trade.OrderType;
import java.util.regex.Pattern;
import java.io.IOException;
import io.reactivex.rxjava3.disposables.Disposable;
import org.slf4j.LoggerFactory;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import java.nio.charset.Charset;
import info.bitrich.xchangestream.service.netty.WebSocketClientCompressionAllowClientNoContextAndServerNoContextHandler;
import java.util.Base64;
import io.netty.handler.codec.http.websocketx.extensions.WebSocketClientExtensionHandler;
import java.time.Duration;
import org.knowm.xchange.binance.dto.BinanceException;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import org.knowm.xchange.dto.trade.LimitOrder;
import info.bitrich.xchangestream.binance.dto.trade.*;
import static info.bitrich.xchangestream.binance.dto.trade.BinanceWebsocketOrderCancelAndReplacePayload.CancelReplaceMode.STOP_ON_FAILURE;
import org.knowm.xchange.binance.dto.trade.BinanceCancelOrderParams;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import java.security.Security;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import java.nio.charset.StandardCharsets;
import org.knowm.xchange.binance.BinanceAdapters;
import org.bouncycastle.crypto.Signer;
import lombok.Getter;
import org.bouncycastle.crypto.params.AsymmetricKeyParameter;
import info.bitrich.xchangestream.service.netty.StreamingObjectMapperHelper;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.CompletableSource;
import org.bouncycastle.crypto.util.PrivateKeyFactory;
import org.knowm.xchange.dto.trade.MarketOrder;
import info.bitrich.xchangestream.service.netty.JsonNettyStreamingService;
import org.slf4j.Logger;

public class BinanceUserTradeStreamingService extends JsonNettyStreamingService {

  private static final Logger LOG = LoggerFactory.getLogger(BinanceUserTradeStreamingService.class);
  private static final Pattern p = Pattern.compile("[a-z.]+|\\d+");
  CompositeDisposable compositeDisposable = new CompositeDisposable();
  @Getter private boolean authorized = false;
  private String signature = "";
  Charset charSet = StandardCharsets.UTF_8;
  private final String apiKey;
  private final String privateKey;
  private Disposable loginDisposable;

  public BinanceUserTradeStreamingService(String apiUrl, String apiKey, String privateKey) {
      super(apiUrl,65536, Duration.ofSeconds(1), Duration.ofMillis(500), 15);
    this.apiKey = apiKey;
    this.privateKey = privateKey;
  }

  @Override
  public Completable connect() {
    Completable conn = super.connect();
    return conn.andThen(
        (CompletableSource)
            (completable) -> {
              login();
              Disposable disposable =
                  subscribeDisconnect()
                      .subscribe(
                          obj -> {
                            authorized = false;
                            signature = "";
                          });
              compositeDisposable.add(disposable);
              completable.onComplete();
            });
  }

  @Override
  public Completable disconnect() {
    compositeDisposable.dispose();
    return super.disconnect();
  }

  @Override
  public String getSubscriptionUniqueId(String channelName, Object... args) {
    return channelName;
  }

  public void login() {
    ObjectMapper mapper = StreamingObjectMapperHelper.getObjectMapper();
    Observable<Boolean> observable =
        this.subscribeChannel(String.valueOf(System.currentTimeMillis()), "session.logon")
            .flatMap(
                node -> {
                  TypeReference<BinanceWebsocketOrderResponse<BinanceWebsocketLoginResponse>>
                      typeReference = new TypeReference<>() {};
                  BinanceWebsocketOrderResponse<BinanceWebsocketLoginResponse> response =
                      mapper.treeToValue(node, typeReference);
                  if (response.getStatus() == 200) {
                    return Observable.just(true);
                  } else {
                    return Observable.error(
                        new BinanceException(
                            response.getError().getCode(), response.getError().getMsg()));
                  }
                });
    loginDisposable =
        observable
            .firstElement()
            .doOnError(error -> LOG.error("Login error", error))
            .subscribe(
                loginResult -> {
                  LOG.info("Successfully authorized to BinanceUserTradeStreamingService");
                  authorized = true;
                });
  }

  public String signPayload(String payload) throws Exception {
    Security.addProvider(new BouncyCastleProvider());
    byte[] decodePrivateKey = Base64.getDecoder().decode(privateKey.getBytes(charSet));
    PKCS8EncodedKeySpec pkcs8EncodedKeySpec = new PKCS8EncodedKeySpec(decodePrivateKey);
    PrivateKeyInfo instancePrivate = PrivateKeyInfo.getInstance(pkcs8EncodedKeySpec.getEncoded());
    AsymmetricKeyParameter keyPrivate = PrivateKeyFactory.createKey(instancePrivate);
    Signer signer = new Ed25519Signer();
    signer.init(true, keyPrivate);
    var payloadBytes = payload.getBytes(charSet);
    signer.update(payloadBytes, 0, payloadBytes.length);
    byte[] signature = signer.generateSignature();
    return new String(Base64.getEncoder().encode(signature));
  }

  @Override
  public void messageHandler(String message) {
    super.messageHandler(message);
  }

  @Override
  public String getUnsubscribeMessage(String channelName, Object... args) throws IOException {
    return null;
  }

  @Override
  protected String getChannelNameFromMessage(JsonNode message) throws IOException {

    return message.get("id").asText();
  }

  @Override
  public String getSubscribeMessage(String channelName, Object... args) throws IOException {
    String method = args[0].toString();
    switch (method) {
      case "session.logon":
        { // login
          long timestamp = System.currentTimeMillis();
          try {
            String loginPayload = "apiKey=" + apiKey + "&timestamp=" + timestamp;
            signature = signPayload(loginPayload);
            BinanceWebsocketLoginPayloadWithSignature loginPayloadWithSignature =
                new BinanceWebsocketLoginPayloadWithSignature(apiKey, signature, timestamp);
            BinanceWebsocketPayload<BinanceWebsocketLoginPayloadWithSignature> payload =
                new BinanceWebsocketPayload<>(
                    channelName, "session.logon", loginPayloadWithSignature);
            return objectMapper.writeValueAsString(payload);
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        }
      case "order.place":
        {
          BinanceWebsocketPlaceOrderPayload orderPayload = null;
          if (args[1] instanceof MarketOrder) {
            MarketOrder marketOrder = (MarketOrder) args[1];
            orderPayload = BinanceStreamingAdapters.adaptPlaceOrder(marketOrder);
          } else if (args[1] instanceof LimitOrder) {
            LimitOrder limitOrder = (LimitOrder) args[1];
            orderPayload = BinanceStreamingAdapters.adaptPlaceOrder(limitOrder);
          }
          assert orderPayload != null;
          BinanceWebsocketPayload<BinanceWebsocketPlaceOrderPayload> payload =
              new BinanceWebsocketPayload<>(channelName, method, orderPayload);
          return objectMapper.writeValueAsString(payload);
        }
      case "order.modify":
        {
          LimitOrder limitOrder = (LimitOrder) args[1];
          BinanceWebsocketOrderAmendPayload amendOrderPayload =
              BinanceStreamingAdapters.adaptAmendOrder(limitOrder);
          assert amendOrderPayload != null;
          BinanceWebsocketPayload<BinanceWebsocketOrderAmendPayload> payload =
              new BinanceWebsocketPayload<>(channelName, method, amendOrderPayload);
          return objectMapper.writeValueAsString(payload);
        }
      case "order.cancel":
        {
          BinanceCancelOrderParams params = (BinanceCancelOrderParams) args[1];
          Long orderId = null;
          if (params.getOrderId() != null && !params.getOrderId().isEmpty()) {
            orderId = Long.valueOf(params.getOrderId());
          }
            BinanceWebsocketOrderCancelPayload cancelOrderPayload =
                    BinanceWebsocketOrderCancelPayload.builder()
                            .symbol(BinanceAdapters.toSymbol(params.getInstrument()))
                            .orderId(orderId)
                            .origClientOrderId(params.getUserReference())
                            .newClientOrderId(params.getUserReference())
                            .timestamp(System.currentTimeMillis())
                            .build();
          BinanceWebsocketPayload<BinanceWebsocketOrderCancelPayload> payload =
              new BinanceWebsocketPayload<>(channelName, method, cancelOrderPayload);
          return objectMapper.writeValueAsString(payload);
        }
        case "order.cancelReplace":
        {
            LimitOrder limitOrder = (LimitOrder) args[1];
            BinanceCancelOrderParams params = (BinanceCancelOrderParams) args[2];
            Long cancelOrderId = null;
            if (params.getOrderId() != null && !params.getOrderId().isEmpty()) {
                cancelOrderId = Long.valueOf(params.getOrderId());
            }
            TimeInForce tif =
                    BinanceAdapters.getOrderFlag(limitOrder, TimeInForce.class).orElse(TimeInForce.GTC);
            BinanceWebsocketOrderCancelAndReplacePayload orderCancelAndReplacePayload =
                    BinanceWebsocketOrderCancelAndReplacePayload.builder()
                            .symbol(BinanceAdapters.toSymbol(params.getInstrument()))
                            .cancelOrderId(cancelOrderId)
                            .cancelOrigClientOrderId(params.getUserReference())
                            .symbol(BinanceAdapters.toSymbol(limitOrder.getInstrument()))
                            .side(BinanceAdapters.convert(limitOrder.getType()))
                            .newClientOrderId(limitOrder.getUserReference())
                            .type(OrderType.LIMIT)
                            .price(limitOrder.getLimitPrice())
                            .quantity(limitOrder.getOriginalAmount())
                            .timeInForce(tif)
                            .cancelReplaceMode(STOP_ON_FAILURE)
                            .timestamp(System.currentTimeMillis())
                            .build();
            BinanceWebsocketPayload<BinanceWebsocketOrderCancelAndReplacePayload> payload =
                    new BinanceWebsocketPayload<>(channelName, method, orderCancelAndReplacePayload);
            return objectMapper.writeValueAsString(payload);
        }
      default:
        return null;
    }
  }

  @Override
  protected WebSocketClientExtensionHandler getWebSocketClientExtensionHandler() {
    return WebSocketClientCompressionAllowClientNoContextAndServerNoContextHandler.INSTANCE;
  }
}
