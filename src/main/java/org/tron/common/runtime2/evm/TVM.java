package org.tron.common.runtime2.evm;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.tron.common.logsfilter.EventPluginLoader;
import org.tron.common.runtime.vm.LogInfoTriggerParser;
import org.tron.common.runtime.vm.program.InternalTransaction;
import org.tron.common.runtime.vm.program.ProgramResult;
import org.tron.common.runtime2.IVM;
import org.tron.common.runtime2.config.VMConfig;
import org.tron.common.storage.Deposit;
import org.tron.core.Constant;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.ContractCapsule;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.core.db.EnergyProcessor;
import org.tron.core.db.TransactionTrace;
import org.tron.core.exception.ContractValidateException;
import org.tron.core.exception.VMIllegalException;
import org.tron.protos.Contract;
import org.tron.protos.Protocol;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Objects;

import static java.lang.Math.max;
import static java.lang.Math.min;

@Slf4j(topic = "VM2")
public class TVM implements IVM {

  @Setter
  private VMConfig vmConfig;

  private EnergyProcessor energyProcessor;

  private TransactionTrace trace;

  private BlockCapsule blockCap;

  private Protocol.Transaction trx;

  private InternalTransaction.TrxType trxType;

  private Deposit deposit;

  private LogInfoTriggerParser logInfoTriggerParser;


  public TVM(TransactionTrace trace, Protocol.Transaction trx, BlockCapsule block, Deposit deposit) {
    this.trace = trace;
    this.trx = (trx == null ? trace.getTrx().getInstance() : trx);
    this.energyProcessor = new EnergyProcessor(deposit.getDbManager());
    this.deposit = deposit;
    if (Objects.nonNull(block)) {
      this.blockCap = block;
    } else {
      this.blockCap = new BlockCapsule(Protocol.Block.newBuilder().build());
    }

    Protocol.Transaction.Contract.ContractType contractType = this.trx.getRawData().getContract(0).getType();
    switch (contractType.getNumber()) {
      case Protocol.Transaction.Contract.ContractType.TriggerSmartContract_VALUE:
        trxType = InternalTransaction.TrxType.TRX_CONTRACT_CALL_TYPE;
        break;
      default:
        trxType = InternalTransaction.TrxType.TRX_CONTRACT_CREATION_TYPE;
    }
  }


  @Override
  public void execute(boolean isStatic) throws ContractValidateException, VMIllegalException {
    //Validate and getBaseProgram
    Program program = preValidateAndGetBaseProgram(isStatic);
    //create Program


  }


  Program preValidateAndGetBaseProgram(boolean isStatic) throws ContractValidateException, VMIllegalException {
    Program program = new Program();
    if (trxType == InternalTransaction.TrxType.TRX_CONTRACT_CREATION_TYPE) {
      Contract.CreateSmartContract contract = ContractCapsule.getSmartContractFromTransaction(trx);
      if (contract == null) {
        throw new ContractValidateException("Cannot get CreateSmartContract from transaction");
      }

      Protocol.SmartContract newSmartContract = contract.getNewContract();
      if (!contract.getOwnerAddress().equals(newSmartContract.getOriginAddress())) {
        logger.info("OwnerAddress not equals OriginAddress");
        throw new VMIllegalException("OwnerAddress is not equals OriginAddress");
      }

      byte[] contractName = newSmartContract.getName().getBytes();

      if (contractName.length > VMConfig.CONTRACT_NAME_LENGTH) {
        throw new ContractValidateException("contractName's length cannot be greater than 32");
      }

      long percent = contract.getNewContract().getConsumeUserResourcePercent();
      if (percent < 0 || percent > VMConfig.ONE_HUNDRED) {
        throw new ContractValidateException("percent must be >= 0 and <= 100");
      }
      AccountCapsule creator = this.deposit.getAccount(newSmartContract.getOriginAddress().toByteArray());
      byte[] callerAddress = contract.getOwnerAddress().toByteArray();


      program.setCallValue(newSmartContract.getCallValue());
      program.setTokenId(contract.getTokenId());
      program.setTokenValue(contract.getCallTokenValue());
      program.setCreator(creator);
      program.setCallerAddress(callerAddress);
      program.setOps(newSmartContract.getBytecode().toByteArray());


    } else { // TRX_CONTRACT_CALL_TYPE


    }
    //calculateEnergyLimit
    long energylimt = calculateEnergyLimit(program.getCreator(), null, null, isStatic, program.getCallValue());
    program.setEnergyLimit(energylimt);
    //maxCpuTime
    long maxCpuTimeOfOneTx = vmConfig.getMaxCpuTimeOfOneTx()
            * VMConfig.ONE_THOUSAND;
    long thisTxCPULimitInUs = (long) (maxCpuTimeOfOneTx * getCpuLimitInUsRatio());
    long vmStartInUs = System.nanoTime() / VMConfig.ONE_THOUSAND;
    long vmShouldEndInUs = vmStartInUs + thisTxCPULimitInUs;
    program.setEnergyLimit(energylimt);
    program.setVmStartInUs(vmStartInUs);
    program.setVmShouldEndInUs(vmShouldEndInUs);
    //

    byte[] txId = new TransactionCapsule(trx).getTransactionId().getBytes();
    program.setRootTransactionId(txId);
    program.setInternalTransaction(new InternalTransaction(trx, trxType));

    if (vmConfig.isEventPluginLoaded() &&
            (EventPluginLoader.getInstance().isContractEventTriggerEnable()
                    || EventPluginLoader.getInstance().isContractLogTriggerEnable())
            && isCheckTransaction()) {
      logInfoTriggerParser = new LogInfoTriggerParser(blockCap.getNum(), blockCap.getTimeStamp(),
              txId, program.getCallerAddress());

    }

    return program;
  }


  private long calculateEnergyLimit(AccountCapsule creator, AccountCapsule caller,
                                    Contract.TriggerSmartContract contract, boolean isStatic, long callValue) throws ContractValidateException {
    long energyLimit = 0;
    long rawfeeLimit = trx.getRawData().getFeeLimit();
    if (rawfeeLimit < 0 || rawfeeLimit > vmConfig.getMaxFeeLimit()) {
      logger.info("invalid feeLimit {}", rawfeeLimit);
      throw new ContractValidateException(
              "feeLimit must be >= 0 and <= " + vmConfig.getMaxFeeLimit());
    }
    if (trxType == InternalTransaction.TrxType.TRX_CONTRACT_CREATION_TYPE) {
      energyLimit = getAccountEnergyLimitWithFixRatio(creator, rawfeeLimit, callValue);
    } else { // TRX_CONTRACT_CALL_TYPE
      if (isStatic) {
        energyLimit = Constant.ENERGY_LIMIT_IN_CONSTANT_TX;
      } else {
        energyLimit = getTotalEnergyLimit(creator, caller, contract, rawfeeLimit, callValue);
      }
    }
    return energyLimit;
  }


  private long getAccountEnergyLimitWithFixRatio(AccountCapsule account, long feeLimit,
                                                 long callValue) {

    long sunPerEnergy = Constant.SUN_PER_ENERGY;
    if (deposit.getDbManager().getDynamicPropertiesStore().getEnergyFee() > 0) {
      sunPerEnergy = deposit.getDbManager().getDynamicPropertiesStore().getEnergyFee();
    }

    long leftFrozenEnergy = energyProcessor.getAccountLeftEnergyFromFreeze(account);

    long energyFromBalance = max(account.getBalance() - callValue, 0) / sunPerEnergy;
    long availableEnergy = Math.addExact(leftFrozenEnergy, energyFromBalance);

    long energyFromFeeLimit = feeLimit / sunPerEnergy;
    return min(availableEnergy, energyFromFeeLimit);

  }


  private long getTotalEnergyLimitWithFixRatio(AccountCapsule creator, AccountCapsule caller,
                                               Contract.TriggerSmartContract contract, long feeLimit, long callValue)
          throws ContractValidateException {

    long callerEnergyLimit = getAccountEnergyLimitWithFixRatio(caller, feeLimit, callValue);
    if (Arrays.equals(creator.getAddress().toByteArray(), caller.getAddress().toByteArray())) {
      // when the creator calls his own contract, this logic will be used.
      // so, the creator must use a BIG feeLimit to call his own contract,
      // which will cost the feeLimit TRX when the creator's frozen energy is 0.
      return callerEnergyLimit;
    }

    long creatorEnergyLimit = 0;
    ContractCapsule contractCapsule = this.deposit
            .getContract(contract.getContractAddress().toByteArray());
    long consumeUserResourcePercent = contractCapsule.getConsumeUserResourcePercent();

    long originEnergyLimit = contractCapsule.getOriginEnergyLimit();
    if (originEnergyLimit < 0) {
      throw new ContractValidateException("originEnergyLimit can't be < 0");
    }

    if (consumeUserResourcePercent <= 0) {
      creatorEnergyLimit = min(energyProcessor.getAccountLeftEnergyFromFreeze(creator),
              originEnergyLimit);
    } else {
      if (consumeUserResourcePercent < Constant.ONE_HUNDRED) {
        // creatorEnergyLimit =
        // min(callerEnergyLimit * (100 - percent) / percent, creatorLeftFrozenEnergy, originEnergyLimit)

        creatorEnergyLimit = min(
                BigInteger.valueOf(callerEnergyLimit)
                        .multiply(BigInteger.valueOf(Constant.ONE_HUNDRED - consumeUserResourcePercent))
                        .divide(BigInteger.valueOf(consumeUserResourcePercent)).longValueExact(),
                min(energyProcessor.getAccountLeftEnergyFromFreeze(creator), originEnergyLimit)
        );
      }
    }
    return Math.addExact(callerEnergyLimit, creatorEnergyLimit);
  }


  private long getTotalEnergyLimit(AccountCapsule creator, AccountCapsule caller,
                                   Contract.TriggerSmartContract contract, long feeLimit, long callValue)
          throws ContractValidateException {
    if (Objects.isNull(creator)) {
      return getAccountEnergyLimitWithFixRatio(caller, feeLimit, callValue);
    }
    return getTotalEnergyLimitWithFixRatio(creator, caller, contract, feeLimit, callValue);

  }

  private double getCpuLimitInUsRatio() {
    double cpuLimitRatio;
    // self witness generates block
    if (this.blockCap != null && blockCap.generatedByMyself &&
            this.blockCap.getInstance().getBlockHeader().getWitnessSignature().isEmpty()) {
      cpuLimitRatio = 1.0;
    } else {
      // self witness or other witness or fullnode verifies block
      if (trx.getRet(0).getContractRet() == Protocol.Transaction.Result.contractResult.OUT_OF_TIME) {
        cpuLimitRatio = vmConfig.getMinTimeRatio();
      } else {
        cpuLimitRatio = vmConfig.getMaxTimeRatio();
      }
    }
    return cpuLimitRatio;
  }

  @Override
  public ProgramResult getResult() {
    return null;
  }

  private boolean isCheckTransaction() {
    return this.blockCap != null && !this.blockCap.getInstance().getBlockHeader()
            .getWitnessSignature().isEmpty();
  }
}
