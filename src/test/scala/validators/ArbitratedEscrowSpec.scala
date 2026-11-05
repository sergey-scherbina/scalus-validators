package validators

import org.scalacheck.Gen
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import scalus.uplc.builtin.Data.toData
import scalus.uplc.eval.Result
import scalus.cardano.onchain.plutus.v1.Address
import scalus.cardano.onchain.plutus.v2.OutputDatum
import scalus.cardano.onchain.plutus.v3.*
import scalus.cardano.onchain.plutus.prelude.{List, Option as POption}
import scalus.testing.kit.TestUtil

class ArbitratedEscrowSpec extends AnyFunSuite, Fixtures, ScalaCheckPropertyChecks {

    // alice sells, bob buys, carol arbitrates; eve is nobody
    private val datum = ArbitratedEscrowDatum(seller = alice, buyer = bob, arbiter = carol)
    private val locked = BigInt(50_000_000)
    private val script = Address(Credential.ScriptCredential(TestUtil.mockScriptHash(7)), POption.None)
    private val ref1 = TestUtil.mockTxOutRef(1, 0)
    private val ref2 = TestUtil.mockTxOutRef(1, 1)

    private def escrow(ref: TxOutRef, d: ArbitratedEscrowDatum = datum) =
        TxInInfo(ref, TxOut(script, Value.lovelace(locked), OutputDatum.OutputDatum(d.toData)))
    private def pay(to: PubKeyHash, lovelace: BigInt, tag: POption[TxOutRef]) =
        TxOut(Address.fromPubKeyHash(to), Value.lovelace(lovelace), tag match
            case POption.Some(r) => OutputDatum.OutputDatum(r.toData)
            case POption.None    => OutputDatum.NoOutputDatum)

    private def settle(action: ArbitratedEscrowAction, signers: List[PubKeyHash], outputs: List[TxOut],
        ref: TxOutRef = ref1, inputs: List[TxInInfo] = List(escrow(ref1)),
        d: ArbitratedEscrowDatum = datum): Result =
        run(ArbitratedEscrowContract.compiled, spending(ref, d.toData, action.toData,
            TxInfo.placeholder.copy(inputs = inputs, outputs = outputs, signatories = signers)))

    private val toSeller = List(pay(alice, locked, POption.Some(ref1)))
    private val toBuyer = List(pay(bob, locked, POption.Some(ref1)))
    import ArbitratedEscrowAction.{Refund, Release}

    test("no dispute: buyer and seller release to the seller") {
        val r = settle(Release, List(bob, alice), toSeller)
        assert(r.isSuccess, r.explain)
    }

    test("no dispute: buyer and seller agree to refund") {
        assert(settle(Refund, List(bob, alice), toBuyer).isSuccess)
    }

    test("dispute won by the seller: seller and arbiter release") {
        assert(settle(Release, List(alice, carol), toSeller).isSuccess)
    }

    test("dispute won by the buyer: buyer and arbiter refund") {
        assert(settle(Refund, List(bob, carol), toBuyer).isSuccess)
    }

    test("one signature is never enough — not even the arbiter's") {
        for who <- Seq(alice, bob, carol) do
            assert(settle(Release, List(who), toSeller).failsWith(ArbitratedEscrow.TwoOfThree), s"$who")
            assert(settle(Refund, List(who), toBuyer).failsWith(ArbitratedEscrow.TwoOfThree), s"$who")
    }

    test("an outsider's signature does not count") {
        assert(settle(Release, List(alice, eve), toSeller).failsWith(ArbitratedEscrow.TwoOfThree))
    }

    test("the same key signing twice counts once") {
        assert(settle(Release, List(alice, alice), toSeller).failsWith(ArbitratedEscrow.TwoOfThree))
    }

    test("one key in two roles: the seller named also as arbiter cannot release alone") {
        val rigged = ArbitratedEscrowDatum(seller = alice, buyer = bob, arbiter = alice)
        val r = settle(Release, List(alice), toSeller, inputs = List(escrow(ref1, rigged)), d = rigged)
        assert(r.failsWith(ArbitratedEscrow.TwoOfThree))
        // with the buyer it still settles: 2-of-2, the money never stuck
        val ok = settle(Release, List(alice, bob), toSeller, inputs = List(escrow(ref1, rigged)), d = rigged)
        assert(ok.isSuccess, ok.explain)
    }

    test("the money goes only where the action says: arbiter and seller cannot pay themselves on a refund") {
        val r = settle(Refund, List(alice, carol), List(pay(alice, locked, POption.Some(ref1))))
        assert(r.failsWith(ArbitratedEscrow.NotPaid))
    }

    test("all of it: paying the seller less than the escrow holds is rejected") {
        val r = settle(Release, List(alice, carol), List(pay(alice, locked - 1, POption.Some(ref1))))
        assert(r.failsWith(ArbitratedEscrow.NotPaid))
    }

    test("an untagged payment is rejected") {
        val r = settle(Release, List(alice, carol), List(pay(alice, locked, POption.None)))
        assert(r.failsWith(ArbitratedEscrow.NotPaid))
    }

    test("double satisfaction: one payment cannot settle two escrows") {
        val both = List(escrow(ref1), escrow(ref2))
        val outs = List(pay(alice, locked, POption.Some(ref1)))
        assert(settle(Release, List(alice, carol), outs, ref1, both).isSuccess)
        assert(settle(Release, List(alice, carol), outs, ref2, both).failsWith(ArbitratedEscrow.NotPaid))
    }

    test("a missing datum is rejected") {
        val ctx = ScriptContext(TxInfo.placeholder.copy(inputs = List(escrow(ref1)), signatories = List(alice, bob)),
            Release.toData, ScriptInfo.SpendingScript(ref1, POption.None))
        assert(run(ArbitratedEscrowContract.compiled, ctx).failsWith(ArbitratedEscrow.NoDatum))
    }

    test("property: settles iff at least two distinct parties sign, whatever outsiders also sign") {
        val parties = Seq(alice, bob, carol)
        forAll(Gen.someOf(parties), Gen.someOf(Seq(eve, TestUtil.mockPubKeyHash(77)))) { (ps, outsiders) =>
            val signers = List.from(ps ++ outsiders)
            val r = settle(Release, signers, toSeller)
            assert(r.isSuccess == (ps.distinct.size >= 2), s"${ps.size} parties")
        }
    }
}
