package validators

import org.scalacheck.Gen
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import scalus.uplc.builtin.Data.toData
import scalus.cardano.onchain.plutus.v1.Address
import scalus.cardano.onchain.plutus.v2.OutputDatum
import scalus.cardano.onchain.plutus.v3.*
import scalus.cardano.onchain.plutus.prelude.List
import scalus.testing.kit.TestUtil

class EscrowSpec extends AnyFunSuite, Fixtures, ScalaCheckPropertyChecks {

    private val price = BigInt(50_000_000)
    private val deadline = BigInt(1_000_000)
    private val datum = EscrowDatum(seller = alice, buyer = bob, price = price, deadline = deadline)
    private val ref1 = TestUtil.mockTxOutRef(1, 0)
    private val ref2 = TestUtil.mockTxOutRef(1, 1)

    private def payment(to: PubKeyHash, lovelace: BigInt, tag: OutputDatum) =
        TxOut(Address.fromPubKeyHash(to), Value.lovelace(lovelace), tag)

    private def tagged(ref: TxOutRef) = OutputDatum.OutputDatum(ref.toData)

    private def release(ref: TxOutRef, outputs: List[TxOut], signers: List[PubKeyHash] = List(bob)) =
        run(
          EscrowContract.compiled,
          spending(
            ref,
            datum.toData,
            EscrowAction.Release.toData,
            TxInfo.placeholder.copy(outputs = outputs, signatories = signers)
          )
        )

    test("the buyer releases: the seller is paid the price, tagged with the escrow") {
        val r = release(ref1, List(payment(alice, price, tagged(ref1))))
        assert(r.isSuccess, r.explain)
    }

    test("the seller paid less than the price is rejected") {
        assert(release(ref1, List(payment(alice, price - 1, tagged(ref1)))).failsWith(Escrow.SellerNotPaid))
    }

    test("a payment to someone else is rejected") {
        assert(release(ref1, List(payment(eve, price, tagged(ref1)))).failsWith(Escrow.SellerNotPaid))
    }

    test("an untagged payment is rejected") {
        assert(release(ref1, List(payment(alice, price, OutputDatum.NoOutputDatum))).failsWith(Escrow.SellerNotPaid))
    }

    test("only the buyer releases") {
        assert(release(ref1, List(payment(alice, price, tagged(ref1))), List(alice)).failsWith(Escrow.BuyerMustSign))
    }

    test("double satisfaction: one payment cannot settle two escrows") {
        // Two escrows with the same seller and price spent in one transaction, one payment.
        val onePayment = List(payment(alice, price, tagged(ref1)))
        assert(release(ref1, onePayment).isSuccess)
        assert(release(ref2, onePayment).failsWith(Escrow.SellerNotPaid))
        // With a payment each, both pass.
        val twoPayments = List(payment(alice, price, tagged(ref1)), payment(alice, price, tagged(ref2)))
        assert(release(ref1, twoPayments).isSuccess && release(ref2, twoPayments).isSuccess)
    }

    test("the buyer is refunded after the deadline, not before") {
        def refund(from: BigInt) = run(
          EscrowContract.compiled,
          spending(
            ref1,
            datum.toData,
            EscrowAction.Refund.toData,
            TxInfo.placeholder.copy(signatories = List(bob), validRange = Interval.after(from))
          )
        )
        assert(refund(deadline).isSuccess)
        assert(refund(deadline - 1).failsWith(Escrow.TooEarly))
    }

    test("property: a release passes iff the seller gets at least the price") {
        forAll(Gen.choose(0L, 100_000_000L)) { paid =>
            val r = release(ref1, List(payment(alice, BigInt(paid), tagged(ref1))))
            assert(r.isSuccess == (BigInt(paid) >= price), s"paid=$paid")
        }
    }
}
