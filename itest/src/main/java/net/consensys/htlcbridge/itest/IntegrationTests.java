package net.consensys.htlcbridge.itest;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.consensys.htlcbridge.admin.commands.AuthoriseERC20ForReceiver;
import net.consensys.htlcbridge.admin.commands.AuthoriseERC20ForTransfer;
import net.consensys.htlcbridge.admin.commands.DeployERC20Contract;
import net.consensys.htlcbridge.admin.commands.DeployTransferContract;
import net.consensys.htlcbridge.common.DynamicGasProvider;
import net.consensys.htlcbridge.common.KeyPairGen;
import net.consensys.htlcbridge.common.PRNG;
import net.consensys.htlcbridge.common.RevertReason;
import net.consensys.htlcbridge.openzeppelin.soliditywrappers.ERC20PresetFixedSupply;
import net.consensys.htlcbridge.relayer.Relayer;
import net.consensys.htlcbridge.relayer.RelayerConfig;
import net.consensys.htlcbridge.transfer.CommitmentCalculator;
import net.consensys.htlcbridge.transfer.ReceiverInfo;
import net.consensys.htlcbridge.transfer.TransferState;
import net.consensys.htlcbridge.transfer.soliditywrappers.Erc20HtlcTransfer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.exceptions.TransactionException;
import org.web3j.protocol.http.HttpService;
import org.web3j.tuples.generated.Tuple7;
import org.web3j.tx.RawTransactionManager;
import org.web3j.tx.TransactionManager;
import org.web3j.tx.gas.ContractGasProvider;
import org.web3j.tx.gas.DefaultGasProvider;
import org.web3j.tx.gas.StaticGasProvider;
import static org.junit.Assert.assertEquals;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.math.BigInteger;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;

public class IntegrationTests {
  private static final Logger LOG = LogManager.getLogger(IntegrationTests.class);

  public static final String MAINNET_BLOCKCHAIN_URI = "http://127.0.0.1:8400/";
  public static final String MAINNET_BLOCKCHAIN_ID = "40";
  public static final int MAINNET_BLOCK_PERIOD = 4000;
  public static final int MAINNET_CONFIRMATIONS = 3;

  public static final String SIDECHAIN_BLOCKCHAIN_URI = "http://127.0.0.1:8310/";
  public static final String SIDECHAIN_BLOCKCHAIN_ID = "31";
  public static final int SIDECHAIN_BLOCK_PERIOD = 2000;
  public static final int SIDECHAIN_CONFIRMATIONS = 1;

  public static final int API_PORT = 8080;


  public static final String TOK1_NAME = "Token 1";
  public static final String TOK1_SYMBOL = "Tok1";
  public static final long TOK1_TOTAL_SUPPLY = 100000;

  public static final String TOK2_NAME = "Token 2";
  public static final String TOK2_SYMBOL = "Tok2";
  public static final long TOK2_TOTAL_SUPPLY = 100000;

  // Addresses of transfer and receiver contracts on sidechain and MainNet.
  String transferSidechain;
  String transferMainNet;

  public static final int NUM_USERS = 3;
  public static final int NUM_TRANFERS = 10;
  String[] userPKeys;
  String[] userAddresses;

  public static final long USER_TOK_1_BASE_STARTING_BALANCE = 100;
  public static final long USER_TOK_2_BASE_STARTING_BALANCE = 80;

  public static final boolean TOKEN2 = false;

  public IntegrationTests() {

  }

  public void runTest() throws Exception {
    String bankerPKey = new KeyPairGen().generateKeyPairGetPrivateKey();
    String relayer1PKey = new KeyPairGen().generateKeyPairGetPrivateKey();
//    String relayer2PKey = new KeyPairGen().generateKeyPairGetPrivateKey();

    this.userPKeys = new String[NUM_USERS];
    this.userAddresses = new String[NUM_USERS];
    for (int i=0; i<this.userPKeys.length; i++) {
      this.userPKeys[i] = new KeyPairGen().generateKeyPairGetPrivateKey();
      this.userAddresses[i] = Credentials.create(this.userPKeys[i]).getAddress();
    }

    LOG.info("Set-up MainNet: Deploy ERC20 contracts and give users some tokens");
    // An entity other than the relayers is the deplyer of the ERC 20s on MainNet.
    // Relayer 1 is the deployer of the ERC 20s on the Sidechain.
    String erc20Tok1MainNet = deployErc20Contract(true, TOK1_NAME, TOK1_SYMBOL, TOK1_TOTAL_SUPPLY, bankerPKey);
    String erc20Tok2MainNet = null;
    if (TOKEN2) {
      erc20Tok2MainNet = deployErc20Contract(true, TOK2_NAME, TOK2_SYMBOL, TOK2_TOTAL_SUPPLY, bankerPKey);
    }
    for (int i = 0; i < NUM_USERS; i++) {
      long startingBalance = USER_TOK_1_BASE_STARTING_BALANCE + i;
      LOG.info("Give user {}: {} of {}, MainNet contract: {}", this.userAddresses[i], startingBalance, TOK1_NAME, erc20Tok1MainNet);
      transferErc20Tokens(true, erc20Tok1MainNet, bankerPKey, this.userAddresses[i], startingBalance);
      BigInteger balance = getBalanceErc20Tokens(true, erc20Tok1MainNet, this.userPKeys[i]);
      assertEquals(balance.longValue(), startingBalance);
    }
    if (TOKEN2) {
      for (int i = 0; i < NUM_USERS; i++) {
        long startingBalance = USER_TOK_2_BASE_STARTING_BALANCE + i;
        LOG.info("Give user {}: {} of {}, MainNet contract: {}", this.userAddresses[i], startingBalance, TOK2_NAME, erc20Tok2MainNet);
        transferErc20Tokens(true, erc20Tok2MainNet, bankerPKey, this.userAddresses[i], startingBalance);
        BigInteger balance = getBalanceErc20Tokens(true, erc20Tok2MainNet, this.userPKeys[i]);
        assertEquals(balance.longValue(), startingBalance);
      }
    }


    LOG.info("Deploy ERC20 contracts and Receiver and Transfer contract on Sidechain");
    // Relayer 1 is the deployer of the ERC 20s on the Sidechain.
    String erc20Tok1Sidechain = deployErc20Contract(false, TOK1_NAME, TOK1_SYMBOL, TOK1_TOTAL_SUPPLY, relayer1PKey);
    String erc20Tok2Sidechain = null;
    if (TOKEN2) {
      erc20Tok2Sidechain = deployErc20Contract(false, TOK2_NAME, TOK2_SYMBOL, TOK2_TOTAL_SUPPLY, relayer1PKey);
    }
    long mainnetSourceTimeLock = 24 * 60 * 60; // 24 hours.
    long sidechainDestinationTimeLock = 12 * 60 * 60; // 12 hours.
    long mainnetDestinationTimeLock = 12 * 60 * 60; // 24 hours.
    long sidechainSourceTimeLock = 24 * 60 * 60; // 12 hours.
    this.transferSidechain = deployTransferContract(false, relayer1PKey, sidechainSourceTimeLock, sidechainDestinationTimeLock);

    LOG.info("Transfer all tokens on Sidechain to Receiver contract");
    transferErc20Tokens(false, erc20Tok1Sidechain, relayer1PKey, this.transferSidechain, TOK1_TOTAL_SUPPLY);
    if (TOKEN2) {
      transferErc20Tokens(false, erc20Tok2Sidechain, relayer1PKey, this.transferSidechain, TOK2_TOTAL_SUPPLY);
    }

    LOG.info("Deploy Transfer contract on MainNet");
    this.transferMainNet = deployTransferContract(true, relayer1PKey, mainnetSourceTimeLock, mainnetDestinationTimeLock);

    LOG.info("Add {} and {} to list of authorised ERC 20 contracts", TOK1_NAME, TOK2_NAME);
    // Always add tokens to receivers first.
    authoriseErc20TokensOnReceiver(false, transferSidechain, relayer1PKey, erc20Tok1Sidechain, erc20Tok1MainNet);
    authoriseErc20TokensOnReceiver(true, transferMainNet, relayer1PKey, erc20Tok1MainNet, erc20Tok1Sidechain);
    authoriseErc20TokensOnTransfer(false, transferSidechain, relayer1PKey, erc20Tok1Sidechain);
    authoriseErc20TokensOnTransfer(true, transferMainNet, relayer1PKey, erc20Tok1MainNet);
    if (TOKEN2) {
      authoriseErc20TokensOnReceiver(false, transferSidechain, relayer1PKey, erc20Tok2Sidechain, erc20Tok2MainNet);
      authoriseErc20TokensOnReceiver(true, transferMainNet, relayer1PKey, erc20Tok2MainNet, erc20Tok2Sidechain);
      authoriseErc20TokensOnTransfer(false, transferSidechain, relayer1PKey, erc20Tok2Sidechain);
      authoriseErc20TokensOnTransfer(true, transferMainNet, relayer1PKey, erc20Tok2MainNet);
    }


    LOG.info("Approve ERC20 transferfrom for Transfer contract on MainNet");
    for (int i = 0; i < NUM_USERS; i++) {
      approveErc20Tokens(true, erc20Tok1MainNet, this.userPKeys[i], transferMainNet, USER_TOK_1_BASE_STARTING_BALANCE + i);
    }

    LOG.info("Writing Relayer config file");
    writeRelayerConfigFile(true, relayer1PKey, 8080, mainnetSourceTimeLock, "relayer1.config");
    LOG.info("Launching MainNet to Sidechain Relayer");
    launchRelayer("relayer1.config");
//    this.relayerToSidechain.setRelayers(2, 0);

    for (int i = 0; i < NUM_USERS; i++) {
      int user = i;
      Executors.newSingleThreadExecutor().execute(new Runnable() {
        @Override
        public void run() {
          String userAddress = userAddresses[user];
          String userPKey = userPKeys[user];
          LOG.info("Started thread for user {}: {}", user, userAddress);
          try {
            byte[][] preimageSalts = new byte[NUM_TRANFERS][];
            byte[][] commitments = new byte[NUM_TRANFERS][];

            for (int i = 0; i < NUM_TRANFERS; i++) {
              BigInteger amountToTransfer = BigInteger.valueOf(i + 1);
              LOG.info("User {}: New Transfer {} ERC tokens ({}) to Sidechain", userAddress, i + 1, TOK1_NAME);
              byte[][] result = newTransfer(true, userPKey, erc20Tok1MainNet, amountToTransfer);
              preimageSalts[i] = result[0];
              commitments[i] = result[1];
            }

            for (int i = 0; i < NUM_TRANFERS; i++) {
              BigInteger amountToTransfer = BigInteger.valueOf(i + 1);
              // TODO wait for destination confirmations before posting the preimage. Otherwise,
              // an attacker could reorganise the destination blockchain, removing the transaction with the commitment.
              // If the user reveals the preimage, the dishonest relayer could use it to cash in on the source chain?
              waitForHtlcToBePostedToRelayer(false, commitments[i], userPKey);

              LOG.info("User {}: Checking deal matches what was committed to.", userAddress);
              ReceiverInfo receiverInfo = getDetailsFromReceiver(false, commitments[i], userPKey);
              LOG.info(" Receiver Info: {}", receiverInfo.toString());
              if (!amountToTransfer.equals(receiverInfo.getAmount())) {
                LOG.error(" Transfer Amount did not match: Expected: {}, Actual: {}", amountToTransfer, receiverInfo.getAmount());
                throw new Exception(" Transfer Amount did not match");
              }
              if (!userAddress.equalsIgnoreCase(receiverInfo.getRecipientAddress())) {
                LOG.error(" Recipient did not match: Expected: {}, Actual: {}", userAddress, receiverInfo.getRecipientAddress());
                throw new Exception(" Recipient did not match");
              }
              if (!erc20Tok1MainNet.equalsIgnoreCase(receiverInfo.getTokenContractOtherBlockchain())) {
                LOG.error(" Token did not match: Expected: {}, Actual: {}", erc20Tok1MainNet, receiverInfo.getTokenContractOtherBlockchain());
                throw new Exception(" Token did not match");
              }
              if (!receiverInfo.getState().equals(TransferState.OPEN)) {
                LOG.error(" Unexpected transfer state: Expected: {}, Actual: {}", TransferState.OPEN, receiverInfo.getState());
                throw new Exception(" Unexpected transfer state");
              }
              long now = System.currentTimeMillis() / 1000;
              if (now > receiverInfo.getTimeLock().longValue()) {
                LOG.error(" Transfer has timed out: Now: {}, TimeLock: {}", now, receiverInfo.getTimeLock());
                throw new Exception(" Transfer has timed out");
              }

              LOG.info(" Balances Before Transfer on Sidechain for Token {}: {}", TOK1_NAME, erc20Tok1Sidechain);
              LOG.info("  Receiver Contract: {}", getBalanceErc20Tokens(false, erc20Tok1Sidechain, relayer1PKey, transferSidechain));
              LOG.info("  Relayer1: {}", getBalanceErc20Tokens(false, erc20Tok1Sidechain, relayer1PKey));
//      LOG.info("  Relayer2: {}", getBalanceErc20Tokens(false, erc20Tok1Sidechain, relayer2PKey));
              LOG.info("  User2: {}", getBalanceErc20Tokens(false, erc20Tok1Sidechain, userPKey));

              LOG.info("User {}: Posting preimage to Sidechain", userAddress);
              postPreimage(false, preimageSalts[i], commitments[i], userPKey);

              LOG.info(" Balances After Transfer on Sidechain for Token {}: {}", TOK1_NAME, erc20Tok1Sidechain);
              LOG.info("  Receiver Contract: {}", getBalanceErc20Tokens(false, erc20Tok1Sidechain, relayer1PKey, transferSidechain));
              LOG.info("  Relayer1: {}", getBalanceErc20Tokens(false, erc20Tok1Sidechain, relayer1PKey));
//      LOG.info("  Relayer2: {}", getBalanceErc20Tokens(false, erc20Tok1Sidechain, relayer2PKey));
              LOG.info("  User2: {}", getBalanceErc20Tokens(false, erc20Tok1Sidechain, userPKey));
            }

          } catch (Exception ex) {
              LOG.error("Error executing for user {}: {}", user, userAddress);

          }
        }
      });
    }



//
//    // Complete the transfer.
//    try {
//      txr = this.transferContract.finaliseTransferToOtherBlockchain(commitmentBytes, preimageBytes).send();
//    } catch (TransactionException ex) {
//      LOG.error(RevertReason.decodeRevertReason(ex.getTransactionReceipt().get().getRevertReason()));
//      throw ex;
//    }
//    if (!txr.isStatusOK()) {
//      throw new Exception("Status not OK: finaliseTransferToOtherBlockchain");
//    }
//
//    // Check that the transfer contract believes the transfer is completed.
//    BigInteger transferState = this.transferContract.transferState(commitmentBytes).send();
//    assertTrue(TransferState.FINALILISED.equals(transferState));
//
//    // Check that the balance was transferred in the ERC 20 contract.
//    BigInteger balance = token1Erc20.balanceOf(transferContractAddress).send();
//    assertEquals(amountToTransfer, balance);
//
//    balance = token1Erc20.balanceOf(this.credentials.getAddress()).send();
//    assertEquals(TEST_SUPPLY.subtract(amountToTransfer), balance);

  }


  public String deployTransferContract(boolean deployOnMainNet, String pKeyOwner, long sourceTimeLock, long destTimeLock) throws Exception {
    String uri = deployOnMainNet ? MAINNET_BLOCKCHAIN_URI : SIDECHAIN_BLOCKCHAIN_URI;
    String bcId = deployOnMainNet ? MAINNET_BLOCKCHAIN_ID : SIDECHAIN_BLOCKCHAIN_ID;
    String blockPeriod = deployOnMainNet ? Integer.toString(MAINNET_BLOCK_PERIOD) : Integer.toString(SIDECHAIN_BLOCK_PERIOD);
    String sourceTimeLockStr = Long.toString(sourceTimeLock);
    String destTimeLockStr = Long.toString(destTimeLock);

    String[] args = new String[] {
        "ignored",
        uri,
        bcId,       // Chain ID
        pKeyOwner,
        blockPeriod,
        sourceTimeLockStr,
        destTimeLockStr};   // Timelock: 24 * 60 * 60 = 86400

    return DeployTransferContract.deploy(args);
  }

  public String deployErc20Contract(boolean deployOnMainNet, String tokenName, String tokenSymbol, long totalSupply, String pKeyOwner) throws Exception {
    String uri = deployOnMainNet ? MAINNET_BLOCKCHAIN_URI : SIDECHAIN_BLOCKCHAIN_URI;
    String bcId = deployOnMainNet ? MAINNET_BLOCKCHAIN_ID : SIDECHAIN_BLOCKCHAIN_ID;
    String blockPeriod = deployOnMainNet ? Integer.toString(MAINNET_BLOCK_PERIOD) : Integer.toString(SIDECHAIN_BLOCK_PERIOD);
    String totalSupplyStr = Long.toString(totalSupply);

    String[] args = new String[] {
        "ignored",
        uri,
        bcId,       // Chain ID
        pKeyOwner,
        blockPeriod,
        totalSupplyStr,
        tokenName,
        tokenSymbol};

    return DeployERC20Contract.deploy(args);
  }


  public void transferErc20Tokens(boolean onMainNet, String contractAddress, String fromPKey, String toAddress, long amount) throws Exception {
    String uri = onMainNet ? MAINNET_BLOCKCHAIN_URI : SIDECHAIN_BLOCKCHAIN_URI;
    String bcIdStr = onMainNet ? MAINNET_BLOCKCHAIN_ID : SIDECHAIN_BLOCKCHAIN_ID;
    String blockPeriod = onMainNet ? Integer.toString(MAINNET_BLOCK_PERIOD) : Integer.toString(SIDECHAIN_BLOCK_PERIOD);

    long bcId = Long.parseLong(bcIdStr);
    int pollingInterval = Integer.parseInt(blockPeriod);
    final int RETRY = 5;

    Web3j web3j;
    TransactionManager tm;
    // A gas provider which indicates no gas is charged for transactions.
    ContractGasProvider freeGasProvider =  new StaticGasProvider(BigInteger.ZERO, DefaultGasProvider.GAS_LIMIT);

    Credentials from = Credentials.create(fromPKey);

    web3j = Web3j.build(new HttpService(uri), pollingInterval, new ScheduledThreadPoolExecutor(5));
    tm = new RawTransactionManager(web3j, from, bcId, RETRY, pollingInterval);
    ERC20PresetFixedSupply erc20 = ERC20PresetFixedSupply.load(contractAddress, web3j, tm, freeGasProvider);
    TransactionReceipt txr = erc20.transfer(toAddress, BigInteger.valueOf(amount)).send();
    if (!txr.isStatusOK()) {
      throw new Exception("ERC 20 transfer failed");
    }
  }

  public BigInteger getBalanceErc20Tokens(boolean onMainNet, String contractAddress, String pKey) throws Exception {
    Credentials user = Credentials.create(pKey);
    return getBalanceErc20Tokens(onMainNet, contractAddress, pKey, user.getAddress());
  }


  public BigInteger getBalanceErc20Tokens(boolean onMainNet, String contractAddress, String pKey, String address) throws Exception {
    String uri = onMainNet ? MAINNET_BLOCKCHAIN_URI : SIDECHAIN_BLOCKCHAIN_URI;
    String bcIdStr = onMainNet ? MAINNET_BLOCKCHAIN_ID : SIDECHAIN_BLOCKCHAIN_ID;
    String blockPeriod = onMainNet ? Integer.toString(MAINNET_BLOCK_PERIOD) : Integer.toString(SIDECHAIN_BLOCK_PERIOD);

    long bcId = Long.parseLong(bcIdStr);
    int pollingInterval = Integer.parseInt(blockPeriod);
    final int RETRY = 5;

    Web3j web3j;
    TransactionManager tm;
    Credentials credentials;
    // A gas provider which indicates no gas is charged for transactions.
    ContractGasProvider freeGasProvider =  new StaticGasProvider(BigInteger.ZERO, DefaultGasProvider.GAS_LIMIT);

    Credentials user = Credentials.create(pKey);

    web3j = Web3j.build(new HttpService(uri), pollingInterval, new ScheduledThreadPoolExecutor(5));
    tm = new RawTransactionManager(web3j, user, bcId, RETRY, pollingInterval);
    ERC20PresetFixedSupply erc20 = ERC20PresetFixedSupply.load(contractAddress, web3j, tm, freeGasProvider);
    return erc20.balanceOf(address).send();
  }

  public void authoriseErc20TokensOnReceiver(boolean onMainNet, String receiverContractAddress, String pKey, String localErc20, String remoteErc20) throws Exception {
    String uri = onMainNet ? MAINNET_BLOCKCHAIN_URI : SIDECHAIN_BLOCKCHAIN_URI;
    String bcIdStr = onMainNet ? MAINNET_BLOCKCHAIN_ID : SIDECHAIN_BLOCKCHAIN_ID;
    String blockPeriod = onMainNet ? Integer.toString(MAINNET_BLOCK_PERIOD) : Integer.toString(SIDECHAIN_BLOCK_PERIOD);

    String[] args = new String[] {
        "ignored",
        uri,
        bcIdStr,       // Chain ID
        pKey,
        blockPeriod,
        receiverContractAddress,
        localErc20,
        remoteErc20};

    AuthoriseERC20ForReceiver.authorise(args);
  }

  public void authoriseErc20TokensOnTransfer(boolean onMainNet, String receiverContractAddress, String pKey, String localErc20) throws Exception {
    String uri = onMainNet ? MAINNET_BLOCKCHAIN_URI : SIDECHAIN_BLOCKCHAIN_URI;
    String bcIdStr = onMainNet ? MAINNET_BLOCKCHAIN_ID : SIDECHAIN_BLOCKCHAIN_ID;
    String blockPeriod = onMainNet ? Integer.toString(MAINNET_BLOCK_PERIOD) : Integer.toString(SIDECHAIN_BLOCK_PERIOD);

    String[] args = new String[] {
        "ignored",
        uri,
        bcIdStr,       // Chain ID
        pKey,
        blockPeriod,
        receiverContractAddress,
        localErc20};

    AuthoriseERC20ForTransfer.authorise(args);
  }

  // Approve of the transfer contract transferring tokens on behalf of the user.
  public void approveErc20Tokens(boolean onMainNet, String contractAddress, String userPKey, String authorisedAddress, long amount) throws Exception {
    String uri = onMainNet ? MAINNET_BLOCKCHAIN_URI : SIDECHAIN_BLOCKCHAIN_URI;
    String bcIdStr = onMainNet ? MAINNET_BLOCKCHAIN_ID : SIDECHAIN_BLOCKCHAIN_ID;
    String blockPeriod = onMainNet ? Integer.toString(MAINNET_BLOCK_PERIOD) : Integer.toString(SIDECHAIN_BLOCK_PERIOD);

    long bcId = Long.parseLong(bcIdStr);
    int pollingInterval = Integer.parseInt(blockPeriod);
    final int RETRY = 5;

    Web3j web3j;
    TransactionManager tm;
    Credentials credentials;
    // A gas provider which indicates no gas is charged for transactions.
    ContractGasProvider freeGasProvider =  new StaticGasProvider(BigInteger.ZERO, DefaultGasProvider.GAS_LIMIT);

    Credentials user = Credentials.create(userPKey);

    web3j = Web3j.build(new HttpService(uri), pollingInterval, new ScheduledThreadPoolExecutor(5));
    tm = new RawTransactionManager(web3j, user, bcId, RETRY, pollingInterval);
    ERC20PresetFixedSupply erc20 = ERC20PresetFixedSupply.load(contractAddress, web3j, tm, freeGasProvider);
    TransactionReceipt txr = erc20.approve(authorisedAddress, BigInteger.valueOf(amount)).send();
    if (!txr.isStatusOK()) {
      throw new Exception("ERC 20 approve failed");
    }
  }

  public void launchRelayer(String configFileName) throws Exception {
    Relayer.main(new String[]{configFileName});
  }


  public void writeRelayerConfigFile(boolean fromMainNetToSidechain, String relayerPKey, int adminPort, long maxTimeLock, String filename) throws IOException {
    String sourceBcUri = fromMainNetToSidechain ? MAINNET_BLOCKCHAIN_URI : SIDECHAIN_BLOCKCHAIN_URI;
    String sourceTransferContract = fromMainNetToSidechain ? transferMainNet : transferSidechain;
    int sourceBlockPeriod = fromMainNetToSidechain ? MAINNET_BLOCK_PERIOD : SIDECHAIN_BLOCK_PERIOD;
    int sourceConfirmations = fromMainNetToSidechain ? MAINNET_CONFIRMATIONS : SIDECHAIN_CONFIRMATIONS;
    int sourceRetries = 5;
    String sourceGasStrategy = DynamicGasProvider.Strategy.FREE.toString();
    long sourceBcId = fromMainNetToSidechain ? Integer.valueOf(MAINNET_BLOCKCHAIN_ID) : Integer.valueOf(SIDECHAIN_BLOCKCHAIN_ID);

    String destBcUri = fromMainNetToSidechain ? SIDECHAIN_BLOCKCHAIN_URI : MAINNET_BLOCKCHAIN_URI;
    String destReceiverContract = fromMainNetToSidechain ? transferSidechain : transferMainNet;
    int destBlockPeriod = fromMainNetToSidechain ? SIDECHAIN_BLOCK_PERIOD : MAINNET_BLOCK_PERIOD;
    int destConfirmations = fromMainNetToSidechain ? SIDECHAIN_CONFIRMATIONS : MAINNET_CONFIRMATIONS;
    int destRetries = 5;
    String destGasStrategy = DynamicGasProvider.Strategy.FREE.toString();
    long destBcId = fromMainNetToSidechain ? Integer.valueOf(SIDECHAIN_BLOCKCHAIN_ID) : Integer.valueOf(MAINNET_BLOCKCHAIN_ID);

    RelayerConfig config = new RelayerConfig(
        sourceBcUri, sourceTransferContract, sourceBlockPeriod, sourceConfirmations,
        sourceRetries, sourceBcId, relayerPKey, sourceGasStrategy,
        destBcUri, destReceiverContract, destBlockPeriod, destConfirmations,
        destRetries, destBcId, relayerPKey, destGasStrategy,
        maxTimeLock,
        adminPort
    );
    String result = new ObjectMapper().writeValueAsString(config);
    File file = new File(filename);
    FileWriter fw = new FileWriter(file);
    fw.write(result);
    fw.close();
  }




  public byte[][] newTransfer(boolean fromMainNetToSidechain, String userPKey, String tokenContract, BigInteger amountToTransfer) throws Exception {
    String uri = fromMainNetToSidechain ? MAINNET_BLOCKCHAIN_URI : SIDECHAIN_BLOCKCHAIN_URI;
    String bcIdStr = fromMainNetToSidechain ? MAINNET_BLOCKCHAIN_ID : SIDECHAIN_BLOCKCHAIN_ID;
    String blockPeriod = fromMainNetToSidechain ? Integer.toString(MAINNET_BLOCK_PERIOD) : Integer.toString(SIDECHAIN_BLOCK_PERIOD);
    String transferContractAddress = fromMainNetToSidechain ? transferMainNet : transferSidechain;

    long bcId = Long.parseLong(bcIdStr);
    int pollingInterval = Integer.parseInt(blockPeriod);
    final int RETRY = 5;

    Web3j web3j;
    TransactionManager tm;
    // A gas provider which indicates no gas is charged for transactions.
    ContractGasProvider freeGasProvider =  new StaticGasProvider(BigInteger.ZERO, DefaultGasProvider.GAS_LIMIT);

    Credentials user = Credentials.create(userPKey);
    web3j = Web3j.build(new HttpService(uri), pollingInterval, new ScheduledThreadPoolExecutor(5));
    tm = new RawTransactionManager(web3j, user, bcId, RETRY, pollingInterval);
    Erc20HtlcTransfer transfer = Erc20HtlcTransfer.load(transferContractAddress, web3j, tm, freeGasProvider);


    Bytes preimageSalt = PRNG.getPublicRandomBytes32();
    byte[] preimageSaltBytes = preimageSalt.toArray();
    Bytes commitment = CommitmentCalculator.calculate(preimageSalt, user.getAddress(), tokenContract, amountToTransfer);
    byte[] commitmentBytes = commitment.toArray();

    TransactionReceipt txr;
    try {
      txr = transfer.newTransferToOtherBlockchain(tokenContract, amountToTransfer, commitmentBytes).send();
    } catch (TransactionException ex) {
      LOG.error("transfer.newTransferToOtherBlockchain reverted: {}", RevertReason.decodeRevertReason(ex.getTransactionReceipt().get().getRevertReason()));
      throw ex;
    }
    if (!txr.isStatusOK()) {
      throw new Exception("Status not OK: newTransferToOtherBlockchain");
    }
    return new byte[][]{preimageSaltBytes, commitmentBytes};
  }


  public void waitForHtlcToBePostedToRelayer(boolean onMainNet, byte[] commitment, String userPKey) throws Exception {
    String uri = onMainNet ? MAINNET_BLOCKCHAIN_URI : SIDECHAIN_BLOCKCHAIN_URI;
    String bcIdStr = onMainNet ? MAINNET_BLOCKCHAIN_ID : SIDECHAIN_BLOCKCHAIN_ID;
    String blockPeriod = onMainNet ? Integer.toString(MAINNET_BLOCK_PERIOD) : Integer.toString(SIDECHAIN_BLOCK_PERIOD);
    String receiverContractAddress = onMainNet ? transferMainNet : transferSidechain;

    long bcId = Long.parseLong(bcIdStr);
    int pollingInterval = Integer.parseInt(blockPeriod);
    final int RETRY = 5;

    // A gas provider which indicates no gas is charged for transactions.
    Credentials user = Credentials.create(userPKey);
    ContractGasProvider freeGasProvider =  new StaticGasProvider(BigInteger.ZERO, DefaultGasProvider.GAS_LIMIT);
    Web3j web3j = Web3j.build(new HttpService(uri), pollingInterval, new ScheduledThreadPoolExecutor(5));
    TransactionManager tm = new RawTransactionManager(web3j, user, bcId, RETRY, pollingInterval);

    Erc20HtlcTransfer receiver = Erc20HtlcTransfer.load(receiverContractAddress, web3j, tm, freeGasProvider);

    boolean exists = false;
    for (int i=0; i<1000; i++) {
      LOG.info(" Waiting for transfer to be posted to receiver: {}", i);
      exists = receiver.destTransferExists(commitment).send();
      if (exists) {
        LOG.info(" Transfer has been posted to receiver!");
        break;
      }
      Thread.sleep(100);
    }
    if (!exists) {
      LOG.error(" timed out waiting for transfer to be posted to receiver!");
      throw new Exception("timed out waiting for transfer to be posted");
    }
  }

  public ReceiverInfo getDetailsFromReceiver(boolean onMainNet, byte[] commitment, String userPKey) throws Exception {
    String uri = onMainNet ? MAINNET_BLOCKCHAIN_URI : SIDECHAIN_BLOCKCHAIN_URI;
    String bcIdStr = onMainNet ? MAINNET_BLOCKCHAIN_ID : SIDECHAIN_BLOCKCHAIN_ID;
    String blockPeriod = onMainNet ? Integer.toString(MAINNET_BLOCK_PERIOD) : Integer.toString(SIDECHAIN_BLOCK_PERIOD);
    String receiverContractAddress = onMainNet ? transferMainNet : transferSidechain;

    long bcId = Long.parseLong(bcIdStr);
    int pollingInterval = Integer.parseInt(blockPeriod);
    final int RETRY = 5;

    // A gas provider which indicates no gas is charged for transactions.
    Credentials user = Credentials.create(userPKey);
    ContractGasProvider freeGasProvider =  new StaticGasProvider(BigInteger.ZERO, DefaultGasProvider.GAS_LIMIT);
    Web3j web3j = Web3j.build(new HttpService(uri), pollingInterval, new ScheduledThreadPoolExecutor(5));
    TransactionManager tm = new RawTransactionManager(web3j, user, bcId, RETRY, pollingInterval);

    Erc20HtlcTransfer receiver = Erc20HtlcTransfer.load(receiverContractAddress, web3j, tm, freeGasProvider);
    Tuple7<String, String, String, BigInteger, byte[], BigInteger, BigInteger> info = receiver.getDestInfo(commitment).send();
    return new ReceiverInfo(commitment, info);
  }


  public void postPreimage(boolean onMainNet, byte[] preimage, byte[] commitment, String userPKey) throws Exception {
    String uri = onMainNet ? MAINNET_BLOCKCHAIN_URI : SIDECHAIN_BLOCKCHAIN_URI;
    String bcIdStr = onMainNet ? MAINNET_BLOCKCHAIN_ID : SIDECHAIN_BLOCKCHAIN_ID;
    String blockPeriod = onMainNet ? Integer.toString(MAINNET_BLOCK_PERIOD) : Integer.toString(SIDECHAIN_BLOCK_PERIOD);
    String receiverContractAddress = onMainNet ? transferMainNet : transferSidechain;

    long bcId = Long.parseLong(bcIdStr);
    int pollingInterval = Integer.parseInt(blockPeriod);
    final int RETRY = 5;

    // A gas provider which indicates no gas is charged for transactions.
    Credentials user = Credentials.create(userPKey);
    ContractGasProvider freeGasProvider =  new StaticGasProvider(BigInteger.ZERO, DefaultGasProvider.GAS_LIMIT);
    Web3j web3j = Web3j.build(new HttpService(uri), pollingInterval, new ScheduledThreadPoolExecutor(5));
    TransactionManager tm = new RawTransactionManager(web3j, user, bcId, RETRY, pollingInterval);

    Erc20HtlcTransfer receiver = Erc20HtlcTransfer.load(receiverContractAddress, web3j, tm, freeGasProvider);
    TransactionReceipt txr;
    try {
      txr = receiver.finaliseTransferFromOtherBlockchain(commitment, preimage).send();
    } catch (TransactionException ex) {
      LOG.error(" Receiver: finaliseTransferFromOtherBlockchain reverted: {}", RevertReason.decodeRevertReason(ex.getTransactionReceipt().get().getRevertReason()));
      throw ex;
    }
    if (!txr.isStatusOK()) {
      throw new Exception(" Receiver: finaliseTransferFromOtherBlockchain: Status not OK");
    }
    LOG.info(" Posting preimage successful");
  }

  public static void main(String[] args) throws Exception {
    LOG.info("Start");
    try {
      new IntegrationTests().runTest();
    } finally {
      LOG.info("Done");
    }
  }
}
