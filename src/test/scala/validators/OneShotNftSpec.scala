package validators

import org.scalacheck.Gen
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import scalus.uplc.builtin.ByteString.utf8
import scalus.uplc.builtin.Data.toData
import scalus.uplc.eval.Result
import scalus.cardano.onchain.plutus.v1.Address
import scalus.cardano.onchain.plutus.v2.OutputDatum
import scalus.cardano.onchain.plutus.v3.*
import scalus.cardano.onchain.plutus.prelude.List
import scalus.testing.kit.TestUtil

class OneShotNftSpec extends AnyFunSuite, Fixtures, ScalaCheckPropertyChecks {

    private val seed = TestUtil.mockTxOutRef(5, 0)
    private val policy = OneShotNftContract.compiled.withErrorTraces(seed.toData)
    private val policyId = TestUtil.mockScriptHash(7)
    private val name = utf8"artwork-1"

    private def input(ref: TxOutRef) =
        TxInInfo(ref, TxOut(Address.fromPubKeyHash(alice), Value.lovelace(5_000_000), OutputDatum.NoOutputDatum))

    private def mint(action: NftAction, spent: List[TxOutRef], minted: Value): Result = {
        val tx = TxInfo.placeholder.copy(inputs = spent.map(input), mint = minted)
        policy.program.runWithDebug(ScriptContext(tx, action.toData, ScriptInfo.MintingScript(policyId)))
    }

    test("mints one token when the seed is spent") {
        val r = mint(NftAction.Mint(name), List(seed), Value(policyId, name, 1))
        assert(r.isSuccess, r.explain)
    }

    test("cannot mint without spending the seed") {
        val other = TestUtil.mockTxOutRef(6, 0)
        assert(mint(NftAction.Mint(name), List(other), Value(policyId, name, 1)).failsWith(OneShotNft.SeedNotSpent))
    }

    test("cannot mint two, or a second token beside it") {
        assert(mint(NftAction.Mint(name), List(seed), Value(policyId, name, 2)).failsWith(OneShotNft.MintExactlyOne))
        val extra = Value(policyId, name, 1) + Value(policyId, utf8"extra", 1)
        assert(mint(NftAction.Mint(name), List(seed), extra).failsWith(OneShotNft.MintExactlyOne))
    }

    test("burns, but a burn that also mints is rejected") {
        assert(mint(NftAction.Burn, List.Nil, Value(policyId, name, -1)).isSuccess)
        val sneaky = Value(policyId, name, -1) + Value(policyId, utf8"new", 1)
        assert(mint(NftAction.Burn, List.Nil, sneaky).failsWith(OneShotNft.OnlyBurn))
    }

    test("every seed gives its own policy id") {
        val other = OneShotNftContract.compiled(TestUtil.mockTxOutRef(6, 0).toData)
        assert(OneShotNftContract.compiled(seed.toData).script.scriptHash != other.script.scriptHash)
    }

    test("property: Mint passes only for a quantity of exactly 1") {
        forAll(Gen.choose(-5L, 5L)) { qty =>
            val r = mint(NftAction.Mint(name), List(seed), Value(policyId, name, qty))
            assert(r.isSuccess == (qty == 1L), s"qty=$qty")
        }
    }

    test("property: Burn passes iff the quantity is negative") {
        forAll(Gen.choose(-5L, 5L).suchThat(_ != 0)) { qty =>
            assert(mint(NftAction.Burn, List.Nil, Value(policyId, name, qty)).isSuccess == (qty < 0), s"qty=$qty")
        }
    }
}
