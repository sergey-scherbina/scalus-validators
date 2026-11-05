package validators

import org.scalacheck.Gen
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import scalus.compiler.Options
import scalus.uplc.PlutusV3
import scalus.uplc.builtin.ByteString
import scalus.uplc.builtin.Data.toData
import scalus.uplc.eval.Result
import scalus.cardano.onchain.plutus.v1.Address
import scalus.cardano.onchain.plutus.v2.OutputDatum
import scalus.cardano.onchain.plutus.v3.*
import scalus.cardano.onchain.plutus.prelude.{List, Option as POption}
import scalus.testing.kit.TestUtil

class ListingSpec extends AnyFunSuite, Fixtures, ScalaCheckPropertyChecks {

    private val naive = {
        given Options = Options.release
        PlutusV3.compile(NaiveListing.validate)
    }
    private val script = Address(Credential.ScriptCredential(TestUtil.mockScriptHash(7)), POption.None)
    private val price = BigInt(10_000_000)
    private val datum = ListingDatum(seller = alice, price = price)
    private val refA = TestUtil.mockTxOutRef(1, 0)
    private val refB = TestUtil.mockTxOutRef(2, 0)
    private val policy = TestUtil.mockScriptHash(9)
    private def nft(name: String) = Value(policy, ByteString.fromString(name), 1)

    private def listed(ref: TxOutRef, item: String) =
        TxInInfo(ref, TxOut(script, Value.lovelace(2_000_000) + nft(item), OutputDatum.OutputDatum(datum.toData)))
    private def pay(tag: POption[TxOutRef], lovelace: BigInt = price) =
        TxOut(Address.fromPubKeyHash(alice), Value.lovelace(lovelace), tag match
            case POption.Some(r) => OutputDatum.OutputDatum(r.toData)
            case POption.None    => OutputDatum.NoOutputDatum)

    /** the validator run for ONE input, as the node runs it once per script input */
    private def spend(contract: PlutusV3[scalus.uplc.builtin.Data => Unit], ins: List[TxInInfo], outs: List[TxOut],
        ref: TxOutRef, action: ListingAction = ListingAction.Buy, signers: List[PubKeyHash] = List.Nil): Result =
        run(contract, spending(ref, datum.toData, action.toData,
            TxInfo.placeholder.copy(inputs = ins, outputs = outs, signatories = signers)))

    // the attack: two listings of one seller at one price, ONE payment, both items to the buyer
    private val both = List(listed(refA, "A"), listed(refB, "B"))
    private val toBuyer = TxOut(Address.fromPubKeyHash(bob), nft("A") + nft("B"), OutputDatum.NoOutputDatum)

    test("double satisfaction: on the naive listing one payment settles two listings") {
        val outs = List(pay(POption.None), toBuyer)
        assert(spend(naive, both, outs, refA).isSuccess)
        assert(spend(naive, both, outs, refB).isSuccess) // both pass: the buyer paid once for two
    }

    test("double satisfaction: the listing refuses the same attack") {
        val outs = List(pay(POption.Some(refA)), toBuyer) // the one payment names listing A
        assert(spend(ListingContract.compiled, both, outs, refA).isSuccess)
        assert(spend(ListingContract.compiled, both, outs, refB).failsWith(Listing.SellerNotPaid))
    }

    test("two listings, two tagged payments: both bought in one transaction") {
        val outs = List(pay(POption.Some(refA)), pay(POption.Some(refB)), toBuyer)
        assert(spend(ListingContract.compiled, both, outs, refA).isSuccess)
        assert(spend(ListingContract.compiled, both, outs, refB).isSuccess)
    }

    test("an untagged payment is rejected") {
        val r = spend(ListingContract.compiled, List(listed(refA, "A")), List(pay(POption.None)), refA)
        assert(r.failsWith(Listing.SellerNotPaid))
    }

    test("a payment tagged with another listing is rejected") {
        val r = spend(ListingContract.compiled, List(listed(refA, "A")), List(pay(POption.Some(refB))), refA)
        assert(r.failsWith(Listing.SellerNotPaid))
    }

    test("property: a tagged payment buys iff it is at least the price") {
        forAll(Gen.choose(0L, 20_000_000L)) { paid =>
            val r = spend(ListingContract.compiled, List(listed(refA, "A")), List(pay(POption.Some(refA), paid)), refA)
            assert(r.isSuccess == (BigInt(paid) >= price), s"paid=$paid")
        }
    }

    test("only the seller cancels") {
        val one = List(listed(refA, "A"))
        assert(spend(ListingContract.compiled, one, List.Nil, refA, ListingAction.Cancel, List(alice)).isSuccess)
        val r = spend(ListingContract.compiled, one, List.Nil, refA, ListingAction.Cancel, List(eve))
        assert(r.failsWith(Listing.SellerMustSign))
    }
}
