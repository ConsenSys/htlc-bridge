package net.consensys.htlcbridge.relayer.api;

import io.vertx.core.Future;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import net.consensys.htlcbridge.relayer.DestinationBlockchainObserver;
import net.consensys.htlcbridge.relayer.Relayer;
import net.consensys.htlcbridge.relayer.RelayerConfig;
import net.consensys.htlcbridge.relayer.SourceBlockchainObserver;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class RestAPI {
  private static final Logger LOG = LogManager.getLogger(RestAPI.class);

  Relayer relayer;

  public RestAPI(Relayer relayer) {
    this.relayer = relayer;
  }

  public void createAPI(Router router) {
    router.route("/conf/slot*").handler(BodyHandler.create());
    router.post("/conf/slot").handler(this::configSlot);
    router.get("/conf/slot").handler(this::getConfigSlot);
    router.route("/conf/all/*").handler(BodyHandler.create());
    router.post("/conf/all").handler(this::configSlot);
    router.get("/conf/all").handler(this::getConfigSlot);

    // TODO block confirmations
    // TODO block periods

    router
        .get("/ver")
        // this handler will ensure that the response is serialized to json
        // the content type is set to "application/json"
        .respond(
            ctx -> Future.succeededFuture(new JsonObject().put("version", "1")));

  }


  private void configSlot(RoutingContext routingContext) {
    String body = routingContext.getBodyAsString();
    SlotConfig conf;
    try {
      conf = Json.decodeValue(body, SlotConfig.class);
    } catch (Throwable th) {
      LOG.error("Config slot: JSON format issue: {}", th.toString());
      routingContext.response()
          .setStatusCode(400)
          .putHeader("content-type", "application/json; charset=utf-8")
          .end(new JsonObject().put("Issue", "JSON Encoding Error").toString());
      return;
    }

    int numRelayers = conf.numRelayers;
    int relayerOffset = conf.relayerOffset;
    if (numRelayers <= 0) {
      LOG.error("Config slot: Ignoring invalid request (numRelayers negative): Num Relayers: {}, Relayer Offset: {}", numRelayers, relayerOffset);
      routingContext.response()
          .setStatusCode(400)
          .putHeader("content-type", "application/json; charset=utf-8")
          .end(new JsonObject().put("Issue", "numRelayers must be a greater than zero").toString());
      return;
    }
    if (relayerOffset < 0 || relayerOffset >= numRelayers) {
      LOG.error("Config slot: Ignoring invalid request (relayOffset invalid): Num Relayers: {}, Relayer Offset: {}", numRelayers, relayerOffset);
      routingContext.response()
          .setStatusCode(400)
          .putHeader("content-type", "application/json; charset=utf-8")
          .end(new JsonObject().put("Issue", "relayOffset must be zero or more and less than numRelayers").toString());
      return;
    }

    LOG.info("Config slot: Num Relayers: {}, Relayer Offset: {}", numRelayers, relayerOffset);
//    this.relayer.sourceBlockchainObserver.setRelayers(numRelayers, relayerOffset);
//    this.relayer.destBlockchainObserver.setRelayers(numRelayers, relayerOffset);

    routingContext.response()
        .setStatusCode(201)
        .putHeader("content-type", "application/json; charset=utf-8")
        .end(Json.encodePrettily(conf));
  }

  private void getConfigSlot(RoutingContext routingContext) {
    int numRelayers = 1; // this.relayer.sourceBlockchainObserver.getNumRelayers();
    int relayerOffset = 0; //this.relayer.sourceBlockchainObserver.getRelayerOffset();
    SlotConfig conf = new SlotConfig(numRelayers, relayerOffset);

    routingContext.response()
        .setStatusCode(201)
        .putHeader("content-type", "application/json; charset=utf-8")
        .end(Json.encodePrettily(conf));
  }


  private void configAll(RoutingContext routingContext) {
    String body = routingContext.getBodyAsString();
    RelayerConfig config;
    try {
      config = Json.decodeValue(body, RelayerConfig.class);
    } catch (Throwable th) {
      LOG.error("Config all: JSON format issue: {}", th.toString());
      routingContext.response()
          .setStatusCode(400)
          .putHeader("content-type", "application/json; charset=utf-8")
          .end(new JsonObject().put("Issue", "JSON Encoding Error").toString());
      return;
    }

//    if (this.relayer.sourceBlockchainObserver != null) {
//      LOG.error("Config all: Configuration already set");
//      routingContext.response()
//          .setStatusCode(400)
//          .putHeader("content-type", "application/json; charset=utf-8")
//          .end(new JsonObject().put("Issue", "configuration already set").toString());
//      return;
//    }

    try {
      relayer.sourceBlockchainObserver = new SourceBlockchainObserver(
          config.sourceBcUri, config.sourceTransferContract, config.sourceBlockPeriod, config.sourceConfirmations,
          config.sourceRelayerPKey, config.sourceRetries, config.sourceBcId, config.sourceGasStrategy,
          config.destBcUri, config.destTransferContract, config.destBlockPeriod, config.destConfirmations,
          config.destRelayerPKey, config.destRetries, config.destBcId, config.destGasStrategy);
      relayer.sourceBlockPeriod = config.sourceBlockPeriod;

      relayer.destBlockchainObserver = new DestinationBlockchainObserver(
          config.sourceBcUri, config.sourceTransferContract, config.sourceBlockPeriod, config.sourceConfirmations,
          config.sourceRelayerPKey, config.sourceRetries, config.sourceBcId, config.sourceGasStrategy,
          config.destBcUri, config.destTransferContract, config.destBlockPeriod, config.destConfirmations,
          config.destRelayerPKey, config.destRetries, config.destBcId, config.destGasStrategy);
      relayer.destBlockPeriod = config.destBlockPeriod;
    }
    catch (Exception ex) {
      relayer.resetConfig();
      LOG.error("Config all: Exception while setting configuration: {}", ex.getMessage());
      routingContext.response()
          .setStatusCode(400)
          .putHeader("content-type", "application/json; charset=utf-8")
          .end(new JsonObject().put("Issue", "exception while setting configuration").toString());
      return;
    }

    this.relayer.conf = config;
    LOG.info("Config all: Configured");

    routingContext.response()
        .setStatusCode(201)
        .putHeader("content-type", "application/json; charset=utf-8")
        .end(Json.encodePrettily(config));
  }

  private void getConfigAll(RoutingContext routingContext) {

    routingContext.response()
        .setStatusCode(201)
        .putHeader("content-type", "application/json; charset=utf-8")
        .end(Json.encodePrettily(this.relayer.conf));
  }


}
