package validators

import okay.cardano.Attacks
import org.scalatest.funsuite.AnyFunSuite
import scalus.compiler.Options
import scalus.uplc.PlutusV3
import scalus.uplc.builtin.{ByteString, Data}
import scalus.uplc.builtin.Data.toData
import scalus.cardano.onchain.plutus.v1.Address
import scalus.cardano.onchain.plutus.v2.OutputDatum
import scalus.cardano.onchain.plutus.v3.*
import scalus.cardano.onchain.plutus.prelude.{List, Option as POption}
import scalus.testing.kit.TestUtil

/** THE CONTRACTS UNDER ATTACK (okay-cardano `Attacks`): each passing case changed the way known exploits change a
  * transaction. NaiveListing must lose to double satisfaction; every other contract must defend every HIGH attack. */
class AttacksSpec extends AnyFunSuite, Fixtures {

    private val ref = TestUtil.mockTxOutRef(1, 0)
    private def at(c: PlutusV3[?]) = Address(Credential.ScriptCredential(c.script.scriptHash), POption.None)
    private def tagged(to: PubKeyHash, lovelace: BigInt) =
        TxOut(Address.fromPubKeyHash(to), Value.lovelace(lovelace), OutputDatum.OutputDatum(ref.toData))
    private def locked(c: PlutusV3[?], value: Value, datum: Data) =
        TxInInfo(ref, TxOut(at(c), value, OutputDatum.OutputDatum(datum)))
    private def spend(tx: TxInfo, redeemer: Data, datum: Data) =
        ScriptContext(tx, redeemer, ScriptInfo.SpendingScript(ref, POption.Some(datum)))

    /** the HIGH attacks the contract accepts */
    private def high(c: PlutusV3[?], ctx: ScriptContext): Set[String] =
        Attacks.run(c.withErrorTraces.program.deBruijnedProgram, ctx) match
            case Left(e)   => fail(e)
            case Right(ts) =>
                ts.filter(t => t.accepted && t.attack.severity == Attacks.Severity.High).map(_.attack.id).toSet

    private val naive = { given Options = Options.release; PlutusV3.compile(NaiveListing.validate) }
    private val nft = Value(TestUtil.mockScriptHash(9), ByteString.fromString("A"), 1)

    private def listingBuy(c: PlutusV3[?]) =
        val d = ListingDatum(alice, 10_000_000).toData
        spend(TxInfo.placeholder.copy(inputs = List(locked(c, Value.lovelace(2_000_000) + nft, d)),
            outputs = List(tagged(alice, 10_000_000))), ListingAction.Buy.toData, d)

    test("NaiveListing: double satisfaction found") {
        assert(high(naive, listingBuy(naive)).contains("double-satisfaction"))
    }

    test("Listing: every HIGH attack defended") {
        assert(high(ListingContract.compiled, listingBuy(ListingContract.compiled)) == Set.empty)
    }

    test("Escrow release: every HIGH attack defended") {
        val c = EscrowContract.compiled
        val d = EscrowDatum(alice, bob, 50_000_000, 1_000_000).toData
        val ctx = spend(TxInfo.placeholder.copy(inputs = List(locked(c, Value.lovelace(50_000_000), d)),
            outputs = List(tagged(alice, 50_000_000)), signatories = List(bob)), EscrowAction.Release.toData, d)
        assert(high(c, ctx) == Set.empty)
    }

    test("ArbitratedEscrow: release and refund, every HIGH attack defended") {
        val c = ArbitratedEscrowContract.compiled
        val d = ArbitratedEscrowDatum(alice, bob, carol).toData
        for (action, payee) <- Seq(ArbitratedEscrowAction.Release -> alice, ArbitratedEscrowAction.Refund -> bob) do
            val ctx = spend(TxInfo.placeholder.copy(inputs = List(locked(c, Value.lovelace(50_000_000), d)),
                outputs = List(tagged(payee, 50_000_000)), signatories = List(alice, carol, bob)), action.toData, d)
            assert(high(c, ctx) == Set.empty, action.toString)
    }

    test("Vesting and Multisig pay no one: double satisfaction moot, nothing found") {
        val v = VestingContract.compiled
        val vd = VestingDatum(alice, bob, 1_000_000).toData
        val claim = spend(TxInfo.placeholder.copy(inputs = List(locked(v, Value.lovelace(5_000_000), vd)),
            signatories = List(bob), validRange = Interval.after(1_000_000)), VestingAction.Claim.toData, vd)
        assert(high(v, claim) == Set.empty)
        val m = MultisigContract.compiled
        val md = MultisigDatum(List(alice, bob, carol), 2).toData
        val ms = spend(TxInfo.placeholder.copy(inputs = List(locked(m, Value.lovelace(5_000_000), md)),
            signatories = List(alice, bob)), ().toData, md)
        assert(high(m, ms) == Set.empty)
    }

    private val seed = TestUtil.mockTxOutRef(5, 0)
    private val counter = StateCounterContract.compiled.withErrorTraces(seed.toData)

    test("StateCounter: increment, init and close, every HIGH attack defended") {
        val h = counter.script.scriptHash
        val home = Address(Credential.ScriptCredential(h), POption.None)
        val token = Value(h, StateCounter.stateName, 1)
        val state = Value.lovelace(2_000_000) + token
        def out(n: BigInt) = TxOut(home, state, OutputDatum.OutputDatum(CounterState(alice, n).toData))
        val program = counter.program.deBruijnedProgram
        def accepted(ctx: ScriptContext) = Attacks.run(program, ctx).fold(fail(_), identity)
            .filter(t => t.accepted && t.attack.severity == Attacks.Severity.High).map(_.attack.id)
        val inc = ScriptContext(TxInfo.placeholder.copy(inputs = List(TxInInfo(ref, out(0))), outputs = List(out(1)),
            signatories = List(alice)), CounterAction.Increment.toData, ScriptInfo.SpendingScript(ref, POption.None))
        assert(accepted(inc) == scala.Nil)
        val seedIn = TxInInfo(seed, TxOut(Address.fromPubKeyHash(alice), Value.lovelace(5_000_000),
            OutputDatum.NoOutputDatum))
        val init = ScriptContext(TxInfo.placeholder.copy(inputs = List(seedIn), outputs = List(out(0)), mint = token,
            signatories = List(alice)), CounterMint.Init.toData, ScriptInfo.MintingScript(h))
        assert(accepted(init) == scala.Nil)
        val burn = ScriptContext(TxInfo.placeholder.copy(mint = Value(h, StateCounter.stateName, -1)),
            CounterMint.Burn.toData, ScriptInfo.MintingScript(h))
        assert(accepted(burn) == scala.Nil)
    }

    test("OneShotNft: mint and burn, neither extra mint nor sneaky burn") {
        val p = OneShotNftContract.compiled.withErrorTraces(seed.toData)
        val h = p.script.scriptHash
        val name = ByteString.fromString("artwork-1")
        val seedIn = TxInInfo(seed, TxOut(Address.fromPubKeyHash(alice), Value.lovelace(5_000_000),
            OutputDatum.NoOutputDatum))
        val program = p.program.deBruijnedProgram
        def accepted(ctx: ScriptContext) = Attacks.run(program, ctx).fold(fail(_), identity)
            .filter(t => t.accepted && t.attack.severity == Attacks.Severity.High).map(_.attack.id)
        val mint = ScriptContext(TxInfo.placeholder.copy(inputs = List(seedIn), mint = Value(h, name, 1)),
            NftAction.Mint(name).toData, ScriptInfo.MintingScript(h))
        assert(accepted(mint) == scala.Nil)
        val burn = ScriptContext(TxInfo.placeholder.copy(mint = Value(h, name, -1)), NftAction.Burn.toData,
            ScriptInfo.MintingScript(h))
        assert(accepted(burn) == scala.Nil)
    }
}
