package validators

import org.scalatest.funsuite.AnyFunSuite
import scalus.uplc.builtin.ByteString.utf8
import scalus.uplc.builtin.Data.toData
import scalus.uplc.eval.Result
import scalus.cardano.onchain.plutus.v1.Address
import scalus.cardano.onchain.plutus.v2.OutputDatum
import scalus.cardano.onchain.plutus.v3.*
import scalus.cardano.onchain.plutus.prelude.{List, Option as POption}
import scalus.testing.kit.TestUtil

/** Every test but the happy paths is an attack on the state machine, refused for its own reason. */
class StateCounterSpec extends AnyFunSuite, Fixtures {

    private val seed = TestUtil.mockTxOutRef(5, 0)
    private val program = StateCounterContract.compiled.withErrorTraces(seed.toData).program
    private val policy = TestUtil.mockScriptHash(7)
    private val home = Address(Credential.ScriptCredential(policy), POption.None)
    private val nft = Value(policy, StateCounter.stateName, 1)
    private val ada = Value.lovelace(2_000_000)
    private val stateRef = TestUtil.mockTxOutRef(9, 0)
    private val s0 = CounterState(alice, 0)
    private val s1 = CounterState(alice, 1)

    private def stateOut(st: CounterState, value: Value = ada + nft, at: Address = home) =
        TxOut(at, value, OutputDatum.OutputDatum(st.toData))
    private def stateIn(st: CounterState, value: Value = ada + nft, ref: TxOutRef = stateRef) =
        TxInInfo(ref, stateOut(st, value))
    private val seedIn = TxInInfo(seed, TxOut(Address.fromPubKeyHash(alice), ada, OutputDatum.NoOutputDatum))

    private def spend(ins: List[TxInInfo], outs: List[TxOut], action: CounterAction, signers: List[PubKeyHash],
        minted: Value = Value.zero): Result =
        val tx = TxInfo.placeholder.copy(inputs = ins, outputs = outs, signatories = signers, mint = minted)
        program.runWithDebug(ScriptContext(tx, action.toData, ScriptInfo.SpendingScript(stateRef, POption.None)))

    private def mint(ins: List[TxInInfo], outs: List[TxOut], signers: List[PubKeyHash], minted: Value): Result =
        val tx = TxInfo.placeholder.copy(inputs = ins, outputs = outs, signatories = signers, mint = minted)
        program.runWithDebug(ScriptContext(tx, CounterMint.Init.toData, ScriptInfo.MintingScript(policy)))

    test("init: the one NFT is minted into the script with count 0") {
        val r = mint(List(seedIn), List(stateOut(s0)), List(alice), nft)
        assert(r.isSuccess, r.explain)
    }

    test("init without the seed is rejected: the NFT exists once") {
        assert(mint(List.Nil, List(stateOut(s0)), List(alice), nft).failsWith(StateCounter.SeedNotSpent))
    }

    test("init with a stake part on the state address is rejected: it would stay forever") {
        val staked = Address(Credential.ScriptCredential(policy),
            POption.Some(StakingCredential.StakingHash(Credential.PubKeyCredential(eve))))
        val r = mint(List(seedIn), List(stateOut(s0, at = staked)), List(alice), nft)
        assert(r.failsWith(StateCounter.NoStakeAtInit))
    }

    test("init sending the NFT anywhere but the script is rejected") {
        val away = TxOut(Address.fromPubKeyHash(alice), ada + nft, OutputDatum.OutputDatum(s0.toData))
        assert(mint(List(seedIn), List(away), List(alice), nft).failsWith(StateCounter.OneStateOutput))
    }

    test("increment: the owner moves the state forward, the NFT with it") {
        val r = spend(List(stateIn(s0)), List(stateOut(s1)), CounterAction.Increment, List(alice))
        assert(r.isSuccess, r.explain)
    }

    test("forged state: a UTxO at the script without the NFT is not the state") {
        val r = spend(List(stateIn(s0, ada)), List(stateOut(s1, ada)), CounterAction.Increment, List(alice))
        assert(r.failsWith(StateCounter.NotTheState))
    }

    test("double satisfaction: two script inputs in one transaction are rejected") {
        val other = stateIn(s0, ada + Value(policy, utf8"STATE", 1), TestUtil.mockTxOutRef(10, 0))
        val r = spend(List(stateIn(s0), other), List(stateOut(s1)), CounterAction.Increment, List(alice))
        assert(r.failsWith(StateCounter.OneStateInput))
    }

    test("the NFT taken out of the state is rejected") {
        val r = spend(List(stateIn(s0)), List(stateOut(s1, ada)), CounterAction.Increment, List(alice))
        assert(r.failsWith(StateCounter.OnlyAdaAndNft))
    }

    test("token dust in the state is rejected") {
        val dust = ada + nft + Value(TestUtil.mockScriptHash(3), utf8"junk", 1)
        val r = spend(List(stateIn(s0)), List(stateOut(s1, dust)), CounterAction.Increment, List(alice))
        assert(r.failsWith(StateCounter.OnlyAdaAndNft))
    }

    test("ada drained from the state is rejected") {
        val less = Value.lovelace(1_000_000) + nft
        val r = spend(List(stateIn(s0)), List(stateOut(s1, less)), CounterAction.Increment, List(alice))
        assert(r.failsWith(StateCounter.AdaKept))
    }

    test("stake hijack: the same script with another stake credential is rejected") {
        val hijacked = Address(Credential.ScriptCredential(policy),
            POption.Some(StakingCredential.StakingHash(Credential.PubKeyCredential(eve))))
        val r = spend(List(stateIn(s0)), List(stateOut(s1, at = hijacked)), CounterAction.Increment, List(alice))
        assert(r.failsWith(StateCounter.SameAddress))
    }

    test("a wrong transition or a new owner is rejected") {
        val jump = spend(List(stateIn(s0)), List(stateOut(CounterState(alice, 5))), CounterAction.Increment,
            List(alice))
        assert(jump.failsWith(StateCounter.BadTransition))
        val stolen = spend(List(stateIn(s0)), List(stateOut(CounterState(eve, 1))), CounterAction.Increment,
            List(alice))
        assert(stolen.failsWith(StateCounter.BadTransition))
    }

    test("a change not signed by the owner is rejected") {
        val r = spend(List(stateIn(s0)), List(stateOut(s1)), CounterAction.Increment, List(eve))
        assert(r.failsWith(StateCounter.OwnerMustSign))
    }

    test("close burns the NFT; without the burn it is rejected") {
        val burn = Value(policy, utf8"STATE", -1)
        assert(spend(List(stateIn(s1)), List.Nil, CounterAction.Close, List(alice), burn).isSuccess)
        val r = spend(List(stateIn(s1)), List.Nil, CounterAction.Close, List(alice))
        assert(r.failsWith(StateCounter.MustBurnTheNft))
    }
}
