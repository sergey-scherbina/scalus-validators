package validators

import org.scalatest.funsuite.AnyFunSuite
import scalus.uplc.builtin.ByteString.utf8
import scalus.uplc.builtin.Data.toData
import scalus.cardano.ledger.*
import scalus.cardano.node.Emulator
import scalus.cardano.txbuilder.TxBuilder
import scalus.cardano.onchain.plutus.v1.PubKeyHash
import scalus.cardano.onchain.plutus.v3.{TxId, TxOutRef}
import scalus.testing.kit.Party.{Alice, Bob}
import scalus.testing.kit.{ScalusTest, TestUtil}
import scalus.utils.await

/** Whole transactions, built by TxBuilder and submitted to an in-memory ledger that runs the scripts. */
class EmulatorSpec extends AnyFunSuite, ScalusTest {

    private given env: CardanoInfo = TestUtil.testEnvironment

    private def ledger = Emulator.withAddresses(Seq(Alice.address, Bob.address))

    extension (provider: Emulator) private def utxosOf(party: scalus.testing.kit.Party) =
        provider.findUtxos(party.address).await().toOption.get

    test("vesting: Alice locks 10 ADA for Bob, Bob claims after the deadline") {
        val provider = ledger
        val vesting = VestingContract.compiled.withErrorTraces
        val script = vesting.address(env.network)
        val deadlineSlot: SlotNo = 10
        val deadline = env.slotConfig.slotToInstant(deadlineSlot)
        val datum = VestingDatum(PubKeyHash(Alice.addrKeyHash), PubKeyHash(Bob.addrKeyHash), deadline.toEpochMilli)

        val lockTx = TxBuilder(env)
            .payTo(script, Value.ada(10), datum)
            .complete(availableUtxos = provider.utxosOf(Alice), sponsor = Alice.address)
            .sign(Alice.signer)
            .transaction
        assert(provider.submit(lockTx).await().isRight)
        val locked = Utxo(lockTx.utxos.find((_, out) => out.address == script).get)

        def claim = TxBuilder(env)
            .spend(locked, VestingAction.Claim, vesting)
            .requireSignature(Bob.addrKeyHash)
            .payTo(Bob.address, locked.output.value)
            .validFrom(deadline)
            .complete(availableUtxos = provider.utxosOf(Bob), sponsor = Bob.address)
            .sign(Bob.signer)
            .transaction

        val tx = claim
        info(s"claim budget: ${tx.witnessSet.redeemers.get.value.totalExUnits}, fee: ${tx.body.value.fee}")
        provider.setSlot(deadlineSlot)
        val result = provider.submit(tx).await()
        assert(result.isRight, s"$result")
    }

    test("one-shot NFT: Alice mints with a seed; the same policy cannot mint again") {
        val provider = ledger
        // A seed of its own, so Alice still has another UTxO for collateral.
        val splitTx = TxBuilder(env)
            .payTo(Alice.address, Value.ada(20))
            .complete(availableUtxos = provider.utxosOf(Alice), sponsor = Alice.address)
            .sign(Alice.signer)
            .transaction
        assert(provider.submit(splitTx).await().isRight)
        val seedUtxo = Utxo(splitTx.utxos.find((_, out) => out.value == Value.ada(20)).get)
        val seed = TxOutRef(TxId(seedUtxo.input.transactionId), BigInt(seedUtxo.input.index))
        val policy = OneShotNftContract.compiled.withErrorTraces(seed.toData)
        val name = utf8"artwork-1"
        val asset = AssetName(name)

        val mintTx = TxBuilder(env)
            .spend(seedUtxo)
            .mint(policy, Map(asset -> 1L), NftAction.Mint(name))
            .payTo(Alice.address, Value.ada(2) + Value.asset(policy.script.scriptHash, asset, 1L))
            .complete(availableUtxos = provider.utxosOf(Alice), sponsor = Alice.address)
            .sign(Alice.signer)
            .transaction
        val minted = provider.submit(mintTx).await()
        assert(minted.isRight, s"$minted")

        // The seed is gone, so a second mint under the same policy cannot even be built: the script rejects it.
        val again = scala.util.Try {
            TxBuilder(env)
                .mint(policy, Map(asset -> 1L), NftAction.Mint(name))
                .payTo(Alice.address, Value.ada(2) + Value.asset(policy.script.scriptHash, asset, 1L))
                .complete(availableUtxos = provider.utxosOf(Alice), sponsor = Alice.address)
                .sign(Alice.signer)
                .transaction
        }
        val reason = again.failed.toOption.map(_.getMessage).getOrElse("")
        assert(reason.contains(OneShotNft.SeedNotSpent), s"$again")
    }

    test("script sizes") {
        for (name, size) <- Seq(
              "Vesting" -> VestingContract.compiled.script.script.size,
              "Escrow" -> EscrowContract.compiled.script.script.size,
              "Multisig" -> MultisigContract.compiled.script.script.size,
              "OneShotNft" -> OneShotNftContract.compiled.script.script.size
            )
        do info(s"$name: $size bytes")
    }
}
