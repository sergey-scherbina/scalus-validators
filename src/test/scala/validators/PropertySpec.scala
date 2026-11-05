package validators

import okay.cardano.Explore
import org.scalatest.funsuite.AnyFunSuite
import scalus.uplc.PlutusV3
import scalus.uplc.builtin.{ByteString, Data}
import scalus.uplc.builtin.Data.{fromData, toData}
import scalus.cardano.onchain.plutus.v1.Address
import scalus.cardano.onchain.plutus.v2.OutputDatum
import scalus.cardano.onchain.plutus.v3.*
import scalus.cardano.onchain.plutus.prelude.{List, Option as POption}
import scalus.testing.kit.TestUtil

/** THE CONTRACTS AGAINST THEIR INTENT (okay-cardano `Explore`): each passing case changed at random, hundreds of
  * times, each change run on the script and judged by a rule written here in plain Scala — what SHOULD pass. A
  * disagreement either way (the script accepts what the rule rejects, or refuses what it accepts) fails, shrunk to
  * the fewest changes. */
class PropertySpec extends AnyFunSuite, Fixtures {

    private val ref = TestUtil.mockTxOutRef(1, 0)
    private val tag = OutputDatum.OutputDatum(ref.toData)

    private def agree(c: PlutusV3[?], ctx: ScriptContext, rule: ScriptContext => Boolean): Unit =
        val ms = Explore(c.program.deBruijnedProgram, ctx, rule, tries = 600).fold(e => fail(e), identity)
        assert(ms.isEmpty, ms.map(m => s"${m.kind}: ${m.changes.mkString(" + ")} — ${m.logs.lastOption.getOrElse("")}")
            .mkString("\n"))

    private def datum[A: scalus.uplc.builtin.FromData](c: ScriptContext): A = c.scriptInfo match
        case ScriptInfo.SpendingScript(_, POption.Some(d)) => fromData[A](d)
        case _                                             => throw IllegalStateException("not a spend")
    private def own(c: ScriptContext): TxOut =
        c.txInfo.inputs.toScalaList.find(_.outRef.toData == ref.toData).get.resolved
    private def signed(c: ScriptContext, k: PubKeyHash) = c.txInfo.signatories.toScalaList.exists(_ == k)
    private def paidTagged(c: ScriptContext, to: PubKeyHash, atLeast: BigInt) = c.txInfo.outputs.toScalaList.exists(o =>
        o.address.credential == Credential.PubKeyCredential(to) && o.value.getLovelace >= atLeast && o.datum == tag)

    private def locked(c: PlutusV3[?], value: Value, d: Data) =
        TxInInfo(ref, TxOut(Address(Credential.ScriptCredential(c.script.scriptHash), POption.None), value,
            OutputDatum.OutputDatum(d)))

    private def listingBuy(c: PlutusV3[?]) =
        val d = ListingDatum(alice, 10_000_000)
        ScriptContext(TxInfo.placeholder.copy(inputs = List(locked(c, Value.lovelace(2_000_000), d.toData)),
            outputs = List(TxOut(Address.fromPubKeyHash(alice), Value.lovelace(10_000_000), tag))),
            ListingAction.Buy.toData, ScriptInfo.SpendingScript(ref, POption.Some(d.toData)))

    private def listingRule(x: ScriptContext): Boolean = x.redeemer match
        case r if r == ListingAction.Buy.toData =>
            val l = datum[ListingDatum](x); paidTagged(x, l.seller, l.price)
        case r if r == ListingAction.Cancel.toData => signed(x, datum[ListingDatum](x).seller)
        case _                                     => false

    test("Listing: buy passes iff the seller is paid the price, tagged") {
        val c = ListingContract.compiled.withErrorTraces
        agree(c, listingBuy(c), listingRule)
    }

    test("the rules have teeth: NaiveListing disagrees with Listing's rule, untagged payments let through") {
        val naive = { given scalus.compiler.Options = scalus.compiler.Options.release
            PlutusV3.compile(NaiveListing.validate).withErrorTraces }
        val ms = Explore(naive.program.deBruijnedProgram, listingBuy(naive), listingRule, tries = 600)
            .fold(e => fail(e), identity)
        assert(ms.exists(m => m.kind == Explore.Kind.TooLoose && m.changes.exists(_.contains("tag"))),
            ms.map(_.changes))
    }

    test("ArbitratedEscrow: settles iff two distinct parties sign and the payee gets all of it, tagged") {
        val c = ArbitratedEscrowContract.compiled.withErrorTraces
        val d = ArbitratedEscrowDatum(alice, bob, carol)
        val ctx = ScriptContext(TxInfo.placeholder.copy(
            inputs = List(locked(c, Value.lovelace(50_000_000), d.toData)),
            outputs = List(TxOut(Address.fromPubKeyHash(alice), Value.lovelace(50_000_000), tag)),
            signatories = List(alice, carol)),
            ArbitratedEscrowAction.Release.toData, ScriptInfo.SpendingScript(ref, POption.Some(d.toData)))
        agree(c, ctx, x =>
            val e = datum[ArbitratedEscrowDatum](x)
            val two = List(e.buyer, e.seller, e.arbiter).toScalaList.distinct.count(signed(x, _)) >= 2
            val all = own(x).value.getLovelace
            x.redeemer match
                case r if r == ArbitratedEscrowAction.Release.toData => two && paidTagged(x, e.seller, all)
                case r if r == ArbitratedEscrowAction.Refund.toData  => two && paidTagged(x, e.buyer, all)
                case _                                               => false)
    }

    test("StateCounter: increment passes iff the state moves by one, whole, signed by its owner") {
        val seed = TestUtil.mockTxOutRef(5, 0)
        val c = StateCounterContract.compiled.withErrorTraces(seed.toData)
        val h = c.script.scriptHash
        val home = Address(Credential.ScriptCredential(h), POption.None)
        val nft = Value(h, StateCounter.stateName, 1)
        def out(n: BigInt) = TxOut(home, Value.lovelace(2_000_000) + nft, OutputDatum.OutputDatum(CounterState(alice, n)
            .toData))
        val ctx = ScriptContext(TxInfo.placeholder.copy(inputs = List(TxInInfo(ref, out(0))), outputs = List(out(1)),
            signatories = List(alice)), CounterAction.Increment.toData, ScriptInfo.SpendingScript(ref, POption.None))
        agree(c, ctx, x =>
            val in = own(x)
            val state = in.datum match
                case OutputDatum.OutputDatum(d) => Some(fromData[CounterState](d))
                case _                          => None
            val back = x.txInfo.outputs.toScalaList.filter(_.address.credential == home.credential)
            state.exists(st => x.redeemer == CounterAction.Increment.toData && signed(x, st.owner) && back.size == 1 &&
            back.head.address == in.address && back.head.value.withoutLovelace == nft &&
            back.head.value.getLovelace >= in.value.getLovelace &&
            back.head.datum == OutputDatum.OutputDatum(CounterState(st.owner, st.count + 1).toData) &&
            x.txInfo.mint.tokens(h).isEmpty))
    }
}
