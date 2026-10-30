package validators

import org.scalacheck.Gen
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import scalus.uplc.builtin.Data
import scalus.uplc.builtin.Data.toData
import scalus.cardano.onchain.plutus.v3.*
import scalus.cardano.onchain.plutus.prelude.{List, Option}
import scalus.testing.kit.TestUtil

class VestingSpec extends AnyFunSuite, Fixtures, ScalaCheckPropertyChecks {

    private val deadline = BigInt(1_000_000)
    private val datum = VestingDatum(owner = alice, beneficiary = bob, deadline = deadline)
    private val ref = TestUtil.mockTxOutRef(1, 0)

    private def spend(action: VestingAction, signers: List[PubKeyHash], range: Interval) =
        run(
          VestingContract.compiled,
          spending(ref, datum.toData, action.toData, TxInfo.placeholder.copy(signatories = signers, validRange = range))
        )

    test("the beneficiary claims at the deadline") {
        val r = spend(VestingAction.Claim, List(bob), Interval.after(deadline))
        assert(r.isSuccess, r.explain)
    }

    test("a claim one millisecond early is rejected") {
        assert(spend(VestingAction.Claim, List(bob), Interval.after(deadline - 1)).failsWith(Vesting.TooEarly))
    }

    test("nobody but the beneficiary claims") {
        assert(spend(VestingAction.Claim, List(eve), Interval.after(deadline)).failsWith(Vesting.BeneficiaryMustSign))
    }

    test("a claim without a lower bound is rejected") {
        assert(spend(VestingAction.Claim, List(bob), Interval.always).failsWith(Vesting.Unbounded))
    }

    test("the owner cancels before the deadline, not after") {
        assert(spend(VestingAction.Cancel, List(alice), Interval.between(0, deadline)).isSuccess)
        assert(spend(VestingAction.Cancel, List(alice), Interval.between(0, deadline + 1)).failsWith(Vesting.TooLate))
    }

    test("an output without a datum cannot be spent") {
        val tx = TxInfo.placeholder.copy(signatories = List(bob), validRange = Interval.after(deadline))
        val ctx = ScriptContext(tx, VestingAction.Claim.toData, ScriptInfo.SpendingScript(ref, Option.None))
        assert(run(VestingContract.compiled, ctx).failsWith(Vesting.NoDatum))
    }

    // The validator agrees with the rule written in plain Scala, for any time and any signer.

    private val signer = Gen.oneOf(alice, bob, eve)

    test("property: a claim passes iff the beneficiary signs and from >= deadline") {
        forAll(Gen.choose(0L, 2_000_000L), signer) { (from, who) =>
            val r = spend(VestingAction.Claim, List(who), Interval.after(BigInt(from)))
            assert(r.isSuccess == (who == bob && from >= deadline), s"from=$from\n${r.explain}")
        }
    }

    test("property: a cancel passes iff the owner signs and to <= deadline") {
        forAll(Gen.choose(1L, 2_000_000L), signer) { (to, who) =>
            val r = spend(VestingAction.Cancel, List(who), Interval.between(0, BigInt(to)))
            assert(r.isSuccess == (who == alice && to <= deadline), s"to=$to\n${r.explain}")
        }
    }

    test("property: a redeemer that is neither action is rejected") {
        forAll(Gen.choose(2L, 1000L)) { tag =>
            val tx = TxInfo.placeholder.copy(signatories = List(alice, bob), validRange = Interval.after(deadline))
            val ctx = spending(ref, datum.toData, Data.Constr(tag, List.Nil), tx)
            assert(run(VestingContract.compiled, ctx).isFailure)
        }
    }
}
