package validators

import org.scalacheck.Gen
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import scalus.uplc.builtin.Data.toData
import scalus.cardano.onchain.plutus.v3.*
import scalus.cardano.onchain.plutus.prelude.List
import scalus.testing.kit.TestUtil

class MultisigSpec extends AnyFunSuite, Fixtures, ScalaCheckPropertyChecks {

    private val ref = TestUtil.mockTxOutRef(1, 0)
    private val board = (1 to 5).map(i => TestUtil.mockPubKeyHash(i)).toList
    private val outsiders = (100 to 103).map(i => TestUtil.mockPubKeyHash(i)).toList

    private def spend(datum: MultisigDatum, signers: scala.List[PubKeyHash]) =
        run(
          MultisigContract.compiled,
          spending(ref, datum.toData, ().toData, TxInfo.placeholder.copy(signatories = List.from(signers)))
        )

    test("2 of 3 signs") {
        val d = MultisigDatum(List.from(board.take(3)), 2)
        assert(spend(d, board.take(2)).isSuccess)
        assert(spend(d, board.take(1)).failsWith(Multisig.NotEnoughSignatures))
    }

    test("a key listed twice in the datum counts once") {
        val d = MultisigDatum(List(alice, alice, bob), 2)
        assert(spend(d, scala.List(alice)).failsWith(Multisig.NotEnoughSignatures))
    }

    test("a threshold of zero is rejected, even with no signers") {
        assert(spend(MultisigDatum(List.from(board), 0), Nil).failsWith(Multisig.BadThreshold))
    }

    test("property: spending passes iff at least `threshold` board members sign") {
        val gen = for
            threshold <- Gen.choose(1, 5)
            members <- Gen.someOf(board)
            strangers <- Gen.someOf(outsiders)
        yield (threshold, members.toList, strangers.toList)
        forAll(gen) { case (threshold, members, strangers) =>
            val r = spend(MultisigDatum(List.from(board), threshold), members ++ strangers)
            assert(r.isSuccess == (members.size >= threshold), s"t=$threshold members=${members.size}")
        }
    }
}
