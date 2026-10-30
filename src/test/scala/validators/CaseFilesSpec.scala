package validators

import java.nio.file.{Files, Path}
import okay.cardano.{CaseFile, ProjectFiles}
import org.scalatest.funsuite.AnyFunSuite
import scalus.compiler.Options
import scalus.uplc.PlutusV3
import scalus.uplc.builtin.Data.toData
import scalus.cardano.onchain.plutus.v3.*
import scalus.cardano.onchain.plutus.prelude.List
import scalus.testing.kit.TestUtil

/** A failing case written as a file for okay-uplc (its "A case file…"): the script with its Scala positions, the
  * context — open target/okay-uplc/vesting-early-claim.json and land on the line that fails
  */
class CaseFilesSpec extends AnyFunSuite {
    private given Options = Options.default

    test("Vesting's early claim, written for the debugger") {
        val program = PlutusV3.compile(Vesting.validate).withErrorTraces.program.deBruijnedProgram
        val datum = VestingDatum(TestUtil.mockPubKeyHash(1), TestUtil.mockPubKeyHash(2), 1_000_000)
        val tx = TxInfo.placeholder.copy(signatories = List(TestUtil.mockPubKeyHash(2)),
          validRange = Interval.after(999_999))
        val own = TestUtil.mockTxOutRef(1, 0)
        val spending = ScriptInfo.SpendingScript(own, scalus.cardano.onchain.plutus.prelude.Option.Some(datum.toData))
        val ctx = ScriptContext(tx, VestingAction.Claim.toData, spending)
        val name = "Vesting: early claim (from scalus-validators)"
        val json = CaseFile.of(name, "a claim one millisecond early", program, ctx, ProjectFiles.read)
        val out = Path.of("target/okay-uplc/vesting-early-claim.json")
        Files.createDirectories(out.getParent)
        Files.writeString(out, json)
        val back = CaseFile.read(json).fold(fail(_), identity)
        assert(back.sources.exists(_.lines.keys.exists(_.endsWith("Vesting.scala"))), "the Scala travels with it")
    }
}
