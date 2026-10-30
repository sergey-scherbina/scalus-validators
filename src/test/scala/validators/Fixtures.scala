package validators

import scalus.uplc.builtin.Data
import scalus.uplc.builtin.Data.toData
import scalus.uplc.eval.Result
import scalus.uplc.PlutusV3
import scalus.cardano.onchain.plutus.v3.*
import scalus.cardano.onchain.plutus.prelude.Option
import scalus.testing.kit.{ScalusTest, TestUtil}

/** Hand-built script contexts: a transaction holds only the fields the validator under test reads. */
trait Fixtures extends ScalusTest {

    val alice: PubKeyHash = TestUtil.mockPubKeyHash(1)
    val bob: PubKeyHash = TestUtil.mockPubKeyHash(2)
    val carol: PubKeyHash = TestUtil.mockPubKeyHash(3)
    val eve: PubKeyHash = TestUtil.mockPubKeyHash(66)

    def spending(ref: TxOutRef, datum: Data, redeemer: Data, tx: TxInfo): ScriptContext =
        ScriptContext(tx, redeemer, ScriptInfo.SpendingScript(ref, Option.Some(datum)))

    def run(contract: PlutusV3[Data => Unit], ctx: ScriptContext): Result =
        contract.withErrorTraces.program.runWithDebug(ctx)

    extension (r: Result)
        def failsWith(message: String): Boolean = r.isFailure && r.logs.exists(_.contains(message))
        def explain: String = r.logs.mkString("\n")
}
